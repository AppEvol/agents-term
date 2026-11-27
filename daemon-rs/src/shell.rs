use std::ffi::CString;
use std::fs::{self, OpenOptions};
use std::io::{self, Read};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};

use crate::config::{enriched_path, ensure_dir_0700, resolve_binary, tmp_dir};
use crate::store::resolve_abs_path;
use crate::util::rand_hex;

const SHELL_DRIVER_SCRIPT: &str = r#"__at_dir="$__AGENTSTERM_SHELL_DIR"
unset __AGENTSTERM_SHELL_DIR
while IFS= builtin read -r -u 3 __at_seq; do
    __at_cmd="$__at_dir/$__at_seq.sh"
    __at_in="$__at_dir/$__at_seq.in"
    __at_out="$__at_dir/$__at_seq.out"
    __at_err="$__at_dir/$__at_seq.err"
    builtin source "$__at_cmd" <"$__at_in" >"$__at_out" 2>"$__at_err" 3<&- 4>&-
    __at_rc=$?
    builtin printf '%d\t%s\0' "$__at_rc" "$PWD" >&4
done
"#;

pub fn signal_process_group(pid: u32, sig: libc::c_int) {
    let pgid = -(pid as libc::pid_t);
    unsafe {
        let _ = libc::kill(pgid, sig);
        let _ = libc::kill(pid as libc::pid_t, sig);
    }
}

#[derive(Clone)]
pub struct ShellStdin {
    file: Arc<tokio::sync::Mutex<Option<tokio::fs::File>>>,
}

impl ShellStdin {
    fn new(file: tokio::fs::File) -> Self {
        Self {
            file: Arc::new(tokio::sync::Mutex::new(Some(file))),
        }
    }

    pub async fn write_and_maybe_eof(
        &self,
        data: Option<&str>,
        eof: bool,
    ) -> Result<(), &'static str> {
        let mut guard = self.file.lock().await;
        let Some(file) = guard.as_mut() else {
            return Err("No running shell command accepts stdin");
        };
        if let Some(text) = data {
            if !text.is_empty() {
                file.write_all(text.as_bytes())
                    .await
                    .map_err(|_| "Failed to write to shell stdin")?;
                file.flush()
                    .await
                    .map_err(|_| "Failed to flush shell stdin")?;
            }
        }
        if eof {
            if let Some(f) = guard.take() {
                let std_f = f.into_std().await;
                drop(std_f);
            }
        }
        Ok(())
    }

    pub async fn close(&self) {
        let mut guard = self.file.lock().await;
        if let Some(f) = guard.take() {
            let std_f = f.into_std().await;
            drop(std_f);
        }
    }
}

#[derive(Clone)]
pub enum StdinState {
    Pending,
    Ready(ShellStdin),
    Closed,
}

pub struct CommandOutcome {
    pub exit_code: Option<i32>,
    pub new_cwd: Option<String>,
    pub shell_alive: bool,
}

pub struct PersistentShell {
    child: Child,
    pid: u32,
    ctrl_write: tokio::fs::File,
    status_read: BufReader<tokio::fs::File>,
    session_dir: PathBuf,
    seq: u64,
    exited: bool,
}

fn make_pipe_cloexec() -> io::Result<(OwnedFd, OwnedFd)> {
    let mut fds = [0 as libc::c_int; 2];
    let rc = unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    unsafe { Ok((OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1]))) }
}

fn ensure_fd_ge_5(fd: OwnedFd) -> io::Result<OwnedFd> {
    if fd.as_raw_fd() >= 5 {
        return Ok(fd);
    }
    let duped = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 5) };
    if duped == -1 {
        return Err(io::Error::last_os_error());
    }
    drop(fd);
    Ok(unsafe { OwnedFd::from_raw_fd(duped) })
}

fn create_fifo_0600(path: &Path) -> io::Result<()> {
    let c_path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "FIFO path contains NUL"))?;
    let rc = unsafe { libc::mkfifo(c_path.as_ptr(), 0o600) };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn pump_fifo_utf8<F: FnMut(String)>(path: PathBuf, opened: Arc<AtomicBool>, mut cb: F) {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_CLOEXEC)
        .open(&path);
    opened.store(true, Ordering::Release);
    let mut file = match file {
        Ok(f) => f,
        Err(_) => return,
    };

    let mut buf = [0u8; 65_536];
    let mut pending = Vec::new();
    loop {
        match file.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                pending.extend_from_slice(&buf[..n]);
                loop {
                    match std::str::from_utf8(&pending) {
                        Ok(s) => {
                            if !s.is_empty() {
                                cb(s.to_string());
                            }
                            pending.clear();
                            break;
                        }
                        Err(err) => {
                            let valid_len = err.valid_up_to();
                            if valid_len > 0 {
                                let valid =
                                    String::from_utf8_lossy(&pending[..valid_len]).into_owned();
                                cb(valid);
                                pending.drain(..valid_len);
                            }
                            if let Some(err_len) = err.error_len() {
                                cb("\u{FFFD}".to_string());
                                pending.drain(..err_len);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
    if !pending.is_empty() {
        cb(String::from_utf8_lossy(&pending).into_owned());
    }
}

async fn finish_fifo_reader(
    path: &Path,
    opened: &Arc<AtomicBool>,
    task: tokio::task::JoinHandle<()>,
    shell_died: bool,
) {
    if !shell_died {
        let _ = task.await;
        return;
    }

    // If bash died before opening this FIFO for writing, the blocking open(O_RDONLY)
    // in the reader thread would wait forever. Nudge it with O_WRONLY|O_NONBLOCK until
    // open() returns (and then immediately sees EOF once our temporary write fd drops).
    let mut task = task;
    for _ in 0..40 {
        if opened.load(Ordering::Acquire) {
            let _ = task.await;
            return;
        }
        let _ = OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC)
            .open(path);
        if tokio::time::timeout(Duration::from_millis(25), &mut task)
            .await
            .is_ok()
        {
            return;
        }
    }
    let _ = task.await;
}

impl PersistentShell {
    pub fn spawn(tab_id: &str, cwd: &str) -> Result<Self, String> {
        let session_dir = tmp_dir().join(format!("shell-{tab_id}-{}", rand_hex(6)));
        ensure_dir_0700(&session_dir)
            .map_err(|e| format!("Failed to create shell session dir: {e}"))?;

        let result = Self::spawn_in_dir(cwd, session_dir.clone());
        if result.is_err() {
            let _ = fs::remove_dir_all(&session_dir);
        }
        result
    }

    fn spawn_in_dir(cwd: &str, session_dir: PathBuf) -> Result<Self, String> {
        let (ctrl_read, ctrl_write) =
            make_pipe_cloexec().map_err(|e| format!("Failed to create shell control pipe: {e}"))?;
        let (status_read, status_write) =
            make_pipe_cloexec().map_err(|e| format!("Failed to create shell status pipe: {e}"))?;

        let ctrl_read = ensure_fd_ge_5(ctrl_read)
            .map_err(|e| format!("Failed to prepare shell control fd: {e}"))?;
        let status_write = ensure_fd_ge_5(status_write)
            .map_err(|e| format!("Failed to prepare shell status fd: {e}"))?;

        let bash = resolve_binary("bash").unwrap_or_else(|| "/bin/bash".to_string());
        let mut cmd = Command::new(&bash);
        cmd.args(["-lc", SHELL_DRIVER_SCRIPT])
            .current_dir(cwd)
            .env("PATH", enriched_path())
            .env("FORCE_COLOR", "0")
            .env("NO_COLOR", "1")
            .env("TERM", "dumb")
            .env("__AGENTSTERM_SHELL_DIR", &session_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());

        let ctrl_r_raw = ctrl_read.as_raw_fd();
        let status_w_raw = status_write.as_raw_fd();
        unsafe {
            cmd.pre_exec(move || {
                if libc::setsid() == -1 {
                    return Err(io::Error::last_os_error());
                }
                if libc::dup2(ctrl_r_raw, 3) == -1 {
                    return Err(io::Error::last_os_error());
                }
                if libc::dup2(status_w_raw, 4) == -1 {
                    return Err(io::Error::last_os_error());
                }
                Ok(())
            });
        }

        let child = cmd
            .spawn()
            .map_err(|e| format!("Failed to start bash: {e}"))?;

        // Close child-side ends in parent immediately so EOF semantics work cleanly.
        drop(ctrl_read);
        drop(status_write);

        let pid = child
            .id()
            .ok_or_else(|| "Persistent bash exited immediately after spawn".to_string())?;
        let ctrl_write = tokio::fs::File::from_std(std::fs::File::from(ctrl_write));
        let status_read =
            BufReader::new(tokio::fs::File::from_std(std::fs::File::from(status_read)));

        Ok(Self {
            child,
            pid,
            ctrl_write,
            status_read,
            session_dir,
            seq: 0,
            exited: false,
        })
    }

    pub fn pid(&self) -> u32 {
        self.pid
    }

    pub fn session_dir(&self) -> &Path {
        &self.session_dir
    }

    pub fn is_alive(&mut self) -> bool {
        if self.exited {
            return false;
        }
        match self.child.try_wait() {
            Ok(None) => true,
            Ok(Some(_)) | Err(_) => {
                self.exited = true;
                false
            }
        }
    }

    pub async fn run_command<FOut, FErr>(
        &mut self,
        command: &str,
        on_stdin_ready: impl FnOnce(ShellStdin),
        on_stdout: FOut,
        on_stderr: FErr,
    ) -> Result<CommandOutcome, String>
    where
        FOut: FnMut(String) + Send + 'static,
        FErr: FnMut(String) + Send + 'static,
    {
        self.seq += 1;
        let seq = self.seq;
        let cmd_path = self.session_dir.join(format!("{seq}.sh"));
        let in_path = self.session_dir.join(format!("{seq}.in"));
        let out_path = self.session_dir.join(format!("{seq}.out"));
        let err_path = self.session_dir.join(format!("{seq}.err"));

        let script_bytes = format!("{command}\n");
        {
            use std::io::Write;
            let mut f = OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&cmd_path)
                .map_err(|e| format!("Failed to write command script: {e}"))?;
            f.write_all(script_bytes.as_bytes())
                .map_err(|e| format!("Failed to write command script: {e}"))?;
        }

        if let Err(e) = create_fifo_0600(&in_path)
            .and_then(|_| create_fifo_0600(&out_path))
            .and_then(|_| create_fifo_0600(&err_path))
        {
            cleanup_turn_files(&cmd_path, &in_path, &out_path, &err_path);
            return Err(format!("Failed to create shell FIFOs: {e}"));
        }

        let in_std = match OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(libc::O_CLOEXEC)
            .open(&in_path)
        {
            Ok(f) => f,
            Err(e) => {
                cleanup_turn_files(&cmd_path, &in_path, &out_path, &err_path);
                return Err(format!("Failed to open stdin FIFO: {e}"));
            }
        };
        let stdin_handle = ShellStdin::new(tokio::fs::File::from_std(in_std));
        on_stdin_ready(stdin_handle.clone());

        let out_opened = Arc::new(AtomicBool::new(false));
        let err_opened = Arc::new(AtomicBool::new(false));
        let out_task = {
            let p = out_path.clone();
            let opened = out_opened.clone();
            tokio::task::spawn_blocking(move || pump_fifo_utf8(p, opened, on_stdout))
        };
        let err_task = {
            let p = err_path.clone();
            let opened = err_opened.clone();
            tokio::task::spawn_blocking(move || pump_fifo_utf8(p, opened, on_stderr))
        };

        let trigger = format!("{seq}\n");
        let trigger_res = async {
            self.ctrl_write.write_all(trigger.as_bytes()).await?;
            self.ctrl_write.flush().await
        }
        .await;

        if trigger_res.is_err() {
            stdin_handle.close().await;
            let status = self.child.wait().await.ok();
            self.exited = true;
            finish_fifo_reader(&out_path, &out_opened, out_task, true).await;
            finish_fifo_reader(&err_path, &err_opened, err_task, true).await;
            cleanup_turn_files(&cmd_path, &in_path, &out_path, &err_path);
            return Ok(CommandOutcome {
                exit_code: status.and_then(|s| s.code()),
                new_cwd: None,
                shell_alive: false,
            });
        }

        let mut status_buf = Vec::new();
        let read_res = self.status_read.read_until(0u8, &mut status_buf).await;

        stdin_handle.close().await;

        let outcome = match read_res {
            Ok(n) if n > 0 && status_buf.last() == Some(&0u8) => {
                status_buf.pop();
                let line = String::from_utf8_lossy(&status_buf);
                let (rc_part, pwd_part) = line.split_once('\t').unwrap_or((line.as_ref(), ""));
                let exit_code = rc_part.trim().parse::<i32>().ok();
                let new_cwd = {
                    let trimmed = pwd_part.trim();
                    if !trimmed.is_empty() && Path::new(trimmed).is_dir() {
                        Some(resolve_abs_path(trimmed))
                    } else {
                        None
                    }
                };
                finish_fifo_reader(&out_path, &out_opened, out_task, false).await;
                finish_fifo_reader(&err_path, &err_opened, err_task, false).await;
                CommandOutcome {
                    exit_code,
                    new_cwd,
                    shell_alive: true,
                }
            }
            _ => {
                let status = self.child.wait().await.ok();
                self.exited = true;
                finish_fifo_reader(&out_path, &out_opened, out_task, true).await;
                finish_fifo_reader(&err_path, &err_opened, err_task, true).await;
                CommandOutcome {
                    exit_code: status.and_then(|s| s.code()),
                    new_cwd: None,
                    shell_alive: false,
                }
            }
        };

        cleanup_turn_files(&cmd_path, &in_path, &out_path, &err_path);
        Ok(outcome)
    }

    pub fn kill_and_cleanup(&mut self) {
        if !self.exited {
            signal_process_group(self.pid, libc::SIGKILL);
            let _ = self.child.start_kill();
            if let Ok(Some(_)) = self.child.try_wait() {
                self.exited = true;
            }
        }
        let _ = fs::remove_dir_all(&self.session_dir);
    }
}

impl Drop for PersistentShell {
    fn drop(&mut self) {
        self.kill_and_cleanup();
    }
}

fn cleanup_turn_files(cmd_path: &Path, in_path: &Path, out_path: &Path, err_path: &Path) {
    let _ = fs::remove_file(cmd_path);
    let _ = fs::remove_file(in_path);
    let _ = fs::remove_file(out_path);
    let _ = fs::remove_file(err_path);
}
