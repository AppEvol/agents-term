use std::collections::{HashMap, VecDeque};
use std::fs::OpenOptions;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::ExitStatusExt;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::process::Command;
use tokio::sync::mpsc;

use crate::config::{enriched_path, resolve_binary};
use crate::shell::signal_process_group;
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
    pub rx: mpsc::UnboundedReceiver<PtyServerEvent>,
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
    subscribers: HashMap<u64, mpsc::UnboundedSender<PtyServerEvent>>,
}

pub struct PtySession {
    session_id: String,
    pid: u32,
    master_ctrl: Mutex<Option<OwnedFd>>,
    input_tx: Mutex<Option<mpsc::UnboundedSender<Vec<u8>>>>,
    state: Arc<Mutex<PtyStreamState>>,
    next_sub_id: AtomicU64,
}

fn open_pty_pair(cols: u16, rows: u16) -> io::Result<(OwnedFd, OwnedFd)> {
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
    let slave: OwnedFd = slave_file.into();

    set_winsize_fd(master.as_raw_fd(), cols, rows)?;
    Ok((master, slave))
}

fn dup_cloexec(fd: &OwnedFd) -> io::Result<OwnedFd> {
    let duped = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
    if duped < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(duped) })
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

fn default_shell_command() -> (String, Vec<&'static str>) {
    if let Some(bash) = resolve_binary("bash") {
        (bash, vec!["-i"])
    } else if let Some(sh) = resolve_binary("sh") {
        (sh, vec!["-i"])
    } else {
        ("/bin/sh".to_string(), vec!["-i"])
    }
}

impl PtySession {
    pub fn spawn(cwd: &str, cols: u16, rows: u16) -> Result<Arc<Self>, String> {
        let cols = clamp_cols(cols);
        let rows = clamp_rows(rows);
        let (master, slave) =
            open_pty_pair(cols, rows).map_err(|e| format!("Failed to open Linux PTY: {e}"))?;
        let master_read =
            dup_cloexec(&master).map_err(|e| format!("Failed to duplicate PTY read fd: {e}"))?;
        let master_write =
            dup_cloexec(&master).map_err(|e| format!("Failed to duplicate PTY write fd: {e}"))?;

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

        if std::env::var_os("LANG").is_none() {
            cmd.env("LANG", "C.UTF-8");
        }

        let slave_raw = slave.as_raw_fd();
        unsafe {
            cmd.pre_exec(move || {
                for sig in [
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

        let pid = child
            .id()
            .ok_or_else(|| "Interactive shell exited immediately after spawn".to_string())?;

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

        let (input_tx, mut input_rx) = mpsc::unbounded_channel::<Vec<u8>>();

        // Dedicated blocking writer thread preserves strict byte ordering without stalling Tokio.
        std::thread::Builder::new()
            .name(format!("pty-write-{pid}"))
            .spawn(move || {
                let mut writer = std::fs::File::from(master_write);
                while let Some(bytes) = input_rx.blocking_recv() {
                    if bytes.is_empty() {
                        continue;
                    }
                    if writer.write_all(&bytes).is_err() {
                        break;
                    }
                }
            })
            .map_err(|e| format!("Failed to spawn PTY writer thread: {e}"))?;

        let session = Arc::new(Self {
            session_id: rand_hex(8),
            pid,
            master_ctrl: Mutex::new(Some(master)),
            input_tx: Mutex::new(Some(input_tx)),
            state: state.clone(),
            next_sub_id: AtomicU64::new(1),
        });

        // Dedicated blocking reader thread pumps raw PTY master bytes into the sequenced ring buffer.
        let read_state = state.clone();
        std::thread::Builder::new()
            .name(format!("pty-read-{pid}"))
            .spawn(move || {
                let mut reader = std::fs::File::from(master_read);
                let mut buf = [0u8; 16_384];
                loop {
                    match reader.read(&mut buf) {
                        Ok(0) => break,
                        Ok(n) => {
                            let chunk = buf[..n].to_vec();
                            let mut st = read_state.lock().unwrap();
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
                            st.subscribers.retain(|_, tx| tx.send(ev.clone()).is_ok());
                        }
                        Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
                        // Linux PTY master returns EIO once the last slave fd closes.
                        Err(_) => break,
                    }
                }
            })
            .map_err(|e| format!("Failed to spawn PTY reader thread: {e}"))?;

        // Reap the child process and broadcast the terminal exit event.
        let wait_session = Arc::downgrade(&session);
        let wait_state = state.clone();
        tokio::spawn(async move {
            let status = child.wait().await.ok();
            let exit_code = status.and_then(|s| s.code());
            let exit_signal = status.and_then(|s| s.signal());
            if let Some(sess) = wait_session.upgrade() {
                sess.input_tx.lock().unwrap().take();
            }
            let mut st = wait_state.lock().unwrap();
            st.running = false;
            st.exit_code = exit_code;
            st.exit_signal = exit_signal;
            let ev = PtyServerEvent::Exit {
                exit_code,
                signal: exit_signal,
            };
            st.subscribers.retain(|_, tx| tx.send(ev.clone()).is_ok());
        });

        Ok(session)
    }

    #[cfg(test)]
    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    pub fn attach(&self, client_session_id: Option<&str>, after_seq: u64) -> PtyAttachSnapshot {
        let sub_id = self.next_sub_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::unbounded_channel();

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
            Some(tx) => tx.send(data).is_ok(),
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

    pub fn terminate(&self) {
        self.input_tx.lock().unwrap().take();
        signal_process_group(self.pid, libc::SIGHUP);
        signal_process_group(self.pid, libc::SIGKILL);
        self.master_ctrl.lock().unwrap().take();
        let mut st = self.state.lock().unwrap();
        st.running = false;
        st.subscribers.clear();
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
        if let Some(existing) = self.sessions.lock().unwrap().get(tab_id).cloned() {
            return Ok(existing);
        }
        let spawned = PtySession::spawn(cwd, cols, rows)?;
        let mut map = self.sessions.lock().unwrap();
        if let Some(existing) = map.get(tab_id).cloned() {
            drop(map);
            spawned.terminate();
            return Ok(existing);
        }
        map.insert(tab_id.to_string(), spawned.clone());
        Ok(spawned)
    }

    pub fn restart(
        &self,
        tab_id: &str,
        cwd: &str,
        cols: u16,
        rows: u16,
    ) -> Result<Arc<PtySession>, String> {
        let old = self.sessions.lock().unwrap().remove(tab_id);
        if let Some(prev) = old {
            prev.terminate();
        }
        let spawned = PtySession::spawn(cwd, cols, rows)?;
        self.sessions
            .lock()
            .unwrap()
            .insert(tab_id.to_string(), spawned.clone());
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
            map.drain().map(|(_, s)| s).collect()
        };
        for session in all {
            session.terminate();
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
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        while tokio::time::Instant::now() < deadline {
            match tokio::time::timeout(std::time::Duration::from_millis(200), snap.rx.recv()).await
            {
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
        session.write_input(b"stty size\n".to_vec());

        // Reconnect from last_seq: should NOT be truncated and should get the stty size output.
        let mut snap2 = session.attach(Some(session.session_id()), last_seq);
        assert!(!snap2.truncated);
        assert_eq!(snap2.replay_from, last_seq);
        let mut after_bytes = Vec::new();
        for (_, chunk) in snap2.replay_chunks {
            after_bytes.extend_from_slice(&chunk);
        }
        let deadline2 = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        while tokio::time::Instant::now() < deadline2
            && !String::from_utf8_lossy(&after_bytes).contains("32 100")
        {
            if let Ok(Some(PtyServerEvent::Output { data, .. })) =
                tokio::time::timeout(std::time::Duration::from_millis(200), snap2.rx.recv()).await
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
}
