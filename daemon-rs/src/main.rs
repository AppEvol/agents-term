mod app_cli;
mod app_state;
mod attachments;
mod config;
mod http_client;
mod install;
mod log;
mod models;
mod runner;
mod server;
mod shell;
mod store;
mod update;
mod usage;
mod util;

use std::env;
use std::path::Path;
use std::process::ExitCode;

use crate::config::{
    acquire_daemon_lock, daemon_commit, detect_tailscale_ip, load_or_create_config, pairing_uri,
    rotate_token, save_config, BUILD_HASH, SERVER_VERSION,
};

const USAGE: &str = "usage: agentsterm-daemon [serve|install|ensure-running|update|rollback|pairing|rotate-token|version|app] [options]\n\
\n\
  serve           run the daemon (default); binds the Tailscale IPv4 unless --host is given\n\
  install         install/update systemd --user or detached+@reboot service and verify health\n\
  ensure-running  start the daemon if not already running (used by @reboot crontab)\n\
  update          download, verify, and install a daemon release (<version>|latest)\n\
  rollback        restore the previous known-good daemon binary (.prev) and restart\n\
  pairing         print the herdr://connect pairing URI for the Android app\n\
  rotate-token    generate a new bearer token (restart the daemon to apply)\n\
  version         print the daemon version and build hash (--hash-only for hash only)\n\
  app             inspect or drive the connected Android app (also via agents_term_app_cli symlink)\n\
\n\
Options:\n\
  --host HOST       bind/advertise address\n\
  --port PORT       TCP port (default: $AGENT_TERMINAL_PORT or 7501)\n\
  --no-auth         disable bearer authentication (serve only)\n\
  --force-restart   restart even if running build hash matches (install only)\n";

fn default_port() -> u16 {
    env::var("AGENT_TERMINAL_PORT")
        .or_else(|_| env::var("HERDR_PORT"))
        .ok()
        .and_then(|s| s.parse::<u16>().ok())
        .filter(|&p| p >= 1)
        .unwrap_or(7501)
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();

    // Support direct invocation via symlink `~/.agents-term/bin/agents_term_app_cli -> agentsterm-daemon`
    // (and legacy `app_state_cli` alias).
    let invoked_name = args
        .first()
        .and_then(|a| Path::new(a).file_name())
        .and_then(|n| n.to_str())
        .unwrap_or("");
    if matches!(
        invoked_name,
        "agents_term_app_cli" | "agents-term-app-cli" | "app_state_cli" | "app-state-cli"
    ) {
        unsafe {
            libc::signal(libc::SIGPIPE, libc::SIG_DFL);
        }
        return ExitCode::from(app_cli::run_app_cli("agents_term_app_cli", &args[1..]) as u8);
    }

    let argv = &args[1..];
    if let Some(first) = argv.first() {
        if first == "app" {
            unsafe {
                libc::signal(libc::SIGPIPE, libc::SIG_DFL);
            }
            return ExitCode::from(app_cli::run_app_cli("agentsterm-daemon app", &argv[1..]) as u8);
        }
        if first == "update" {
            return ExitCode::from(update::run_update_cli(&argv[1..], default_port()) as u8);
        }
        if first == "rollback" {
            return ExitCode::from(update::run_rollback_cli(&argv[1..], default_port()) as u8);
        }
        if first == "rollback-watch" {
            return ExitCode::from(update::run_rollback_watch_cli(&argv[1..], default_port()) as u8);
        }
    }

    let mut command = "serve".to_string();
    let mut host: Option<String> = None;
    let mut port: u16 = default_port();
    let mut no_auth = false;
    let mut force_restart = false;
    let mut hash_only = false;

    let mut i = 0;
    if let Some(first) = argv.first() {
        if !first.starts_with('-') {
            command = first.clone();
            i = 1;
        }
    }

    while i < argv.len() {
        let arg = &argv[i];
        if arg == "--port" && i + 1 < argv.len() {
            i += 1;
            port = argv[i].parse::<u16>().unwrap_or(7501);
        } else if let Some(v) = arg.strip_prefix("--port=") {
            port = v.parse::<u16>().unwrap_or(7501);
        } else if arg == "--host" && i + 1 < argv.len() {
            i += 1;
            host = Some(argv[i].clone());
        } else if let Some(v) = arg.strip_prefix("--host=") {
            host = Some(v.to_string());
        } else if arg == "--no-auth" {
            no_auth = true;
        } else if arg == "--force-restart" {
            force_restart = true;
        } else if arg == "--hash-only" {
            hash_only = true;
        } else if arg == "-h" || arg == "--help" {
            command = "help".to_string();
        }
        i += 1;
    }
    if port == 0 {
        port = 7501;
    }

    match command.as_str() {
        "help" => {
            print!("{USAGE}");
            ExitCode::SUCCESS
        }
        "version" => {
            if hash_only {
                println!("{BUILD_HASH}");
            } else {
                println!("{SERVER_VERSION} {}", daemon_commit());
            }
            ExitCode::SUCCESS
        }
        "install" => ExitCode::from(install::run_install(host, port, force_restart) as u8),
        "ensure-running" => ExitCode::from(install::run_ensure_running(host, port) as u8),
        "pairing" | "rotate-token" => {
            let cfg = if command == "rotate-token" {
                rotate_token()
            } else {
                load_or_create_config()
            };
            let resolved_host = host
                .or(cfg.host)
                .or_else(|| detect_tailscale_ip(false))
                .unwrap_or_else(|| "127.0.0.1".to_string());
            let resolved_port = if argv
                .iter()
                .any(|a| a == "--port" || a.starts_with("--port="))
            {
                port
            } else {
                cfg.port.unwrap_or(port)
            };
            let uri = pairing_uri(&resolved_host, resolved_port, &cfg.token);
            if command == "pairing" {
                println!("Host:  {resolved_host}");
                println!("Port:  {resolved_port}");
                println!("Token: {}", cfg.token);
                println!();
            } else {
                println!("Rotated bearer token. Restart the daemon to apply.");
            }
            println!("{uri}");
            ExitCode::SUCCESS
        }
        "serve" => {
            let daemon_lock = match acquire_daemon_lock() {
                Ok(lock) => lock,
                Err(err) => {
                    eprintln!("Failed to acquire daemon lock: {err}");
                    return ExitCode::from(1);
                }
            };
            let mut cfg = load_or_create_config();
            let bind_host = host
                .or_else(|| {
                    env::var("AGENT_TERMINAL_HOST")
                        .ok()
                        .filter(|s| !s.is_empty())
                })
                .or_else(|| detect_tailscale_ip(false))
                .unwrap_or_else(|| "0.0.0.0".to_string());
            if cfg.host.as_deref() != Some(bind_host.as_str()) || cfg.port != Some(port) {
                cfg.host = Some(bind_host.clone());
                cfg.port = Some(port);
                let _ = save_config(&cfg);
            }

            let mut serve_args = vec![
                "serve".to_string(),
                "--host".to_string(),
                bind_host.clone(),
                "--port".to_string(),
                port.to_string(),
            ];
            if no_auth {
                serve_args.push("--no-auth".to_string());
            }

            match update::check_startup_crash_loop_guard(&serve_args) {
                Ok(Some(plan)) => {
                    drop(daemon_lock);
                    if let Err(err) = plan.exec() {
                        eprintln!("Failed to execv rollback daemon: {err}");
                        return ExitCode::from(1);
                    }
                    return ExitCode::SUCCESS;
                }
                Ok(None) => {}
                Err(err) => {
                    eprintln!("Startup crash-loop rollback failed: {err}");
                }
            }

            let rt = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("failed to build tokio runtime");
            let run_result = rt.block_on(server::run_daemon(
                bind_host.clone(),
                port,
                cfg.token,
                no_auth,
            ));
            drop(rt);
            drop(daemon_lock);

            match run_result {
                Ok(Some(plan)) => {
                    if let Err(err) = plan.exec() {
                        eprintln!("Failed to execv updated daemon: {err}");
                        ExitCode::from(1)
                    } else {
                        ExitCode::SUCCESS
                    }
                }
                Ok(None) => ExitCode::SUCCESS,
                Err(err) => {
                    eprintln!("Failed to start daemon on {bind_host}:{port}: {err}");
                    ExitCode::from(1)
                }
            }
        }
        other => {
            eprint!("Unknown command: {other}\n\n{USAGE}");
            ExitCode::from(2)
        }
    }
}
