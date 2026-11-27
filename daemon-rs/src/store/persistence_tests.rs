use super::*;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

const TIMEOUT: Duration = Duration::from_secs(10);

struct TestHome(PathBuf);

impl TestHome {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!("agentsterm-save-test-{}", rand_hex(12))))
    }

    fn files(&self) -> StoreFiles {
        StoreFiles::new(self.0.clone())
    }
}

impl Drop for TestHome {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn create_tab(store: &StateStore) -> String {
    store
        .create_tab("shell", None, None, None, None, None)
        .unwrap()["id"]
        .as_str()
        .unwrap()
        .to_owned()
}

#[test]
fn failed_message_and_partial_index_retry_without_another_mutation() {
    let home = TestHome::new();
    let fail_writes = Arc::new(AtomicBool::new(true));
    let failing = fail_writes.clone();
    let (saved, retried) = mpsc::channel();
    let store = StateStore::open(home.files(), move |path, bytes, mode| {
        let target = path.ends_with("messages.json") || path.ends_with("tabs.jsonl");
        if target && failing.load(Ordering::SeqCst) {
            return Err(io::Error::other("injected write failure"));
        }
        write_atomic_file(path, bytes, mode)?;
        if target {
            saved.send(path.to_owned()).unwrap();
        }
        Ok(())
    });
    let id = create_tab(&store);
    let message = json!({"id":"one","text":"first"});
    store.add_message(&id, message.clone());
    assert!(store.save_now().is_err());
    assert!(home.files().workspace_path().is_file());
    assert!(!home.files().index_path().exists());
    fail_writes.store(false, Ordering::SeqCst);

    let paths: HashSet<_> = (0..2)
        .map(|_| retried.recv_timeout(TIMEOUT).unwrap())
        .collect();
    assert!(paths.contains(&home.files().messages_path(&id).unwrap()));
    assert!(paths.contains(&home.files().index_path()));
    let reopened = StateStore::open(home.files(), write_atomic_file);
    assert_eq!(reopened.get_messages(&id), [message]);
}

#[test]
fn mutation_during_snapshot_write_survives_following_flush() {
    let home = TestHome::new();
    let pause = Arc::new(AtomicBool::new(false));
    let pause_writer = pause.clone();
    let (started, writing) = mpsc::channel();
    let (release, resume) = mpsc::channel();
    let store = StateStore::open(home.files(), move |path, bytes, mode| {
        if path.ends_with("messages.json") && pause_writer.swap(false, Ordering::SeqCst) {
            started.send(()).unwrap();
            resume.recv_timeout(TIMEOUT).unwrap();
        }
        write_atomic_file(path, bytes, mode)
    });
    let id = create_tab(&store);
    store.save_now().unwrap();
    pause.store(true, Ordering::SeqCst);
    let first = json!({"id":"one","text":"first"});
    let second = json!({"id":"two","text":"second"});
    store.add_message(&id, first.clone());
    writing.recv_timeout(TIMEOUT).unwrap();
    store.add_message(&id, second.clone());
    release.send(()).unwrap();
    store.save_now().unwrap();
    let reopened = StateStore::open(home.files(), write_atomic_file);
    assert_eq!(reopened.get_messages(&id), [first, second]);
}
