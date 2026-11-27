use std::collections::{HashMap, HashSet, VecDeque};
use std::fs::OpenOptions;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::ExitStatusExt;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;
use tokio::process::Command;
use tokio::sync::{mpsc, watch};
use tokio::task::JoinHandle;

use crate::config::{enriched_path, resolve_binary};
use crate::util::rand_hex;

pub const PTY_PROTOCOL_VERSION: u32 = 1;
pub const PTY_DEFAULT_COLS: u16 = 80;
pub const PTY_DEFAULT_ROWS: u16 = 24;
pub const PTY_MIN_COLS: u16 = 10;
pub const PTY_MAX_COLS: u16 = 512;
pub const PTY_MIN_ROWS: u16 = 4;
pub const PTY_MAX_ROWS: u16 = 256;
pub const PTY_BUFFER_BYTES: usize = 2 * 1024 * 1024;
pub const PTY_REPLAY_CHUNK_BYTES: usize = 64 * 1024;

const PTY_READ_CHUNK_BYTES: usize = 16 * 1024;
/// Bounded per-subscriber output queue (256 * 16 KiB <= 4 MiB worst-case).
/// Lagging subscribers are disconnected so clients reconnect and replay from the ring buffer.
const PTY_SUBSCRIBER_QUEUE_CAPACITY: usize = 256;
/// Bounded client-to-PTY input queue so a stalled child cannot OOM the daemon.
const PTY_INPUT_QUEUE_CAPACITY: usize = 256;
/// Bounded drain wait after child exit so trailing output arrives before `Exit`
/// without hanging indefinitely if a background job still holds the slave fd open.
const PTY_EXIT_DRAIN_TIMEOUT: Duration = Duration::from_millis(200);
/// Bounded grace period between SIGHUP+SIGCONT and SIGKILL escalation on `terminate()`.
const PTY_TERMINATE_GRACE: Duration = Duration::from_millis(100);
const PTY_TERMINATE_POLL: Duration = Duration::from_millis(5);

pub fn clamp_cols(cols: u16) -> u16 {
    cols.clamp(PTY_MIN_COLS, PTY_MAX_COLS)
}

pub fn clamp_rows(rows: u16) -> u16 {
    rows.clamp(PTY_MIN_ROWS, PTY_MAX_ROWS)
}

#[derive(Debug, Clone)]
pub enum PtyServerEvent {
    Output {
        start_seq: u64,
        data: Vec<u8>,
    },
    Exit {
        exit_code: Option<i32>,
        signal: Option<i32>,
    },
}

pub struct PtyAttachSnapshot {
    pub session_id: String,
    pub cols: u16,
    pub rows: u16,
    pub base_seq: u64,
    pub next_seq: u64,
    pub replay_from: u64,
    pub truncated: bool,
    pub running: bool,
    pub exit_code: Option<i32>,
    pub exit_signal: Option<i32>,
    pub replay_chunks: Vec<(u64, Vec<u8>)>,
    pub subscriber_id: u64,
    pub rx: mpsc::Receiver<PtyServerEvent>,
}

struct PtyStreamState {
    buffer: VecDeque<u8>,
    base_seq: u64,
    next_seq: u64,
    cols: u16,
    rows: u16,
    running: bool,
    exit_code: Option<i32>,
    exit_signal: Option<i32>,
    subscribers: HashMap<u64, mpsc::Sender<PtyServerEvent>>,
}

struct TerminatePending {
    is_reaped: bool,
    tracked_pids: Vec<libc::pid_t>,
}

pub struct PtySession {
    session_id: String,
    pid: u32,
    slave_tty_nr: i32,
    pidfd: Option<OwnedFd>,
    reaped: Arc<Mutex<bool>>,
    terminated: AtomicBool,
    master_ctrl: Mutex<Option<Arc<AsyncFd<OwnedFd>>>>,
    input_tx: Mutex<Option<mpsc::Sender<Vec<u8>>>>,
    shutdown_tx: watch::Sender<bool>,
    #[cfg(test)]
    reader_done_rx: watch::Receiver<bool>,
    reader_handle: Mutex<Option<JoinHandle<()>>>,
    writer_handle: Mutex<Option<JoinHandle<()>>>,
    state: Arc<Mutex<PtyStreamState>>,
    next_sub_id: AtomicU64,
}

fn ensure_fd_ge_3(fd: OwnedFd) -> io::Result<OwnedFd> {
    if fd.as_raw_fd() >= 3 {
        return Ok(fd);
    }
    let duped = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 3) };
    if duped < 0 {
        return Err(io::Error::last_os_error());
    }
    drop(fd);
    Ok(unsafe { OwnedFd::from_raw_fd(duped) })
}

fn dup_cloexec(fd: &OwnedFd) -> io::Result<OwnedFd> {
    let duped = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
    if duped < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(duped) })
}

fn set_nonblocking(raw_fd: libc::c_int) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(raw_fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::fcntl(raw_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn encode_linux_tty_nr(rdev: libc::dev_t) -> i32 {
    let maj = libc::major(rdev);
    let min = libc::minor(rdev);
    ((min & 0xff) | ((maj & 0xfff) << 8) | ((min & !0xff) << 12)) as i32
}

fn fstat_tty_nr(raw_fd: libc::c_int) -> io::Result<i32> {
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::fstat(raw_fd, &mut st) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(encode_linux_tty_nr(st.st_rdev))
}

fn open_pty_pair(cols: u16, rows: u16) -> io::Result<(OwnedFd, OwnedFd, i32)> {
    let master_raw = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC) };
    if master_raw < 0 {
        return Err(io::Error::last_os_error());
    }
    let master = unsafe { OwnedFd::from_raw_fd(master_raw) };

    if unsafe { libc::grantpt(master.as_raw_fd()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::unlockpt(master.as_raw_fd()) } != 0 {
        return Err(io::Error::last_os_error());
    }

    let mut pty_num: libc::c_uint = 0;
    if unsafe { libc::ioctl(master.as_raw_fd(), libc::TIOCGPTN as _, &mut pty_num) } != 0 {
        return Err(io::Error::last_os_error());
    }
    let slave_path = format!("/dev/pts/{pty_num}");
    let slave_file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_NOCTTY | libc::O_CLOEXEC)
        .open(&slave_path)?;
    let slave = ensure_fd_ge_3(slave_file.into())?;
    let slave_tty_nr = fstat_tty_nr(slave.as_raw_fd())?;

    set_winsize_fd(master.as_raw_fd(), cols, rows)?;
    set_nonblocking(master.as_raw_fd())?;
    Ok((master, slave, slave_tty_nr))
}

fn set_winsize_fd(raw_fd: libc::c_int, cols: u16, rows: u16) -> io::Result<()> {
    let ws = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let rc = unsafe { libc::ioctl(raw_fd, libc::TIOCSWINSZ as _, &ws) };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn open_pidfd(pid: u32) -> Option<OwnedFd> {
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid as libc::pid_t, 0 as libc::c_uint) };
    if fd >= 0 {
        Some(unsafe { OwnedFd::from_raw_fd(fd as libc::c_int) })
    } else {
        None
    }
}

fn pidfd_send_signal(pidfd: &OwnedFd, sig: libc::c_int) -> bool {
    let rc = unsafe {
        libc::syscall(
            libc::SYS_pidfd_send_signal,
            pidfd.as_raw_fd(),
            sig,
            std::ptr::null::<libc::siginfo_t>(),
            0 as libc::c_uint,
        )
    };
    rc == 0
}

#[derive(Debug, Clone, Copy)]
struct ProcStat {
    pid: libc::pid_t,
    pgrp: libc::pid_t,
    sid: libc::pid_t,
    tty_nr: i32,
    is_zombie: bool,
}

fn read_proc_stat(pid: libc::pid_t) -> Option<ProcStat> {
    let raw = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let rparen = raw.rfind(')')?;
    let rest = raw.get(rparen + 1..)?.trim_start();
    let mut fields = rest.split_ascii_whitespace();
    let state = fields.next()?; // field 3: state
    let _ppid = fields.next()?; // field 4: ppid
    let pgrp: libc::pid_t = fields.next()?.parse().ok()?; // field 5: pgrp
    let sid: libc::pid_t = fields.next()?.parse().ok()?; // field 6: session
    let tty_nr: i32 = fields.next()?.parse().ok()?; // field 7: tty_nr
    Some(ProcStat {
        pid,
        pgrp,
        sid,
        tty_nr,
        is_zombie: matches!(state, "Z" | "X" | "x"),
    })
}

fn find_session_procs(target_sid: libc::pid_t, required_tty_nr: Option<i32>) -> Vec<ProcStat> {
    let self_pid = std::process::id() as libc::pid_t;
    let Ok(entries) = std::fs::read_dir("/proc") else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let Ok(name) = entry.file_name().into_string() else {
            continue;
        };
        let Ok(pid) = name.parse::<libc::pid_t>() else {
            continue;
        };
        if pid <= 1 || pid == self_pid {
            continue;
        }
        if let Some(stat) = read_proc_stat(pid) {
            if stat.is_zombie || stat.sid != target_sid {
                continue;
            }
            if let Some(tty_nr) = required_tty_nr {
                if stat.tty_nr != tty_nr {
                    continue;
                }
            }
            out.push(stat);
        }
    }
    out
}

fn safe_kill_pgrp(pgrp: libc::pid_t, sig: libc::c_int, daemon_pgid: libc::pid_t) {
    if pgrp > 1 && pgrp != daemon_pgid {
        unsafe {
            let _ = libc::kill(-pgrp, sig);
        }
    }
}

fn safe_kill_pid(pid: libc::pid_t, sig: libc::c_int, daemon_pid: libc::pid_t) {
    if pid > 1 && pid != daemon_pid {
        unsafe {
            let _ = libc::kill(pid, sig);
        }
    }
}

fn default_shell_command() -> (String, Vec<&'static str>) {
    if let Some(bash) = resolve_binary("bash") {
        (bash, vec!["-i"])
    } else if let Some(sh) = resolve_binary("sh") {
        (sh, vec!["-i"])
    } else {
        ("/bin/sh".to_string(), vec!["-i"])
    }
}

fn append_pty_chunk(state: &Mutex<PtyStreamState>, chunk: Vec<u8>) {
    let mut st = state.lock().unwrap();
    let start_seq = st.next_seq;
    if chunk.len() >= PTY_BUFFER_BYTES {
        let keep_from = chunk.len() - PTY_BUFFER_BYTES;
        st.buffer.clear();
        st.buffer.extend(&chunk[keep_from..]);
        st.base_seq = start_seq + keep_from as u64;
    } else {
        let total = st.buffer.len() + chunk.len();
        if total > PTY_BUFFER_BYTES {
            let drop_n = total - PTY_BUFFER_BYTES;
            st.buffer.drain(..drop_n);
            st.base_seq += drop_n as u64;
        }
        st.buffer.extend(&chunk);
    }
    st.next_seq = start_seq + chunk.len() as u64;
    let ev = PtyServerEvent::Output {
        start_seq,
        data: chunk,
    };
    st.subscribers
        .retain(|_, tx| tx.try_send(ev.clone()).is_ok());
}

impl PtySession {
    pub fn spawn(cwd: &str, cols: u16, rows: u16) -> Result<Arc<Self>, String> {
        let cols = clamp_cols(cols);
        let rows = clamp_rows(rows);
        let (master, slave, slave_tty_nr) =
            open_pty_pair(cols, rows).map_err(|e| format!("Failed to open Linux PTY: {e}"))?;
        // SAFETY: `master` is an `OwnedFd` whose file descriptor stays open for the lifetime of `AsyncFd`.
        let master_async = Arc::new(
            unsafe { AsyncFd::register(master) }
                .map_err(|e| format!("Failed to register PTY master fd: {e}"))?,
        );

        let (shell_bin, shell_args) = default_shell_command();
        let mut cmd = Command::new(&shell_bin);
        cmd.args(&shell_args)
            .current_dir(cwd)
            .env("PATH", enriched_path())
            .env("TERM", "xterm-256color")
            .env("COLORTERM", "truecolor")
            .env_remove("NO_COLOR")
            .env_remove("FORCE_COLOR")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());

        if std::env::var_os("SHELL")
            .filter(|v| !v.is_empty())
            .is_none()
        {
            cmd.env("SHELL", &shell_bin);
        }
        if std::env::var_os("LANG").filter(|v| !v.is_empty()).is_none() {
            cmd.env("LANG", "C.UTF-8");
        }

        let slave_raw = slave.as_raw_fd();
        unsafe {
            cmd.pre_exec(move || {
                let mut empty_set: libc::sigset_t = std::mem::zeroed();
                libc::sigemptyset(&mut empty_set);
                libc::sigprocmask(libc::SIG_SETMASK, &empty_set, std::ptr::null_mut());
                for sig in [
                    libc::SIGHUP,
                    libc::SIGTERM,
                    libc::SIGPIPE,
                    libc::SIGINT,
                    libc::SIGQUIT,
                    libc::SIGTSTP,
                    libc::SIGTTIN,
                    libc::SIGTTOU,
                    libc::SIGCHLD,
                ] {
                    libc::signal(sig, libc::SIG_DFL);
                }
                if libc::setsid() == -1 {
                    return Err(io::Error::last_os_error());
                }
                if libc::ioctl(slave_raw, libc::TIOCSCTTY as _, 0) == -1 {
                    return Err(io::Error::last_os_error());
                }
                if libc::dup2(slave_raw, 0) == -1
                    || libc::dup2(slave_raw, 1) == -1
                    || libc::dup2(slave_raw, 2) == -1
                {
                    return Err(io::Error::last_os_error());
                }
                if slave_raw > 2 {
                    libc::close(slave_raw);
                }
                Ok(())
            });
        }

        let mut child = cmd
            .spawn()
            .map_err(|e| format!("Failed to start interactive shell ({shell_bin}): {e}"))?;

        // Close slave fd in parent immediately so EOF/EIO propagates cleanly on child exit.
        drop(slave);

        let Some(pid) = child.id() else {
            let _ = child.start_kill();
            let _ = child.try_wait();
            return Err("Interactive shell exited immediately after spawn".to_string());
        };

        let pidfd = open_pidfd(pid);
        // SAFETY: `fd` is a freshly dup'd `OwnedFd` kept alive by the `AsyncFd` until dropped.
        let pidfd_wait = pidfd
            .as_ref()
            .and_then(|fd| dup_cloexec(fd).ok())
            .and_then(|fd| unsafe { AsyncFd::register_with_interest(fd, Interest::READABLE) }.ok());

        let state = Arc::new(Mutex::new(PtyStreamState {
            buffer: VecDeque::with_capacity(64 * 1024),
            base_seq: 0,
            next_seq: 0,
            cols,
            rows,
            running: true,
            exit_code: None,
            exit_signal: None,
            subscribers: HashMap::new(),
        }));

        let reaped = Arc::new(Mutex::new(false));
        let (input_tx, mut input_rx) = mpsc::channel::<Vec<u8>>(PTY_INPUT_QUEUE_CAPACITY);
        let (shutdown_tx, mut shutdown_write) = watch::channel(false);
        let mut shutdown_read = shutdown_tx.subscribe();
        let (reader_done_tx, reader_done_rx) = watch::channel(false);

        // Async writer task preserves strict byte ordering and cancels cleanly on terminate().
        let master_write = master_async.clone();
        let writer_handle = tokio::spawn(async move {
            loop {
                let bytes = tokio::select! {
                    biased;
                    res = shutdown_write.changed() => {
                        if res.is_err() || *shutdown_write.borrow() {
                            break;
                        }
                        continue;
                    }
                    msg = input_rx.recv() => match msg {
                        Some(b) => b,
                        None => break,
                    },
                };
                if bytes.is_empty() {
                    continue;
                }
                let mut offset = 0;
                while offset < bytes.len() {
                    let mut guard = tokio::select! {
                        biased;
                        res = shutdown_write.changed() => {
                            if res.is_err() || *shutdown_write.borrow() {
                                return;
                            }
                            continue;
                        }
                        res = master_write.writable() => match res {
                            Ok(g) => g,
                            Err(_) => return,
                        },
                    };
                    loop {
                        let n = unsafe {
                            libc::write(
                                master_write.as_raw_fd(),
                                bytes[offset..].as_ptr() as *const libc::c_void,
                                bytes.len() - offset,
                            )
                        };
                        if n > 0 {
                            offset += n as usize;
                            if offset >= bytes.len() {
                                break;
                            }
                        } else if n == 0 {
                            return;
                        } else {
                            let err = io::Error::last_os_error();
                            match err.kind() {
                                io::ErrorKind::WouldBlock => {
                                    guard.clear_ready();
                                    break;
                                }
                                io::ErrorKind::Interrupted => continue,
                                _ => return,
                            }
                        }
                    }
                }
            }
        });

        // Async reader task pumps non-blocking PTY master bytes into the sequenced ring buffer.
        let master_read = master_async.clone();
        let read_state = state.clone();
        let reader_handle = tokio::spawn(async move {
            let mut buf = [0u8; PTY_READ_CHUNK_BYTES];
            loop {
                if *shutdown_read.borrow() {
                    break;
                }
                let mut guard = tokio::select! {
                    biased;
                    res = shutdown_read.changed() => {
                        if res.is_err() || *shutdown_read.borrow() {
                            break;
                        }
                        continue;
                    }
                    res = master_read.readable() => match res {
                        Ok(g) => g,
                        Err(_) => break,
                    },
                };
                let mut eof = false;
                let mut drained = false;
                for _ in 0..4 {
                    let n = unsafe {
                        libc::read(
                            master_read.as_raw_fd(),
                            buf.as_mut_ptr() as *mut libc::c_void,
                            buf.len(),
                        )
                    };
                    if n > 0 {
                        append_pty_chunk(&read_state, buf[..n as usize].to_vec());
                        if *shutdown_read.borrow() {
                            eof = true;
                            break;
                        }
                    } else if n == 0 {
                        eof = true;
                        break;
                    } else {
                        let err = io::Error::last_os_error();
                        match err.kind() {
                            io::ErrorKind::WouldBlock => {
                                guard.clear_ready();
                                drained = true;
                                break;
                            }
                            io::ErrorKind::Interrupted => continue,
                            // Linux PTY master returns EIO once the last slave fd closes.
                            _ => {
                                eof = true;
                                break;
                            }
                        }
                    }
                }
                if eof {
                    break;
                }
                if !drained {
                    drop(guard);
                    tokio::task::yield_now().await;
                }
            }
            let _ = reader_done_tx.send(true);
        });

        let session = Arc::new(Self {
            session_id: rand_hex(8),
            pid,
            slave_tty_nr,
            pidfd,
            reaped: reaped.clone(),
            terminated: AtomicBool::new(false),
            master_ctrl: Mutex::new(Some(master_async)),
            input_tx: Mutex::new(Some(input_tx)),
            shutdown_tx,
            #[cfg(test)]
            reader_done_rx: reader_done_rx.clone(),
            reader_handle: Mutex::new(Some(reader_handle)),
            writer_handle: Mutex::new(Some(writer_handle)),
            state: state.clone(),
            next_sub_id: AtomicU64::new(1),
        });

        // Reap the child process, drain trailing PTY output, and broadcast the terminal exit event.
        let wait_session = Arc::downgrade(&session);
        let wait_state = state;
        let mut wait_reader_done = reader_done_rx;
        tokio::spawn(async move {
            let status = if let Some(pidfd_async) = pidfd_wait {
                // Wait for child exit without reaping yet so `reaped` is set BEFORE the PID can be reused.
                let _ = pidfd_async.readable().await;
                let _ = tokio::time::timeout(
                    PTY_EXIT_DRAIN_TIMEOUT,
                    wait_reader_done.wait_for(|done| *done),
                )
                .await;
                {
                    let mut guard = reaped.lock().unwrap();
                    *guard = true;
                }
                child.wait().await.ok()
            } else {
                let st = child.wait().await.ok();
                {
                    let mut guard = reaped.lock().unwrap();
                    *guard = true;
                }
                let _ = tokio::time::timeout(
                    PTY_EXIT_DRAIN_TIMEOUT,
                    wait_reader_done.wait_for(|done| *done),
                )
                .await;
                st
            };

            let exit_code = status.and_then(|s| s.code());
            let exit_signal = status.and_then(|s| s.signal());
            if let Some(sess) = wait_session.upgrade() {
                sess.input_tx.lock().unwrap().take();
            }
            let mut st = wait_state.lock().unwrap();
            let was_running = st.running;
            st.running = false;
            st.exit_code = exit_code;
            st.exit_signal = exit_signal;
            if was_running {
                let ev = PtyServerEvent::Exit {
                    exit_code,
                    signal: exit_signal,
                };
                st.subscribers
                    .retain(|_, tx| tx.try_send(ev.clone()).is_ok());
            }
        });

        Ok(session)
    }

    #[cfg(test)]
    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    #[cfg(test)]
    pub fn pid(&self) -> u32 {
        self.pid
    }

    #[cfg(test)]
    pub fn is_reader_finished(&self) -> bool {
        *self.reader_done_rx.borrow() || self.reader_done_rx.has_changed().is_err()
    }

    pub fn dimensions(&self) -> (u16, u16) {
        let st = self.state.lock().unwrap();
        (st.cols, st.rows)
    }

    pub fn attach(&self, client_session_id: Option<&str>, after_seq: u64) -> PtyAttachSnapshot {
        let sub_id = self.next_sub_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel(PTY_SUBSCRIBER_QUEUE_CAPACITY);

        let mut st = self.state.lock().unwrap();
        let session_mismatch = client_session_id
            .filter(|s| !s.is_empty())
            .is_some_and(|s| s != self.session_id);
        let out_of_range = after_seq < st.base_seq || after_seq > st.next_seq;
        let truncated = session_mismatch || out_of_range;
        let replay_from = if truncated { st.base_seq } else { after_seq };

        let start_idx = (replay_from - st.base_seq) as usize;
        let total_replay = st.buffer.len().saturating_sub(start_idx);
        let mut replay_chunks = Vec::new();
        if total_replay > 0 {
            let mut cursor = start_idx;
            let mut chunk_seq = replay_from;
            while cursor < st.buffer.len() {
                let end = (cursor + PTY_REPLAY_CHUNK_BYTES).min(st.buffer.len());
                let mut data = Vec::with_capacity(end - cursor);
                let (head, tail) = st.buffer.as_slices();
                if cursor < head.len() {
                    let h_end = end.min(head.len());
                    data.extend_from_slice(&head[cursor..h_end]);
                    if end > head.len() {
                        data.extend_from_slice(&tail[..(end - head.len())]);
                    }
                } else {
                    let t_start = cursor - head.len();
                    let t_end = end - head.len();
                    data.extend_from_slice(&tail[t_start..t_end]);
                }
                let len = data.len() as u64;
                replay_chunks.push((chunk_seq, data));
                chunk_seq += len;
                cursor = end;
            }
        }

        st.subscribers.insert(sub_id, tx);

        PtyAttachSnapshot {
            session_id: self.session_id.clone(),
            cols: st.cols,
            rows: st.rows,
            base_seq: st.base_seq,
            next_seq: st.next_seq,
            replay_from,
            truncated,
            running: st.running,
            exit_code: st.exit_code,
            exit_signal: st.exit_signal,
            replay_chunks,
            subscriber_id: sub_id,
            rx,
        }
    }

    pub fn detach(&self, subscriber_id: u64) {
        self.state
            .lock()
            .unwrap()
            .subscribers
            .remove(&subscriber_id);
    }

    pub fn write_input(&self, data: Vec<u8>) -> bool {
        if data.is_empty() {
            return true;
        }
        let guard = self.input_tx.lock().unwrap();
        match guard.as_ref() {
            Some(tx) => tx.try_send(data).is_ok(),
            None => false,
        }
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(u16, u16), String> {
        let cols = clamp_cols(cols);
        let rows = clamp_rows(rows);
        let fd_guard = self.master_ctrl.lock().unwrap();
        let Some(ref fd) = *fd_guard else {
            return Err("PTY session is closed".to_string());
        };
        set_winsize_fd(fd.as_raw_fd(), cols, rows)
            .map_err(|e| format!("Failed to resize PTY: {e}"))?;
        let mut st = self.state.lock().unwrap();
        st.cols = cols;
        st.rows = rows;
        Ok((cols, rows))
    }

    /// Phase 1 of shutdown: idempotently signal the PTY session (`SIGHUP` + `SIGCONT`),
    /// stop reader/writer tasks, close the master fd, and detach subscribers.
    fn begin_terminate(&self) -> Option<TerminatePending> {
        if self.terminated.swap(true, Ordering::AcqRel) {
            return None;
        }
        self.input_tx.lock().unwrap().take();

        let daemon_pid = std::process::id() as libc::pid_t;
        let daemon_pgid = unsafe { libc::getpgrp() };
        let shell_pid = self.pid as libc::pid_t;

        let mut master_guard = self.master_ctrl.lock().unwrap();
        let fg_pgrp = master_guard.as_ref().and_then(|m| {
            let p = unsafe { libc::tcgetpgrp(m.as_raw_fd()) };
            (p > 1 && p != daemon_pgid).then_some(p)
        });

        let (is_reaped, tracked_pids) = {
            let reaped_guard = self.reaped.lock().unwrap();
            let is_reaped = *reaped_guard;
            let required_tty = is_reaped.then_some(self.slave_tty_nr);
            let session_procs = find_session_procs(shell_pid, required_tty);

            let mut pgrps = HashSet::new();
            if let Some(fg) = fg_pgrp {
                pgrps.insert(fg);
            }
            if !is_reaped {
                pgrps.insert(shell_pid);
            }
            let mut pids = Vec::with_capacity(session_procs.len());
            for proc_stat in &session_procs {
                if proc_stat.pgrp > 1 && proc_stat.pgrp != daemon_pgid {
                    pgrps.insert(proc_stat.pgrp);
                }
                pids.push(proc_stat.pid);
            }

            for sig in [libc::SIGHUP, libc::SIGCONT] {
                for &pgrp in &pgrps {
                    safe_kill_pgrp(pgrp, sig, daemon_pgid);
                }
                for &p in &pids {
                    if p != shell_pid {
                        safe_kill_pid(p, sig, daemon_pid);
                    }
                }
                if !is_reaped {
                    if let Some(ref pidfd) = self.pidfd {
                        pidfd_send_signal(pidfd, sig);
                    } else {
                        safe_kill_pid(shell_pid, sig, daemon_pid);
                    }
                }
            }
            (is_reaped, pids)
        };

        // Cancel reader & writer tasks and drop the master fd so the kernel closes the PTY.
        let _ = self.shutdown_tx.send(true);
        if let Some(h) = self.reader_handle.lock().unwrap().take() {
            h.abort();
        }
        if let Some(h) = self.writer_handle.lock().unwrap().take() {
            h.abort();
        }
        master_guard.take();
        drop(master_guard);

        let mut st = self.state.lock().unwrap();
        st.running = false;
        st.subscribers.clear();
        drop(st);

        if is_reaped && tracked_pids.is_empty() && fg_pgrp.is_none() {
            None
        } else {
            Some(TerminatePending {
                is_reaped,
                tracked_pids,
            })
        }
    }

    fn has_living_session_procs(&self, pending: &TerminatePending) -> bool {
        let shell_pid = self.pid as libc::pid_t;
        let required_tty = pending.is_reaped.then_some(self.slave_tty_nr);

        if !pending.is_reaped {
            if let Some(stat) = read_proc_stat(shell_pid) {
                if !stat.is_zombie && stat.sid == shell_pid {
                    return true;
                }
            }
        }
        for &p in &pending.tracked_pids {
            if let Some(stat) = read_proc_stat(p) {
                if !stat.is_zombie
                    && stat.sid == shell_pid
                    && required_tty.is_none_or(|t| stat.tty_nr == t)
                {
                    return true;
                }
            }
        }
        !find_session_procs(shell_pid, required_tty).is_empty()
    }

    fn escalate_sigkill(&self, pending: &TerminatePending) {
        let daemon_pid = std::process::id() as libc::pid_t;
        let daemon_pgid = unsafe { libc::getpgrp() };
        let shell_pid = self.pid as libc::pid_t;
        let required_tty = pending.is_reaped.then_some(self.slave_tty_nr);

        let survivors = find_session_procs(shell_pid, required_tty);
        let mut pgrps = HashSet::new();
        let mut pids = HashSet::new();
        for proc_stat in survivors {
            if proc_stat.pgrp > 1 && proc_stat.pgrp != daemon_pgid {
                pgrps.insert(proc_stat.pgrp);
            }
            pids.insert(proc_stat.pid);
        }
        for &p in &pending.tracked_pids {
            if let Some(stat) = read_proc_stat(p) {
                if !stat.is_zombie
                    && stat.sid == shell_pid
                    && required_tty.is_none_or(|t| stat.tty_nr == t)
                {
                    if stat.pgrp > 1 && stat.pgrp != daemon_pgid {
                        pgrps.insert(stat.pgrp);
                    }
                    pids.insert(stat.pid);
                }
            }
        }

        for pgrp in pgrps {
            safe_kill_pgrp(pgrp, libc::SIGKILL, daemon_pgid);
        }
        for p in pids {
            if p != shell_pid {
                safe_kill_pid(p, libc::SIGKILL, daemon_pid);
            }
        }

        // For the shell leader itself, use pidfd when available (immune to PID reuse);
        // otherwise gate on `!reaped` under the reaped lock.
        if let Some(ref pidfd) = self.pidfd {
            pidfd_send_signal(pidfd, libc::SIGKILL);
        } else {
            let reaped_guard = self.reaped.lock().unwrap();
            if !*reaped_guard {
                safe_kill_pgrp(shell_pid, libc::SIGKILL, daemon_pgid);
                safe_kill_pid(shell_pid, libc::SIGKILL, daemon_pid);
            }
        }
    }

    pub fn terminate(&self) {
        let Some(pending) = self.begin_terminate() else {
            return;
        };
        let deadline = Instant::now() + PTY_TERMINATE_GRACE;
        while Instant::now() < deadline {
            if !self.has_living_session_procs(&pending) {
                return;
            }
            std::thread::sleep(PTY_TERMINATE_POLL);
        }
        if self.has_living_session_procs(&pending) {
            self.escalate_sigkill(&pending);
        }
    }
}

impl Drop for PtySession {
    fn drop(&mut self) {
        self.terminate();
    }
}

#[derive(Clone, Default)]
pub struct PtyManager {
    sessions: Arc<Mutex<HashMap<String, Arc<PtySession>>>>,
    shutting_down: Arc<AtomicBool>,
}

impl PtyManager {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn get_or_spawn(
        &self,
        tab_id: &str,
        cwd: &str,
        cols: u16,
        rows: u16,
    ) -> Result<Arc<PtySession>, String> {
        {
            let map = self.sessions.lock().unwrap();
            if self.shutting_down.load(Ordering::Acquire) {
                return Err("Daemon is shutting down".to_string());
            }
            if let Some(existing) = map.get(tab_id).cloned() {
                return Ok(existing);
            }
        }
        let spawned = PtySession::spawn(cwd, cols, rows)?;
        let displaced = {
            let mut map = self.sessions.lock().unwrap();
            if self.shutting_down.load(Ordering::Acquire) {
                drop(map);
                spawned.terminate();
                return Err("Daemon is shutting down".to_string());
            }
            if let Some(existing) = map.get(tab_id).cloned() {
                drop(map);
                spawned.terminate();
                return Ok(existing);
            }
            map.insert(tab_id.to_string(), spawned.clone())
        };
        if let Some(prev) = displaced {
            prev.terminate();
        }
        Ok(spawned)
    }

    pub fn restart(
        &self,
        tab_id: &str,
        cwd: &str,
        cols: u16,
        rows: u16,
    ) -> Result<Arc<PtySession>, String> {
        let old = {
            let mut map = self.sessions.lock().unwrap();
            if self.shutting_down.load(Ordering::Acquire) {
                return Err("Daemon is shutting down".to_string());
            }
            map.remove(tab_id)
        };
        if let Some(prev) = old {
            prev.terminate();
        }
        let spawned = PtySession::spawn(cwd, cols, rows)?;
        let displaced = {
            let mut map = self.sessions.lock().unwrap();
            if self.shutting_down.load(Ordering::Acquire) {
                drop(map);
                spawned.terminate();
                return Err("Daemon is shutting down".to_string());
            }
            map.insert(tab_id.to_string(), spawned.clone())
        };
        if let Some(prev) = displaced {
            prev.terminate();
        }
        Ok(spawned)
    }

    pub fn kill_and_remove(&self, tab_id: &str) {
        if let Some(session) = self.sessions.lock().unwrap().remove(tab_id) {
            session.terminate();
        }
    }

    pub fn shutdown_all(&self) {
        let all: Vec<Arc<PtySession>> = {
            let mut map = self.sessions.lock().unwrap();
            self.shutting_down.store(true, Ordering::Release);
            map.drain().map(|(_, s)| s).collect()
        };
        let mut pending: Vec<(Arc<PtySession>, TerminatePending)> = Vec::new();
        for session in all {
            if let Some(p) = session.begin_terminate() {
                pending.push((session, p));
            }
        }
        if pending.is_empty() {
            return;
        }
        let deadline = Instant::now() + PTY_TERMINATE_GRACE;
        while Instant::now() < deadline {
            pending.retain(|(sess, p)| sess.has_living_session_procs(p));
            if pending.is_empty() {
                return;
            }
            std::thread::sleep(PTY_TERMINATE_POLL);
        }
        for (sess, p) in pending {
            if sess.has_living_session_procs(&p) {
                sess.escalate_sigkill(&p);
            }
        }
    }
}

pub fn encode_output_frame(start_seq: u64, data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(8 + data.len());
    out.extend_from_slice(&start_seq.to_be_bytes());
    out.extend_from_slice(data);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pty_session_echo_resize_and_reconnect_replay() {
        let tmp = std::env::temp_dir();
        let cwd = tmp.to_string_lossy().to_string();
        let session = PtySession::spawn(&cwd, 80, 24).expect("spawn PTY");

        let mut snap = session.attach(None, 0);
        assert!(snap.running);
        assert_eq!(snap.cols, 80);
        assert_eq!(snap.rows, 24);
        assert!(!snap.truncated);

        session.write_input(b"echo PTY_HELLO_141\n".to_vec());

        let mut collected = Vec::new();
        let mut last_seq = snap.next_seq;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while tokio::time::Instant::now() < deadline {
            match tokio::time::timeout(Duration::from_millis(200), snap.rx.recv()).await {
                Ok(Some(PtyServerEvent::Output { start_seq, data })) => {
                    assert_eq!(start_seq, last_seq);
                    last_seq += data.len() as u64;
                    collected.extend_from_slice(&data);
                    if String::from_utf8_lossy(&collected).contains("PTY_HELLO_141") {
                        break;
                    }
                }
                Ok(Some(PtyServerEvent::Exit { .. })) => break,
                _ => {}
            }
        }
        assert!(
            String::from_utf8_lossy(&collected).contains("PTY_HELLO_141"),
            "expected echoed marker in PTY output, got: {}",
            String::from_utf8_lossy(&collected)
        );
        session.detach(snap.subscriber_id);

        // Resize PTY and verify stty reports new dimensions.
        let (c, r) = session.resize(100, 32).expect("resize PTY");
        assert_eq!((c, r), (100, 32));
        assert_eq!(session.dimensions(), (100, 32));
        session.write_input(b"stty size\n".to_vec());

        // Reconnect from last_seq: should NOT be truncated and should get the stty size output.
        let mut snap2 = session.attach(Some(session.session_id()), last_seq);
        assert!(!snap2.truncated);
        assert_eq!(snap2.replay_from, last_seq);
        let mut after_bytes = Vec::new();
        for (_, chunk) in snap2.replay_chunks {
            after_bytes.extend_from_slice(&chunk);
        }
        let deadline2 = tokio::time::Instant::now() + Duration::from_secs(5);
        while tokio::time::Instant::now() < deadline2
            && !String::from_utf8_lossy(&after_bytes).contains("32 100")
        {
            if let Ok(Some(PtyServerEvent::Output { data, .. })) =
                tokio::time::timeout(Duration::from_millis(200), snap2.rx.recv()).await
            {
                after_bytes.extend_from_slice(&data);
            }
        }
        assert!(
            String::from_utf8_lossy(&after_bytes).contains("32 100"),
            "expected stty size '32 100', got: {}",
            String::from_utf8_lossy(&after_bytes)
        );
        session.detach(snap2.subscriber_id);

        // Cold reattach from seq=0 with no session_id replays from base_seq (0) and contains both outputs.
        let snap_cold = session.attach(None, 0);
        assert!(!snap_cold.truncated);
        let mut cold_bytes = Vec::new();
        for (_, chunk) in snap_cold.replay_chunks {
            cold_bytes.extend_from_slice(&chunk);
        }
        let cold_str = String::from_utf8_lossy(&cold_bytes);
        assert!(cold_str.contains("PTY_HELLO_141"));
        assert!(cold_str.contains("32 100"));
        session.detach(snap_cold.subscriber_id);

        session.terminate();
    }

    #[tokio::test]
    async fn terminate_kills_foreground_and_background_jobs_and_exits_reader() {
        let tmp = std::env::temp_dir();
        let cwd = tmp.to_string_lossy().to_string();
        let session = PtySession::spawn(&cwd, 80, 24).expect("spawn PTY");
        let mut snap = session.attach(None, 0);

        // Start a background sleep (ignoring SIGHUP to test SIGKILL escalation) and a foreground sleep.
        session.write_input(
            b"(trap '' HUP; sleep 60) & BGPID=$!; echo BG_PID=$BGPID; sleep 60\n".to_vec(),
        );

        let mut out = String::new();
        let mut bg_pid: Option<libc::pid_t> = None;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while tokio::time::Instant::now() < deadline {
            if let Ok(Some(PtyServerEvent::Output { data, .. })) =
                tokio::time::timeout(Duration::from_millis(200), snap.rx.recv()).await
            {
                out.push_str(&String::from_utf8_lossy(&data));
                for line in out.lines() {
                    if let Some(rest) = line.trim().strip_prefix("BG_PID=") {
                        if let Ok(p) = rest.trim().parse::<libc::pid_t>() {
                            bg_pid = Some(p);
                        }
                    }
                }
                if bg_pid.is_some() {
                    break;
                }
            }
        }
        let bg_pid = bg_pid.unwrap_or_else(|| panic!("BG_PID never appeared in output: {out:?}"));
        // Give the foreground `sleep 60` a moment to spawn in its own job-control pgrp.
        tokio::time::sleep(Duration::from_millis(150)).await;
        let session_procs_before = find_session_procs(session.pid() as libc::pid_t, None);
        assert!(
            session_procs_before.len() >= 2,
            "expected bash + job children in session, got: {session_procs_before:?}"
        );

        session.terminate();
        // Calling terminate() a second time must be an idempotent no-op.
        session.terminate();

        // Allow Tokio to poll the cancelled reader/writer tasks.
        let reader_deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        while !session.is_reader_finished() && tokio::time::Instant::now() < reader_deadline {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            session.is_reader_finished(),
            "PTY reader task did not exit promptly after terminate()"
        );

        // Verify background SIGHUP-ignoring job and all foreground jobs in the session are dead.
        let dead_deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let bg_alive = read_proc_stat(bg_pid)
                .is_some_and(|st| !st.is_zombie && st.sid == session.pid() as libc::pid_t);
            let remaining = find_session_procs(session.pid() as libc::pid_t, None);
            if !bg_alive && remaining.is_empty() {
                break;
            }
            assert!(
                tokio::time::Instant::now() < dead_deadline,
                "Orphaned PTY jobs survived terminate(): bg_alive={bg_alive} remaining={remaining:?}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    #[tokio::test]
    async fn trailing_output_is_delivered_before_exit_event() {
        let tmp = std::env::temp_dir();
        let cwd = tmp.to_string_lossy().to_string();
        let session = PtySession::spawn(&cwd, 80, 24).expect("spawn PTY");
        let mut snap = session.attach(None, 0);

        session.write_input(b"echo TRAILING_BYE_141; exit 0\n".to_vec());

        let mut collected = String::new();
        let mut saw_exit_code = None;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while tokio::time::Instant::now() < deadline {
            match tokio::time::timeout(Duration::from_millis(200), snap.rx.recv()).await {
                Ok(Some(PtyServerEvent::Output { data, .. })) => {
                    collected.push_str(&String::from_utf8_lossy(&data));
                }
                Ok(Some(PtyServerEvent::Exit { exit_code, .. })) => {
                    saw_exit_code = Some(exit_code);
                    break;
                }
                Ok(None) => break,
                Err(_) => {}
            }
        }
        assert_eq!(saw_exit_code, Some(Some(0)), "expected Exit(0)");
        assert!(
            collected.contains("TRAILING_BYE_141"),
            "trailing output must arrive BEFORE Exit event; got: {collected:?}"
        );
    }

    #[tokio::test]
    async fn pty_manager_rejects_spawns_after_shutdown_all() {
        let mgr = PtyManager::new();
        let cwd = std::env::temp_dir().to_string_lossy().to_string();
        let _sess = mgr
            .get_or_spawn("tab-shut", &cwd, 80, 24)
            .expect("initial spawn");
        mgr.shutdown_all();
        assert!(mgr.get_or_spawn("tab-shut", &cwd, 80, 24).is_err());
        assert!(mgr.restart("tab-shut", &cwd, 80, 24).is_err());
    }

    #[tokio::test]
    async fn lagging_subscriber_is_disconnected_when_bounded_queue_fills() {
        let state = Mutex::new(PtyStreamState {
            buffer: VecDeque::new(),
            base_seq: 0,
            next_seq: 0,
            cols: 80,
            rows: 24,
            running: true,
            exit_code: None,
            exit_signal: None,
            subscribers: HashMap::new(),
        });
        let (tx, mut rx) = mpsc::channel(PTY_SUBSCRIBER_QUEUE_CAPACITY);
        state.lock().unwrap().subscribers.insert(1, tx);

        for _ in 0..=PTY_SUBSCRIBER_QUEUE_CAPACITY {
            append_pty_chunk(&state, vec![b'x'; 64]);
        }

        // The stalled subscriber should have been evicted once the bounded channel filled.
        assert!(state.lock().unwrap().subscribers.is_empty());
        // Draining the buffered items reaches None (channel closed).
        let mut count = 0;
        while rx.try_recv().is_ok() {
            count += 1;
        }
        assert_eq!(count, PTY_SUBSCRIBER_QUEUE_CAPACITY);
        assert!(matches!(
            rx.try_recv(),
            Err(mpsc::error::TryRecvError::Disconnected)
        ));
    }
}
