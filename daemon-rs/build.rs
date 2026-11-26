use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn collect_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let mut paths: Vec<PathBuf> = entries.filter_map(|e| e.ok().map(|e| e.path())).collect();
    paths.sort();
    for path in paths {
        if path.is_dir() {
            collect_files(&path, out);
        } else if path.is_file() {
            out.push(path);
        }
    }
}

fn main() {
    println!("cargo:rerun-if-env-changed=AGENTSTERM_DAEMON_BUILD_HASH");
    println!("cargo:rerun-if-changed=Cargo.toml");
    println!("cargo:rerun-if-changed=Cargo.lock");
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src");
    println!("cargo:rerun-if-changed=../docs/live-debugging.md");
    println!("cargo:rerun-if-changed=../docs/agents_debugging_tips.md");

    if let Ok(override_hash) = env::var("AGENTSTERM_DAEMON_BUILD_HASH") {
        let trimmed = override_hash.trim();
        if !trimmed.is_empty() {
            println!("cargo:rustc-env=AGENTSTERM_BUILD_HASH={trimmed}");
            return;
        }
    }

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap_or_else(|_| ".".into()));
    let mut files = Vec::new();
    for rel in [
        "Cargo.toml",
        "Cargo.lock",
        "build.rs",
        "../docs/live-debugging.md",
        "../docs/agents_debugging_tips.md",
    ] {
        let p = manifest_dir.join(rel);
        if p.is_file() {
            files.push(p);
        }
    }
    collect_files(&manifest_dir.join("src"), &mut files);
    files.sort();

    let mut hasher = Sha256::new();
    for path in &files {
        if let Ok(rel) = path.strip_prefix(&manifest_dir) {
            hasher.update(rel.to_string_lossy().as_bytes());
        } else if let Some(name) = path.file_name() {
            hasher.update(name.to_string_lossy().as_bytes());
        }
        hasher.update(b"\0");
        if let Ok(bytes) = fs::read(path) {
            hasher.update(&bytes);
        }
        hasher.update(b"\0");
    }
    let digest = hasher.finalize();
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    let build_hash = &hex[..40];
    println!("cargo:rustc-env=AGENTSTERM_BUILD_HASH={build_hash}");
}
