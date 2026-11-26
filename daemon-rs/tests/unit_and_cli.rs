use serde_json::Value;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::process::Command;

fn bin_path() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_agentsterm-daemon"))
}

fn make_temp_dir(prefix: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "{prefix}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn cli_version_pairing_and_rotate_token() {
    let home = make_temp_dir("agentsterm-cli");

    let ver = Command::new(bin_path())
        .arg("version")
        .env("HOME", &home)
        .output()
        .unwrap();
    assert!(ver.status.success());
    let ver_str = String::from_utf8_lossy(&ver.stdout);
    let parts: Vec<&str> = ver_str.split_whitespace().collect();
    assert_eq!(parts.len(), 2, "unexpected version output: {ver_str}");
    assert_eq!(parts[1].len(), 40, "expected 40-char build hash: {ver_str}");

    let hash_only = Command::new(bin_path())
        .args(["version", "--hash-only"])
        .env("HOME", &home)
        .output()
        .unwrap();
    assert!(hash_only.status.success());
    assert_eq!(String::from_utf8_lossy(&hash_only.stdout).trim(), parts[1]);

    let p1 = Command::new(bin_path())
        .args(["pairing", "--host", "100.64.1.2", "--port", "7512"])
        .env("HOME", &home)
        .env_remove("AGENTSTERM_HOME")
        .output()
        .unwrap();
    assert!(p1.status.success());
    let out1 = String::from_utf8_lossy(&p1.stdout);
    assert!(out1.contains("herdr://connect?host=100.64.1.2&port=7512&token="));

    let cfg_dir = home.join(".agents-term");
    let cfg_path = cfg_dir.join("config.json");
    assert_eq!(
        fs::metadata(&cfg_dir).unwrap().permissions().mode() & 0o777,
        0o700
    );
    assert_eq!(
        fs::metadata(&cfg_path).unwrap().permissions().mode() & 0o777,
        0o600
    );
    let cfg1: Value = serde_json::from_str(&fs::read_to_string(&cfg_path).unwrap()).unwrap();
    let tok1 = cfg1["token"].as_str().unwrap().to_string();
    assert_eq!(tok1.len(), 32);

    let rot = Command::new(bin_path())
        .args(["rotate-token", "--host", "100.64.1.2", "--port", "7512"])
        .env("HOME", &home)
        .env_remove("AGENTSTERM_HOME")
        .output()
        .unwrap();
    assert!(rot.status.success());
    let cfg2: Value = serde_json::from_str(&fs::read_to_string(&cfg_path).unwrap()).unwrap();
    let tok2 = cfg2["token"].as_str().unwrap().to_string();
    assert_ne!(tok1, tok2);

    let _ = fs::remove_dir_all(&home);
}

#[test]
fn agents_term_home_env_isolates_config_without_touching_home_dot_dir() {
    let home = make_temp_dir("agentsterm-override-home");
    let custom = make_temp_dir("agentsterm-custom-root").join("state");

    let p = Command::new(bin_path())
        .args(["pairing", "--host", "127.0.0.1", "--port", "7599"])
        .env("HOME", &home)
        .env("AGENTSTERM_HOME", &custom)
        .output()
        .unwrap();
    assert!(p.status.success());
    assert!(custom.join("config.json").is_file());
    assert!(!home.join(".agents-term").exists());

    let _ = fs::remove_dir_all(&home);
    let _ = fs::remove_dir_all(custom.parent().unwrap());
}

#[test]
fn app_cli_does_not_create_config_or_state_dir() {
    let home = make_temp_dir("agentsterm-cli-readonly");
    let custom = home.join("custom-root");

    let res = Command::new(bin_path())
        .args(["app", "status", "--host", "127.0.0.1", "--port", "1"])
        .env("HOME", &home)
        .env("AGENTSTERM_HOME", &custom)
        .output()
        .unwrap();
    assert_eq!(res.status.code(), Some(2));
    assert!(
        !custom.exists(),
        "app CLI must stay read-only and never create AGENTSTERM_HOME"
    );
    assert!(
        !home.join(".agents-term").exists(),
        "app CLI must never create ~/.agents-term"
    );

    let _ = fs::remove_dir_all(&home);
}

#[test]
fn app_cli_reports_exit_2_when_daemon_unreachable_and_supports_symlink() {
    let home = make_temp_dir("agentsterm-app-cli");
    let link_dir = home.join("bin");
    fs::create_dir_all(&link_dir).unwrap();
    let primary_link = link_dir.join("agents_term_app_cli");
    let legacy_link = link_dir.join("app_state_cli");
    std::os::unix::fs::symlink(bin_path(), &primary_link).unwrap();
    std::os::unix::fs::symlink(bin_path(), &legacy_link).unwrap();

    // Global flags work both before and after subcommand, and unreachable daemon exits 2.
    for link in [&primary_link, &legacy_link] {
        let res = Command::new(link)
            .args(["status", "--host", "127.0.0.1", "--port", "1"])
            .env("HOME", &home)
            .output()
            .unwrap();
        assert_eq!(res.status.code(), Some(2));
        let err_json: Value = serde_json::from_slice(&res.stderr).unwrap();
        assert_eq!(err_json["ok"].as_bool(), Some(false));
        assert_eq!(err_json["code"].as_str(), Some("daemon_unreachable"));
    }

    // Help output via agents_term_app_cli explains agent usage, prerequisites, and exit codes.
    let help = Command::new(&primary_link)
        .arg("--help")
        .env("HOME", &home)
        .output()
        .unwrap();
    assert!(help.status.success());
    let help_str = String::from_utf8_lossy(&help.stdout);
    assert!(help_str.contains("agents_term_app_cli"));
    assert!(help_str.contains("RECOMMENDED AGENT WORKFLOW"));
    assert!(help_str.contains("Allow VM agents to inspect the app"));
    assert!(help_str.contains("Allow VM agents to control the app"));
    assert!(help_str.contains("tap 980 2200"));

    // Usage error (both --tag and --xy on tap) exits 1 with bad_args.
    let bad_tap = Command::new(bin_path())
        .args(["app", "tap", "--tag", "foo", "--xy", "1", "2"])
        .env("HOME", &home)
        .output()
        .unwrap();
    assert_eq!(bad_tap.status.code(), Some(1));
    let bad_json: Value = serde_json::from_slice(&bad_tap.stderr).unwrap();
    assert_eq!(bad_json["code"].as_str(), Some("bad_args"));

    let _ = fs::remove_dir_all(&home);
}
