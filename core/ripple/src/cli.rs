//! The `ripple` command.

use anyhow::{Result, anyhow, bail};
use ripple::daemon::{self, socket_path};
use ripple::proto::{self, Frame};
use serde_json::{Value, json};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};

const HELP: &str = "Ripple — your computer's terminal, from your phone.

Usage:
  ripple                      open a terminal attached to a new session
  ripple attach [ID|NAME]     attach to a session (newest if none given)
  ripple new [--name N] [--cwd DIR] [-- COMMAND…]   start a session in the background
  ripple ls                   list sessions
  ripple kill ID|NAME         end a session
  ripple rename ID NEW-NAME   rename a session
  ripple pair                 show a QR code and code to pair a phone (and approve requests)
  ripple devices              paired phones
  ripple forget NAME|ID       unpair a phone
  ripple send FILE…           offer files to your phone (they also land in the shared folder)
  ripple notify TEXT          send a notification to connected phones
  ripple status               addresses, doorbell and connection details
  ripple config [KEY VALUE]   show or change settings
  ripple daemon               run the background service in the foreground
  ripple update               install the newest Linux release from GitHub
  ripple version

Inside a Ripple session, $RIPPLE_SESSION holds the session id.";

pub fn main() -> i32 {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("runtime");
    let r = rt.block_on(run(args));
    match r {
        Ok(code) => code,
        Err(e) => {
            eprintln!("ripple: {e}");
            1
        }
    }
}

async fn run(args: Vec<String>) -> Result<i32> {
    let cmd = args.first().map(|s| s.as_str()).unwrap_or("");
    let rest: Vec<String> = args.iter().skip(1).cloned().collect();
    match cmd {
        "" => attach_cmd(vec!["--new".into()]).await,
        "attach" | "a" => attach_cmd(rest).await,
        "daemon" => {
            daemon::run().await?;
            Ok(0)
        }
        "new" => new_cmd(rest).await,
        "ls" | "list" => ls_cmd(rest).await,
        "kill" => {
            let id = resolve_session(rest.first().ok_or_else(|| anyhow!("which session?"))?).await?;
            req(json!({"op": "kill", "id": id})).await?;
            Ok(0)
        }
        "rename" => {
            let id = resolve_session(rest.first().ok_or_else(|| anyhow!("which session?"))?).await?;
            req(json!({"op": "rename", "id": id, "name": rest[1..].join(" ")})).await?;
            Ok(0)
        }
        "pair" => pair_cmd(rest).await,
        "devices" => {
            let r = req(json!({"op": "devices"})).await?;
            let list = r["devices"].as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                println!("No phones paired yet. Run `ripple pair`.");
            }
            for (i, d) in list.iter().enumerate() {
                println!("{}  {}  (last seen {})", i + 1, d["name"].as_str().unwrap_or("?"), ago(d["last_seen"].as_u64().unwrap_or(0)));
            }
            Ok(0)
        }
        "forget" => {
            let r = req(json!({"op": "devices"})).await?;
            let list = r["devices"].as_array().cloned().unwrap_or_default();
            let key = rest.join(" ");
            let pick = list.iter().enumerate().find(|(i, d)| (i + 1).to_string() == key || d["name"].as_str() == Some(&key) || d["fp"].as_str().map(|f| f.starts_with(&key)).unwrap_or(false));
            let (_, d) = pick.ok_or_else(|| anyhow!("no paired phone called {key}"))?;
            req(json!({"op": "forget", "fp": d["fp"]})).await?;
            println!("Forgot {}", d["name"].as_str().unwrap_or("?"));
            Ok(0)
        }
        "send" => {
            if rest.is_empty() {
                bail!("which file?");
            }
            for f in &rest {
                let abs = std::fs::canonicalize(f).map_err(|e| anyhow!("{f}: {e}"))?;
                let r = req(json!({"op": "offer", "path": abs})).await?;
                println!("Offered {} → {}", r["name"].as_str().unwrap_or(""), r["path"].as_str().unwrap_or(""));
            }
            Ok(0)
        }
        "notify" => {
            let sid = std::env::var("RIPPLE_SESSION").ok().and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);
            req(json!({"op": "notify", "text": rest.join(" "), "session": sid, "title": "Ripple"})).await?;
            Ok(0)
        }
        "status" => status_cmd().await,
        "config" => config_cmd(rest).await,
        "update" => update_cmd(rest).await,
        "version" | "--version" | "-V" => {
            println!("ripple {}", ripple::VERSION);
            Ok(0)
        }
        "remote" => crate::cli::remote::run(rest).await,
        "help" | "--help" | "-h" => {
            println!("{HELP}");
            Ok(0)
        }
        other => bail!("unknown command “{other}”. Try `ripple help`."),
    }
}

fn ago(t: u64) -> String {
    if t == 0 {
        return "never".into();
    }
    let s = ripple::util::now_secs().saturating_sub(t);
    match s {
        0..=59 => "just now".into(),
        60..=3599 => format!("{} min ago", s / 60),
        3600..=86399 => format!("{} h ago", s / 3600),
        _ => format!("{} days ago", s / 86400),
    }
}

/// Connects to the daemon, starting it if needed.
pub async fn connect() -> Result<tokio::net::UnixStream> {
    let path = socket_path();
    if let Ok(s) = tokio::net::UnixStream::connect(&path).await {
        return Ok(s);
    }
    start_daemon()?;
    for _ in 0..60 {
        tokio::time::sleep(Duration::from_millis(100)).await;
        if let Ok(s) = tokio::net::UnixStream::connect(&path).await {
            return Ok(s);
        }
    }
    bail!("couldn't start the Ripple service; see ~/.cache/ripple/daemon.log")
}

fn start_daemon() -> Result<()> {
    use std::os::unix::process::CommandExt;
    let exe = std::env::current_exe()?;
    let cache = std::path::PathBuf::from(std::env::var("HOME").unwrap_or_default()).join(".cache/ripple");
    std::fs::create_dir_all(&cache)?;
    let log = std::fs::OpenOptions::new().create(true).append(true).open(cache.join("daemon.log"))?;
    let mut c = std::process::Command::new(exe);
    c.arg("daemon").stdin(std::process::Stdio::null()).stdout(log.try_clone()?).stderr(log);
    unsafe {
        c.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    c.spawn()?;
    Ok(())
}

pub async fn req(v: Value) -> Result<Value> {
    let s = connect().await?;
    let (mut r, mut w) = s.into_split();
    proto::write_json(&mut w, &v).await?;
    proto::check(proto::read_json(&mut r).await?)
}

async fn resolve_session(key: &str) -> Result<u64> {
    let r = req(json!({"op": "sessions"})).await?;
    let list = r["sessions"].as_array().cloned().unwrap_or_default();
    if let Ok(n) = key.parse::<u64>() {
        if list.iter().any(|s| s["id"].as_u64() == Some(n)) {
            return Ok(n);
        }
    }
    list.iter()
        .find(|s| s["name"].as_str().map(|n| n.eq_ignore_ascii_case(key)).unwrap_or(false))
        .and_then(|s| s["id"].as_u64())
        .ok_or_else(|| anyhow!("no session called “{key}” (see `ripple ls`)"))
}

async fn ls_cmd(rest: Vec<String>) -> Result<i32> {
    let r = req(json!({"op": "sessions"})).await?;
    if rest.iter().any(|a| a == "--json") {
        println!("{}", r["sessions"]);
        return Ok(0);
    }
    let list = r["sessions"].as_array().cloned().unwrap_or_default();
    if list.is_empty() {
        println!("No sessions. Start one with `ripple`.");
    }
    for s in list {
        let home = std::env::var("HOME").unwrap_or_default();
        let cwd = s["cwd"].as_str().unwrap_or("").replacen(&home, "~", 1);
        println!(
            "{:>3}  {:<20} {:<12} {:<30} {}",
            s["id"],
            s["name"].as_str().unwrap_or(""),
            s["program"].as_str().unwrap_or(""),
            cwd,
            match s["receivers"].as_u64().unwrap_or(0) { 0 => "detached".to_string(), n => format!("{n} attached") }
        );
    }
    Ok(0)
}

fn flag(rest: &mut Vec<String>, name: &str) -> Option<String> {
    let i = rest.iter().position(|a| a == name)?;
    rest.remove(i);
    if i < rest.len() { Some(rest.remove(i)) } else { None }
}

async fn new_cmd(mut rest: Vec<String>) -> Result<i32> {
    let name = flag(&mut rest, "--name");
    let cwd = flag(&mut rest, "--cwd").or_else(|| std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()));
    if rest.first().map(|s| s == "--") == Some(true) {
        rest.remove(0);
    }
    let command = if rest.is_empty() { None } else { Some(rest.join(" ")) };
    let r = req(json!({"op": "new", "name": name, "cwd": cwd, "command": command, "cols": 120, "rows": 40})).await?;
    println!("{}", r["id"]);
    Ok(0)
}

pub fn term_size() -> (u16, u16) {
    let mut ws: libc::winsize = unsafe { std::mem::zeroed() };
    let ok = unsafe { libc::ioctl(std::io::stdout().as_raw_fd(), libc::TIOCGWINSZ, &mut ws) } == 0;
    if ok && ws.ws_col > 0 { (ws.ws_col, ws.ws_row) } else { (80, 24) }
}

async fn attach_cmd(mut rest: Vec<String>) -> Result<i32> {
    let new = rest.iter().any(|a| a == "--new");
    rest.retain(|a| a != "--new");
    let name = flag(&mut rest, "--name");
    let cwd = flag(&mut rest, "--cwd").or_else(|| std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()));
    let no_history = rest.iter().any(|a| a == "--no-history");
    rest.retain(|a| a != "--no-history");
    let mut command = None;
    if let Some(i) = rest.iter().position(|a| a == "--") {
        command = Some(rest[i + 1..].join(" "));
        rest.truncate(i);
    }
    let (cols, rows) = term_size();
    let mut request = json!({"op": "attach", "cols": cols, "rows": rows, "history": !no_history});
    if new || command.is_some() {
        request["name"] = json!(name);
        request["cwd"] = json!(cwd);
        request["command"] = json!(command);
    } else {
        let id = match rest.first() {
            Some(k) => resolve_session(k).await?,
            None => {
                let r = req(json!({"op": "sessions"})).await?;
                match r["sessions"].as_array().and_then(|l| l.iter().filter(|s| s["receivers"].as_u64() == Some(0)).last().or(l.last()).cloned()) {
                    Some(s) => s["id"].as_u64().unwrap_or(0),
                    None => {
                        request["cwd"] = json!(cwd);
                        0
                    }
                }
            }
        };
        if id > 0 {
            request["id"] = json!(id);
        }
    }
    let s = connect().await?;
    let (r, w) = s.into_split();
    bridge(r, w, request).await
}

struct RawMode(Option<nix::sys::termios::Termios>);

impl RawMode {
    fn enter() -> Self {
        use nix::sys::termios::*;
        let stdin = std::io::stdin();
        match tcgetattr(&stdin) {
            Ok(orig) => {
                let mut raw = orig.clone();
                cfmakeraw(&mut raw);
                let _ = tcsetattr(&stdin, SetArg::TCSANOW, &raw);
                RawMode(Some(orig))
            }
            Err(_) => RawMode(None),
        }
    }
}

impl Drop for RawMode {
    fn drop(&mut self) {
        if let Some(t) = &self.0 {
            let _ = nix::sys::termios::tcsetattr(&std::io::stdin(), nix::sys::termios::SetArg::TCSANOW, t);
        }
    }
}

/// Connects this terminal to a session: keystrokes go there, its output comes here.
pub async fn bridge<R, W>(mut r: R, mut w: W, request: Value) -> Result<i32>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    proto::write_json(&mut w, &request).await?;
    let first = proto::check(proto::read_json(&mut r).await?)?;
    let sid = first["id"].as_u64().unwrap_or(0);
    // Let the desktop app know which session this window shows.
    if let Ok(f) = std::env::var("RIPPLE_REPORT_FD") {
        if let Ok(fd) = f.parse::<i32>() {
            let line = format!("{sid}\n");
            unsafe {
                libc::write(fd, line.as_ptr() as *const _, line.len());
            }
        }
    }
    let raw = RawMode::enter();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Frame>(256);
    // Keyboard → session.
    let tx_in = tx.clone();
    std::thread::spawn(move || {
        let mut buf = [0u8; 8192];
        let fd = std::io::stdin().as_raw_fd();
        loop {
            let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut _, buf.len()) };
            if n <= 0 {
                break;
            }
            if tx_in.blocking_send(Frame::Data(buf[..n as usize].to_vec())).is_err() {
                break;
            }
        }
    });
    // Window size changes.
    let tx_sz = tx.clone();
    tokio::spawn(async move {
        let Ok(mut sig) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::window_change()) else { return };
        while sig.recv().await.is_some() {
            let (c, r) = term_size();
            if tx_sz.send(Frame::Json(json!({"resize": [c, r]}))).await.is_err() {
                break;
            }
        }
    });
    drop(tx);
    tokio::spawn(async move {
        while let Some(f) = rx.recv().await {
            let ok = match f {
                Frame::Data(d) => proto::write_data(&mut w, &d).await,
                Frame::Json(v) => proto::write_json(&mut w, &v).await,
            };
            if ok.is_err() {
                break;
            }
        }
    });
    let mut out = std::io::stdout().lock();
    let mut code = 0;
    loop {
        match proto::read_frame(&mut r).await {
            Ok(Some(Frame::Data(d))) => {
                out.write_all(&d)?;
                out.flush()?;
            }
            Ok(Some(Frame::Json(v))) => {
                if let Some(c) = v.get("ended") {
                    code = c.as_i64().unwrap_or(0) as i32;
                    break;
                }
            }
            Ok(None) | Err(_) => {
                code = 255;
                break;
            }
        }
    }
    drop(raw);
    Ok(code)
}

async fn pair_cmd(rest: Vec<String>) -> Result<i32> {
    let r = req(json!({"op": "pair_code"})).await?;
    if rest.iter().any(|a| a == "--json") {
        println!("{r}");
        return Ok(0);
    }
    println!("{}", r["qr_text"].as_str().unwrap_or(""));
    println!("On your phone: Ripple › Add computer › Scan, or pick “{}” and type", r["computer"]["name"].as_str().unwrap_or(""));
    println!("\n      {}\n", r["code"].as_str().unwrap_or(""));
    println!("The code works once, for 10 minutes. Waiting for your phone… (Ctrl+C to stop)");
    let s = connect().await?;
    let (mut rd, mut wr) = s.into_split();
    proto::write_json(&mut wr, &json!({"op": "watch"})).await?;
    let _ = proto::read_json(&mut rd).await?;
    loop {
        let ev = proto::read_json(&mut rd).await?;
        match ev["event"].as_str() {
            Some("pair_request") => {
                println!("\n{} wants to connect. Its screen should show the code {}.", ev["device"].as_str().unwrap_or("A phone"), ev["compare"].as_str().unwrap_or(""));
                print!("Allow it? [y/N] ");
                std::io::stdout().flush()?;
                let answer = tokio::task::spawn_blocking(|| {
                    let mut s = String::new();
                    std::io::stdin().read_line(&mut s).ok();
                    s
                })
                .await?;
                let allow = answer.trim().eq_ignore_ascii_case("y") || answer.trim().eq_ignore_ascii_case("yes");
                req(json!({"op": "pair_answer", "request": ev["request"], "allow": allow})).await?;
                if allow {
                    println!("Paired.");
                    return Ok(0);
                }
            }
            Some("devices") => {
                println!("\nPaired with your phone.");
                return Ok(0);
            }
            _ => {}
        }
    }
}

async fn status_cmd() -> Result<i32> {
    let r = req(json!({"op": "info"})).await?;
    let c = &r["computer"];
    println!("Ripple {} on {} ({})", ripple::VERSION, c["name"].as_str().unwrap_or(""), c["user"].as_str().unwrap_or(""));
    println!("Identity  {}", c["fp"].as_str().unwrap_or("").get(..16).unwrap_or(""));
    println!("Port      UDP {}", c["port"]);
    println!("Reachable at:");
    for a in c["addrs"].as_array().cloned().unwrap_or_default() {
        println!("  {}", a.as_str().unwrap_or(""));
    }
    let d = &r["doorbell"];
    println!(
        "Doorbell  {}",
        if d["enabled"].as_bool() != Some(true) {
            "off (phones can only connect at home or on addresses above)".to_string()
        } else if d["listening"].as_bool() == Some(true) {
            format!("listening ({})", c["doorbell"]["server"].as_str().unwrap_or(""))
        } else {
            format!("not connected: {}", d["message"].as_str().unwrap_or(""))
        }
    );
    println!("Shared folder  {}", c["shared_dir"].as_str().unwrap_or(""));
    println!("Sessions  {}", r["sessions"].as_array().map(|a| a.len()).unwrap_or(0));
    Ok(0)
}

async fn config_cmd(rest: Vec<String>) -> Result<i32> {
    if rest.len() >= 2 {
        let key = rest[0].as_str();
        let val = rest[1..].join(" ");
        let v: Value = match key {
            "doorbell_enabled" | "upnp" | "auto_update" => json!(matches!(val.as_str(), "on" | "true" | "yes" | "1")),
            "notify_quiet_secs" | "port" => json!(val.parse::<u64>()?),
            "extra_addresses" => json!(val.split(',').map(|s| s.trim()).collect::<Vec<_>>()),
            _ => json!(val),
        };
        let mut r = json!({"op": "set_config"});
        r[key] = v;
        req(r).await?;
        if key == "port" {
            println!("The new port is used after Ripple restarts (`systemctl --user restart ripple`, or log out and in).");
        }
    }
    let r = req(json!({"op": "config"})).await?;
    let mut c = r["config"].clone();
    c["doorbell"]["key"] = json!("(hidden)");
    println!("{}", serde_json::to_string_pretty(&c)?);
    Ok(0)
}

async fn update_cmd(rest: Vec<String>) -> Result<i32> {
    let check_only = rest.iter().any(|a| a == "--check");
    let r = tokio::task::spawn_blocking(move || crate::cli::update::run(check_only)).await??;
    println!("{r}");
    Ok(0)
}

pub mod remote;
pub mod update;
