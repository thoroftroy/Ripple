//! The Ripple daemon that runs on the computer: owns terminal sessions, serves the local desktop
//! app over a Unix socket and paired phones over QUIC.

pub mod files;
pub mod reach;
pub mod server;
pub mod session;
pub mod stats;

use crate::doorbell::DoorbellInfo;
use crate::identity::Identity;
use crate::net::{self, ComputerInfo};
use anyhow::Result;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use session::{Session, Spawn};
use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use tokio::sync::{broadcast, oneshot};

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default)]
pub struct Config {
    pub name: String,
    pub port: u16,
    /// Shell for new sessions; empty = the login shell from the system.
    pub shell: String,
    pub doorbell: DoorbellInfo,
    pub doorbell_enabled: bool,
    pub upnp: bool,
    /// Extra ways to reach this computer (a DNS name, a static address) that phones should try.
    pub extra_addresses: Vec<String>,
    /// Notify when a session that was busy goes quiet for this many seconds (0 = never).
    pub notify_quiet_secs: u64,
    pub shared_dir: String,
    /// Check GitHub for new versions of the Linux app.
    pub auto_update: bool,
    /// Wonderdesk look (AQUA, BLISS, DARK, SUPER); phones send theirs so the desktop matches.
    pub look: String,
    /// Follow the look of the phone that connects.
    pub follow_phone_look: bool,
    /// Start the sessions that were open again when the service starts (after a restart or reboot).
    pub restore_sessions: bool,
    /// Share copied text between the computer and paired phones.
    pub clipboard_sync: bool,
}

impl Default for Config {
    fn default() -> Self {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/".into());
        Self {
            name: crate::util::hostname(),
            port: crate::DEFAULT_PORT,
            shell: String::new(),
            doorbell: DoorbellInfo::generate(crate::doorbell::DEFAULT_SERVER),
            doorbell_enabled: true,
            upnp: true,
            extra_addresses: vec![],
            notify_quiet_secs: 20,
            shared_dir: format!("{home}/Ripple"),
            auto_update: true,
            look: "AQUA".into(),
            follow_phone_look: true,
            restore_sessions: true,
            clipboard_sync: true,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Device {
    pub fp: String,
    pub name: String,
    pub added: u64,
    #[serde(default)]
    pub last_seen: u64,
}

pub fn config_dir() -> PathBuf {
    std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/tmp".into())).join(".config"))
        .join("ripple")
}

pub fn socket_path() -> PathBuf {
    if let Ok(p) = std::env::var("RIPPLE_SOCKET") {
        return PathBuf::from(p);
    }
    match std::env::var("XDG_RUNTIME_DIR") {
        Ok(d) if !d.is_empty() => PathBuf::from(d).join("ripple.sock"),
        _ => PathBuf::from(format!("/tmp/ripple-{}.sock", unsafe { libc::getuid() })),
    }
}

pub fn login_shell() -> String {
    if let Ok(s) = std::env::var("SHELL") {
        if !s.is_empty() {
            return s;
        }
    }
    unsafe {
        let pw = libc::getpwuid(libc::getuid());
        if !pw.is_null() && !(*pw).pw_shell.is_null() {
            return std::ffi::CStr::from_ptr((*pw).pw_shell).to_string_lossy().to_string();
        }
    }
    "/bin/sh".into()
}

pub struct PairRequest {
    pub device: String,
    pub fp: String,
    pub compare: String,
    pub reply: oneshot::Sender<bool>,
}

pub struct Daemon {
    pub id: Identity,
    pub cfg: RwLock<Config>,
    pub devices: Mutex<Vec<Device>>,
    pub sessions: Mutex<BTreeMap<u32, Arc<Session>>>,
    next_id: AtomicU32,
    pub events: broadcast::Sender<Value>,
    pub codes: Mutex<Vec<(String, u64)>>,
    pub pending: Mutex<HashMap<String, PairRequest>>,
    pub public_addrs: Mutex<Vec<String>>,
    pub endpoint: Mutex<Option<quinn::Endpoint>>,
    pub doorbell_state: Mutex<(bool, String)>,
    pub seen_nonces: Mutex<Vec<String>>,
    /// Environment from the desktop session (DISPLAY, SSH_AUTH_SOCK…) for new sessions.
    pub session_env: Mutex<Vec<(String, String)>>,
    /// Recently copied text, newest first, shared between the computer and phones.
    pub clips: Mutex<Vec<Value>>,
    pub shutting_down: std::sync::atomic::AtomicBool,
}

impl Daemon {
    pub fn load() -> Result<Arc<Daemon>> {
        let dir = config_dir();
        std::fs::create_dir_all(&dir)?;
        let cfg_path = dir.join("config.json");
        let cfg: Config = std::fs::read(&cfg_path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        let id = Identity::load_or_create(&dir, &cfg.name)?;
        let devices: Vec<Device> = std::fs::read(dir.join("devices.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        let _ = std::fs::create_dir_all(&cfg.shared_dir);
        let (events, _) = broadcast::channel(512);
        let d = Arc::new(Daemon {
            id,
            cfg: RwLock::new(cfg),
            devices: Mutex::new(devices),
            sessions: Mutex::new(BTreeMap::new()),
            next_id: AtomicU32::new(1),
            events,
            codes: Mutex::new(vec![]),
            pending: Mutex::new(HashMap::new()),
            public_addrs: Mutex::new(vec![]),
            endpoint: Mutex::new(None),
            doorbell_state: Mutex::new((false, String::new())),
            seen_nonces: Mutex::new(vec![]),
            session_env: Mutex::new(vec![]),
            clips: Mutex::new(vec![]),
            shutting_down: std::sync::atomic::AtomicBool::new(false),
        });
        d.save_config()?;
        Ok(d)
    }

    pub fn save_config(&self) -> Result<()> {
        let cfg = self.cfg.read().unwrap().clone();
        crate::identity::write_private(&config_dir().join("config.json"), &serde_json::to_vec_pretty(&cfg)?)
    }

    pub fn save_devices(&self) -> Result<()> {
        let d = self.devices.lock().unwrap().clone();
        crate::identity::write_private(&config_dir().join("devices.json"), &serde_json::to_vec_pretty(&d)?)
    }

    pub fn is_trusted(&self, fp: &str) -> bool {
        self.devices.lock().unwrap().iter().any(|d| crate::util::ct_eq(&d.fp, fp))
    }

    pub fn touch_device(&self, fp: &str) {
        let mut list = self.devices.lock().unwrap();
        if let Some(d) = list.iter_mut().find(|d| d.fp == fp) {
            d.last_seen = crate::util::now_secs();
        }
        drop(list);
        let _ = self.save_devices();
    }

    pub fn info(&self) -> ComputerInfo {
        let cfg = self.cfg.read().unwrap().clone();
        let mut addrs: Vec<String> = net::local_ips().into_iter().map(|ip| net::fmt_addr(ip, cfg.port)).collect();
        addrs.extend(self.public_addrs.lock().unwrap().iter().cloned());
        addrs.extend(cfg.extra_addresses.iter().cloned());
        ComputerInfo {
            name: cfg.name.clone(),
            fp: self.id.fingerprint(),
            port: cfg.port,
            addrs,
            doorbell: if cfg.doorbell_enabled { Some(cfg.doorbell.clone()) } else { None },
            version: crate::VERSION.into(),
            user: std::env::var("USER").unwrap_or_default(),
            home: std::env::var("HOME").unwrap_or_default(),
            shared_dir: cfg.shared_dir.clone(),
            macs: stats::hardware_addresses(),
        }
    }

    pub fn shell(&self) -> String {
        let s = self.cfg.read().unwrap().shell.clone();
        if s.is_empty() { login_shell() } else { s }
    }

    pub fn new_session(self: &Arc<Self>, name: Option<String>, cwd: Option<String>, command: Option<String>, cols: u16, rows: u16) -> Result<Arc<Session>> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let shell = self.shell();
        let default_name = match &command {
            Some(c) => c.split_whitespace().next().unwrap_or("Terminal").rsplit('/').next().unwrap_or("Terminal").to_string(),
            None => format!("Terminal {id}"),
        };
        let name = name.filter(|n| !n.trim().is_empty()).unwrap_or(default_name);
        let env = self.session_env.lock().unwrap().clone();
        let sess = Session::spawn(id, Spawn { name, cwd, command, shell, cols, rows, env }, self.events.clone())?;
        self.sessions.lock().unwrap().insert(id, sess.clone());
        // Remove the session when its program exits.
        let me = self.clone();
        let mut ended = sess.ended.subscribe();
        tokio::spawn(async move {
            while ended.borrow().is_none() {
                if ended.changed().await.is_err() {
                    break;
                }
            }
            let code = *ended.borrow();
            me.sessions.lock().unwrap().remove(&id);
            let _ = me.events.send(json!({"event": "ended", "session": id, "code": code}));
            me.sessions_changed();
        });
        self.sessions_changed();
        Ok(sess)
    }

    pub fn session(&self, id: u32) -> Option<Arc<Session>> {
        self.sessions.lock().unwrap().get(&id).cloned()
    }

    pub fn list_sessions(&self) -> Vec<Value> {
        let list: Vec<Arc<Session>> = self.sessions.lock().unwrap().values().cloned().collect();
        list.iter().map(|s| s.summary()).collect()
    }

    pub fn sessions_changed(&self) {
        let _ = self.events.send(json!({"event": "sessions", "sessions": self.list_sessions()}));
    }

    /// Remembers which sessions are open so they can start again after a restart or reboot.
    pub fn save_sessions(&self) {
        if self.shutting_down.load(Ordering::SeqCst) {
            return;
        }
        let list: Vec<Value> = self.sessions.lock().unwrap().values().map(|s| {
            let sum = s.summary();
            json!({"name": s.name.lock().unwrap().clone(), "cwd": sum["cwd"], "command": s.command, "cols": sum["cols"], "rows": sum["rows"]})
        }).collect();
        let path = config_dir().join("sessions.json");
        let text = serde_json::to_vec_pretty(&list).unwrap_or_default();
        if std::fs::read(&path).ok().as_deref() != Some(&text) {
            let _ = crate::identity::write_private(&path, &text);
        }
    }

    pub fn restore_sessions(self: &Arc<Self>) {
        if !self.cfg.read().unwrap().restore_sessions {
            return;
        }
        let Ok(raw) = std::fs::read(config_dir().join("sessions.json")) else { return };
        let Ok(list) = serde_json::from_slice::<Vec<Value>>(&raw) else { return };
        for v in list.iter().take(40) {
            let name = v["name"].as_str().map(String::from);
            let cwd = v["cwd"].as_str().map(String::from);
            let command = v["command"].as_str().map(String::from);
            let cols = v["cols"].as_u64().unwrap_or(100) as u16;
            let rows = v["rows"].as_u64().unwrap_or(30) as u16;
            let _ = self.new_session(name, cwd, command, cols, rows);
        }
    }

    /// Adds copied text to the shared clipboard history and tells everyone listening.
    pub fn add_clip(&self, text: String, from: &str) -> bool {
        if text.is_empty() || text.len() > 1_000_000 || !self.cfg.read().unwrap().clipboard_sync {
            return false;
        }
        let mut clips = self.clips.lock().unwrap();
        if clips.first().map(|c| c["text"] == text.as_str()).unwrap_or(false) {
            return false;
        }
        clips.retain(|c| c["text"] != text.as_str());
        let entry = json!({"text": text, "from": from, "at": crate::util::now_secs()});
        clips.insert(0, entry.clone());
        clips.truncate(30);
        drop(clips);
        let _ = self.events.send(json!({"event": "clip", "text": entry["text"], "from": from, "at": entry["at"]}));
        true
    }

    pub fn new_code(&self) -> String {
        let code = crate::util::readable_code(10);
        let mut codes = self.codes.lock().unwrap();
        let now = crate::util::now_secs();
        codes.retain(|(_, exp)| *exp > now);
        codes.push((code.clone(), now + 600));
        code
    }

    /// A ripple:// link (shown as a QR code) carrying addresses, fingerprint and a one-time code.
    pub fn pair_uri(&self, code: &str) -> String {
        let info = self.info();
        let enc = |s: &str| s.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") }).collect::<String>();
        let mut uri = format!("ripple://pair?n={}&a={}&p={}&f={}&c={}", enc(&info.name), enc(&info.addrs.join(",")), info.port, info.fp, code);
        if let Some(b) = &info.doorbell {
            uri.push_str(&format!("&bs={}&bt={}&bk={}", enc(&b.server), enc(&b.topic), enc(&b.key)));
        }
        uri
    }
}

/// Runs the daemon until killed.
pub async fn run() -> Result<()> {
    let d = Daemon::load()?;
    let sock = socket_path();
    server::serve_unix(d.clone(), &sock).await?;
    reach::start(d.clone()).await;
    d.restore_sessions();
    // Background upkeep: quiet-session notifications.
    let me = d.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            let secs = me.cfg.read().unwrap().notify_quiet_secs;
            let list: Vec<Arc<Session>> = me.sessions.lock().unwrap().values().cloned().collect();
            for s in list {
                s.check_quiet(secs);
            }
            me.save_sessions();
        }
    });
    // Updates: check GitHub every few hours; when a new binary is installed, restart into it as
    // soon as no sessions are running (the service manager starts it again).
    let me = d.clone();
    tokio::spawn(async move {
        let exe = std::env::current_exe().ok();
        let stamp = |p: &Option<PathBuf>| p.as_ref().and_then(|p| std::fs::metadata(p).ok()).and_then(|m| m.modified().ok());
        let started = stamp(&exe);
        let mut next_check = tokio::time::Instant::now() + std::time::Duration::from_secs(10 * 60);
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(60)).await;
            if me.cfg.read().unwrap().auto_update && tokio::time::Instant::now() >= next_check {
                next_check = tokio::time::Instant::now() + std::time::Duration::from_secs(6 * 3600);
                if let Some(e) = &exe {
                    let _ = tokio::process::Command::new(e).arg("update").stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).status().await;
                }
            }
            let replaced = stamp(&exe) != started;
            if replaced && me.sessions.lock().unwrap().is_empty() {
                eprintln!("ripple: restarting into the updated version");
                let _ = std::fs::remove_file(socket_path());
                std::process::exit(0);
            }
        }
    });
    eprintln!("ripple: daemon ready on {} and udp port {}", sock.display(), d.cfg.read().unwrap().port);
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {},
        _ = term.recv() => {},
    }
    d.save_sessions();
    d.shutting_down.store(true, Ordering::SeqCst);
    let list: Vec<Arc<Session>> = d.sessions.lock().unwrap().values().cloned().collect();
    for s in list {
        s.kill();
    }
    let _ = std::fs::remove_file(&sock);
    Ok(())
}
