use super::{
    prune_closed_tabs_locked, tab_with_disk_state, StoreInner, SAVE_DEBOUNCE,
    WORKSPACE_SCHEMA_VERSION,
};
use crate::config::{ensure_dir_0700, tab_dir_under};
use crate::log::log_error;
use crate::util::now_ms;
use serde_json::{json, Value};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Mutex};
use std::time::{Duration, Instant};

pub(super) enum SaveRequest {
    Scheduled,
    Flush(mpsc::Sender<io::Result<()>>),
}

pub(super) fn run_writer(requests: mpsc::Receiver<SaveRequest>, save: impl Fn() -> io::Result<()>) {
    let mut deadline: Option<Instant> = None;
    let mut retry_delay = SAVE_DEBOUNCE;
    loop {
        let request = match deadline {
            None => match requests.recv() {
                Ok(request) => Some(request),
                Err(_) => return,
            },
            Some(at) if Instant::now() >= at => None,
            Some(at) => match requests.recv_timeout(at.saturating_duration_since(Instant::now())) {
                Ok(request) => Some(request),
                Err(mpsc::RecvTimeoutError::Timeout) => None,
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    if let Err(err) = save() {
                        log_error(&format!("Final store save failed: {err}"));
                    }
                    return;
                }
            },
        };
        if matches!(request, Some(SaveRequest::Scheduled)) {
            deadline.get_or_insert_with(|| Instant::now() + SAVE_DEBOUNCE);
            continue;
        }
        let result = save();
        if let Err(err) = &result {
            deadline = Some(Instant::now() + retry_delay);
            retry_delay = (retry_delay * 2).min(Duration::from_secs(30));
            log_error(&format!("Store save failed, will retry: {err}"));
        } else {
            deadline = None;
            retry_delay = SAVE_DEBOUNCE;
        }
        if let Some(SaveRequest::Flush(reply)) = request {
            let _ = reply.send(result);
        }
    }
}

pub(super) struct StoreFiles {
    home: PathBuf,
    pub(super) root: PathBuf,
}

impl StoreFiles {
    pub(super) fn new(home: PathBuf) -> Self {
        Self {
            root: home.join("agents_tabs"),
            home,
        }
    }

    pub(super) fn index_path(&self) -> PathBuf {
        self.root.join("tabs.jsonl")
    }

    pub(super) fn workspace_path(&self) -> PathBuf {
        self.root.join("workspace.json")
    }

    pub(super) fn messages_path(&self, id: &str) -> Option<PathBuf> {
        tab_dir_under(&self.root, id).map(|dir| dir.join("messages.json"))
    }

    pub(super) fn save(
        &self,
        state: &Mutex<StoreInner>,
        write: &impl Fn(&Path, &[u8], u32) -> io::Result<()>,
    ) -> io::Result<()> {
        let (index, messages, deletions) = {
            let mut inner = state.lock().unwrap();
            prune_closed_tabs_locked(&mut inner, now_ms());
            let index = inner.index_revision.map(|revision| {
                (
                    revision,
                    inner.active_tab_id.clone(),
                    inner.tabs.clone(),
                    inner.closed_tabs.clone(),
                )
            });
            let messages: Vec<_> = inner
                .dirty_message_tabs
                .iter()
                .map(|(id, revision)| (id.clone(), *revision, inner.messages_by_tab[id].clone()))
                .collect();
            (index, messages, inner.deleted_tab_dirs.clone())
        };
        if index.is_none() && messages.is_empty() && deletions.is_empty() {
            return Ok(());
        }
        ensure_dir_0700(&self.home)?;
        ensure_dir_0700(&self.root)?;
        let write_file = |path: &Path, bytes: &[u8]| {
            write(path, bytes, 0o600)
                .map_err(|e| io::Error::new(e.kind(), format!("{}: {e}", path.display())))
        };
        let write_json = |path: &Path, value: &Value| {
            let mut bytes = serde_json::to_vec_pretty(value)?;
            bytes.push(b'\n');
            write_file(path, &bytes)
        };
        let mut first_error = None;
        for (id, revision, history) in messages {
            let Some(dir) = tab_dir_under(&self.root, &id) else {
                first_error.get_or_insert_with(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "unsafe persisted tab ID")
                });
                continue;
            };
            match ensure_dir_0700(&dir)
                .and_then(|()| write_json(&dir.join("messages.json"), &Value::Array(history)))
            {
                Ok(()) => {
                    let mut inner = state.lock().unwrap();
                    if inner.dirty_message_tabs.get(&id) == Some(&revision) {
                        inner.dirty_message_tabs.remove(&id);
                    }
                }
                Err(err) => {
                    first_error.get_or_insert(err);
                }
            }
        }

        if let Some((revision, active, tabs, closed)) = index {
            write_json(
                &self.workspace_path(),
                &json!({"schemaVersion": WORKSPACE_SCHEMA_VERSION, "activeTabId": active}),
            )?;
            let mut lines = Vec::new();
            for (group, status) in [(tabs, "open"), (closed, "closed")] {
                for tab in group {
                    serde_json::to_writer(&mut lines, &tab_with_disk_state(&tab, status))?;
                    lines.push(b'\n');
                }
            }
            write_file(&self.index_path(), &lines)?;
            let mut inner = state.lock().unwrap();
            if inner.index_revision == Some(revision) {
                inner.index_revision = None;
            }
        }
        for id in deletions {
            // Never delete a directory still referenced by the on-disk index.
            if let Some(dir) = tab_dir_under(&self.root, &id) {
                if let Err(err) = fs::remove_dir_all(dir) {
                    if err.kind() != io::ErrorKind::NotFound {
                        first_error.get_or_insert(err);
                        continue;
                    }
                }
            }
            state.lock().unwrap().deleted_tab_dirs.remove(&id);
        }
        first_error.map_or(Ok(()), Err)
    }
}
