//! Persistent terminal sessions. The daemon owns each session's pseudo-terminal, so programs keep
//! running when every window and phone disconnects. A screen model (vt100) remembers what the
//! terminal shows, so anyone attaching later gets the current screen plus recent history.

use anyhow::{Context, Result};
use bytes::Bytes;
use serde_json::{Value, json};
use std::num::NonZeroU16;
use std::os::fd::{AsRawFd, OwnedFd};
use std::os::unix::process::CommandExt;
use std::os::unix::process::ExitStatusExt;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use tokio::io::unix::AsyncFd;
use tokio::sync::{broadcast, watch};

pub const HISTORY_LINES: usize = 3000;

#[derive(Default)]
pub struct Cb {
    bell: bool,
    title: Option<String>,
    notes: Vec<String>,
}

impl vt100::Callbacks for Cb {
    fn audible_bell(&mut self, _: &mut vt100::Screen) {
        self.bell = true;
    }
    fn set_window_title(&mut self, _: &mut vt100::Screen, title: &[u8]) {
        self.title = Some(String::from_utf8_lossy(title).to_string());
    }
    fn unhandled_osc(&mut self, _: &mut vt100::Screen, params: &[&[u8]]) {
        let s = |b: &[u8]| String::from_utf8_lossy(b).to_string();
        match params {
            // OSC 9 ; text  (iTerm2-style notification). OSC 9;4 is a progress report, not a message.
            [b"9", rest @ ..] if !rest.is_empty() && rest[0] != b"4" => {
                self.notes.push(rest.iter().map(|p| s(p)).collect::<Vec<_>>().join(";"))
            }
            // OSC 777 ; notify ; title ; body
            [b"777", b"notify", rest @ ..] => self.notes.push(rest.iter().map(|p| s(p)).collect::<Vec<_>>().join(": ")),
            _ => {}
        }
    }
}

struct Inner {
    parser: vt100::Parser<Cb>,
    cols: u16,
    rows: u16,
    title: String,
    last_output: std::time::Instant,
    busy_since: Option<std::time::Instant>,
    quiet_reported: bool,
    last_attention: Option<std::time::Instant>,
    /// Last output that was the program working on its own (not echoing typing or redrawing after a resize).
    last_work: std::time::Instant,
    /// Output until this moment is a reaction to typing or a resize, so it is not "work".
    reactive_until: std::time::Instant,
    /// A command was entered (or the session was started with one) since the last "finished" alert.
    entered: bool,
}

pub struct Session {
    /// The attached client (phone or desktop window) whose window size the terminal follows: whoever typed or attached last.
    owner: std::sync::atomic::AtomicU64,
    pub id: u32,
    pub name: Mutex<String>,
    pub created: u64,
    pub pid: i32,
    pub command: Option<String>,
    master: Arc<AsyncFd<OwnedFd>>,
    inner: Mutex<Inner>,
    write_lock: tokio::sync::Mutex<()>,
    tx: broadcast::Sender<Bytes>,
    pub ended: watch::Sender<Option<i32>>,
    events: broadcast::Sender<Value>,
}

fn nz(v: u16) -> NonZeroU16 {
    NonZeroU16::new(v.max(1)).unwrap()
}

pub struct Spawn {
    pub name: String,
    pub cwd: Option<String>,
    pub command: Option<String>,
    pub shell: String,
    pub cols: u16,
    pub rows: u16,
    pub env: Vec<(String, String)>,
}

impl Session {
    pub fn spawn(id: u32, s: Spawn, events: broadcast::Sender<Value>) -> Result<Arc<Session>> {
        let ws = nix::pty::Winsize { ws_row: s.rows.max(2), ws_col: s.cols.max(10), ws_xpixel: 0, ws_ypixel: 0 };
        let pty = nix::pty::openpty(Some(&ws), None).context("couldn't open a terminal")?;
        let home = std::env::var("HOME").unwrap_or_else(|_| "/".into());
        let cwd = s.cwd.clone().filter(|c| std::path::Path::new(c).is_dir()).unwrap_or(home);
        let shell_name = std::path::Path::new(&s.shell).file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "sh".into());
        let mut cmd = Command::new(&s.shell);
        match &s.command {
            Some(c) => {
                cmd.arg("-l").arg("-c").arg(c);
            }
            None => {
                cmd.arg0(format!("-{shell_name}"));
            }
        }
        for (k, v) in &s.env {
            cmd.env(k, v);
        }
        cmd.current_dir(&cwd)
            .env("TERM", "xterm-256color")
            .env("COLORTERM", "truecolor")
            .env("TERM_PROGRAM", "Ripple")
            .env("TERM_PROGRAM_VERSION", crate::VERSION)
            .env("RIPPLE_SESSION", id.to_string())
            .env_remove("TMUX")
            .env_remove("RIPPLE_DAEMON_CHILD")
            .stdin(Stdio::from(pty.slave.try_clone()?))
            .stdout(Stdio::from(pty.slave.try_clone()?))
            .stderr(Stdio::from(pty.slave.try_clone()?));
        unsafe {
            cmd.pre_exec(|| {
                if libc::setsid() < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                if libc::ioctl(0, libc::TIOCSCTTY as _, 0) < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                // Undo signal settings a daemon may have inherited.
                for sig in [libc::SIGINT, libc::SIGQUIT, libc::SIGTSTP, libc::SIGTTIN, libc::SIGTTOU, libc::SIGCHLD, libc::SIGPIPE, libc::SIGHUP] {
                    libc::signal(sig, libc::SIG_DFL);
                }
                Ok(())
            });
        }
        let mut child = cmd.spawn().with_context(|| format!("couldn't start {}", s.shell))?;
        drop(pty.slave);
        let fd = pty.master.as_raw_fd();
        unsafe {
            let flags = libc::fcntl(fd, libc::F_GETFL);
            libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
        let master = Arc::new(AsyncFd::new(pty.master)?);
        let (tx, _) = broadcast::channel(2048);
        let (ended, _) = watch::channel(None);
        let has_command = s.command.is_some();
        let sess = Arc::new(Session {
            owner: std::sync::atomic::AtomicU64::new(0),
            id,
            name: Mutex::new(s.name),
            created: crate::util::now_secs(),
            pid: child.id() as i32,
            command: s.command,
            master,
            inner: Mutex::new(Inner {
                parser: vt100::Parser::new_with_callbacks(nz(ws.ws_row), nz(ws.ws_col), HISTORY_LINES, Cb::default()),
                cols: ws.ws_col,
                rows: ws.ws_row,
                title: String::new(),
                last_output: std::time::Instant::now(),
                busy_since: None,
                quiet_reported: true,
                last_attention: None,
                last_work: std::time::Instant::now(),
                // The shell's own start-up output is not work either.
                reactive_until: std::time::Instant::now() + std::time::Duration::from_secs(3),
                entered: has_command,
            }),
            write_lock: tokio::sync::Mutex::new(()),
            tx,
            ended,
            events,
        });
        // Reader: PTY → screen model → everyone attached.
        let s2 = sess.clone();
        tokio::spawn(async move {
            let mut buf = vec![0u8; 64 * 1024];
            loop {
                let Ok(mut guard) = s2.master.readable().await else { break };
                let r = guard.try_io(|fd| {
                    let n = unsafe { libc::read(fd.get_ref().as_raw_fd(), buf.as_mut_ptr() as *mut _, buf.len()) };
                    if n < 0 { Err(std::io::Error::last_os_error()) } else { Ok(n as usize) }
                });
                match r {
                    Ok(Ok(0)) => break,
                    Ok(Ok(n)) => {
                        let answers = s2.on_output(&buf[..n]);
                        if !answers.is_empty() {
                            let _ = s2.write(&answers).await;
                        }
                    }
                    Ok(Err(e)) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                    Ok(Err(_)) => break, // EIO: the program and everything on the terminal exited
                    Err(_would_block) => continue,
                }
            }
            // A signal or a wait error is not a successful command.
            let code = tokio::task::spawn_blocking(move || {
                child.wait().map(|s| s.code().unwrap_or_else(|| 128 + s.signal().unwrap_or(0))).unwrap_or(-1)
            }).await.unwrap_or(-1);
            s2.ended.send_replace(Some(code));
        });
        Ok(sess)
    }

    /// Handles program output; returns answers to terminal queries when nobody is attached to
    /// answer them (programs such as fish wait for an answer before showing a prompt).
    fn on_output(&self, data: &[u8]) -> Vec<u8> {
        let mut attention: Vec<String> = vec![];
        let answers = if self.tx.receiver_count() == 0 { self.answer_queries(data) } else { vec![] };
        {
            let mut inner = self.inner.lock().unwrap();
            inner.parser.process(data);
            let now = std::time::Instant::now();
            // Echoed typing and the redraw after a resize are not the program working: they never start or extend a busy stretch.
            if now >= inner.reactive_until {
                if now.duration_since(inner.last_work).as_secs() >= 3 {
                    inner.busy_since = Some(now);
                }
                inner.last_work = now;
            }
            inner.last_output = now;
            inner.quiet_reported = false;
            let cb = inner.parser.callbacks_mut();
            if let Some(t) = cb.title.take() {
                inner.title = t;
            }
            let cb = inner.parser.callbacks_mut();
            if cb.bell {
                cb.bell = false;
                attention.push(String::new());
            }
            attention.extend(cb.notes.drain(..));
            // Broadcast while holding the lock so attaching clients never miss or duplicate bytes.
            let _ = self.tx.send(Bytes::copy_from_slice(data));
            if !attention.is_empty() {
                let recent = inner.last_attention.map(|t| now.duration_since(t).as_secs() < 4).unwrap_or(false);
                if recent && attention.iter().all(|a| a.is_empty()) {
                    attention.clear();
                } else {
                    inner.last_attention = Some(now);
                }
            }
        }
        for text in attention {
            let _ = self.events.send(json!({"event": "attention", "session": self.id, "name": self.display_name(), "text": text, "kind": "bell"}));
        }
        answers
    }

    /// Replies a terminal would give to the common queries, like tmux does for detached sessions.
    fn answer_queries(&self, data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let has = |pat: &[u8]| data.windows(pat.len()).any(|w| w == pat);
        // Primary device attributes: a VT220-class terminal with colour.
        if has(b"\x1b[c") || has(b"\x1b[0c") {
            out.extend_from_slice(b"\x1b[?62;22c");
        }
        // Secondary device attributes.
        if has(b"\x1b[>c") || has(b"\x1b[>0c") {
            out.extend_from_slice(b"\x1b[>1;10;0c");
        }
        // Status report and cursor position.
        if has(b"\x1b[5n") {
            out.extend_from_slice(b"\x1b[0n");
        }
        if has(b"\x1b[6n") {
            let (r, c) = {
                let inner = self.inner.lock().unwrap();
                inner.parser.screen().cursor_position()
            };
            out.extend_from_slice(format!("\x1b[{};{}R", r + 1, c + 1).as_bytes());
        }
        // Background / foreground colour queries (answer with a light terminal's colours).
        if has(b"\x1b]11;?") {
            out.extend_from_slice(b"\x1b]11;rgb:ffff/ffff/ffff\x1b\\");
        }
        if has(b"\x1b]10;?") {
            out.extend_from_slice(b"\x1b]10;rgb:1c1c/1c1c/1c1c\x1b\\");
        }
        out
    }

    /// Called every few seconds: reports a session that was busy for a while and has gone quiet
    /// (a long command finished, or a program is waiting for an answer).
    pub fn check_quiet(&self, quiet_secs: u64) {
        if quiet_secs == 0 {
            return;
        }
        let fire = {
            let mut inner = self.inner.lock().unwrap();
            let now = std::time::Instant::now();
            let quiet = now.duration_since(inner.last_output).as_secs() >= quiet_secs;
            let worked = inner.busy_since.map(|b| inner.last_work.duration_since(b).as_secs() >= 8).unwrap_or(false);
            // Only after something was actually run: an idle shell that redrew its prompt a few times is not "finished".
            if quiet && worked && inner.entered && !inner.quiet_reported {
                inner.quiet_reported = true;
                inner.busy_since = None;
                inner.entered = false;
                true
            } else {
                false
            }
        };
        if fire {
            let _ = self.events.send(json!({"event": "attention", "session": self.id, "name": self.display_name(), "text": "Finished or waiting for you", "kind": "quiet"}));
        }
    }

    pub fn subscribe_with_snapshot(&self, history: bool) -> (Vec<u8>, broadcast::Receiver<Bytes>) {
        let mut inner = self.inner.lock().unwrap();
        let snap = Self::snapshot_locked(&mut inner, history);
        (snap, self.tx.subscribe())
    }

    pub fn snapshot(&self, history: bool) -> Vec<u8> {
        let mut inner = self.inner.lock().unwrap();
        Self::snapshot_locked(&mut inner, history)
    }

    fn snapshot_locked(inner: &mut Inner, history: bool) -> Vec<u8> {
        let rows = inner.rows as usize;
        let cols = inner.cols;
        let screen = inner.parser.screen_mut();
        let mut s = String::from("\x1b[0m\x1b[?1049l\x1b[H\x1b[2J\x1b[3J");
        if history && !screen.alternate_screen() {
            let total = screen.scrollback_len();
            if total > 0 {
                let mut k = total;
                while k > 0 {
                    screen.set_scrollback(k);
                    let take = rows.min(k);
                    for row in screen.rows_formatted(0, cols).take(take) {
                        s.push_str(&row);
                        s.push_str("\x1b[0m\r\n");
                    }
                    k -= take;
                }
                screen.set_scrollback(0);
                for _ in 0..rows {
                    s.push_str("\r\n");
                }
            }
        }
        if screen.alternate_screen() {
            s.push_str("\x1b[?1049h");
        }
        s.push_str(&screen.state_formatted());
        s.into_bytes()
    }

    pub async fn write(&self, data: &[u8]) -> Result<()> {
        {
            let mut inner = self.inner.lock().unwrap();
            inner.reactive_until = std::time::Instant::now() + std::time::Duration::from_millis(1500);
            if data.contains(&b'\r') || data.contains(&b'\n') {
                inner.entered = true;
            }
        }
        let _g = self.write_lock.lock().await;
        let mut off = 0;
        while off < data.len() {
            let mut guard = self.master.writable().await?;
            match guard.try_io(|fd| {
                let n = unsafe { libc::write(fd.get_ref().as_raw_fd(), data[off..].as_ptr() as *const _, data.len() - off) };
                if n < 0 { Err(std::io::Error::last_os_error()) } else { Ok(n as usize) }
            }) {
                Ok(Ok(n)) => off += n,
                Ok(Err(e)) if e.kind() == std::io::ErrorKind::Interrupted => {}
                Ok(Err(e)) => return Err(e.into()),
                Err(_) => {}
            }
        }
        Ok(())
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        let cols = cols.clamp(10, 1000);
        let rows = rows.clamp(2, 500);
        let mut inner = self.inner.lock().unwrap();
        if inner.cols == cols && inner.rows == rows {
            return;
        }
        let ws = libc::winsize { ws_row: rows, ws_col: cols, ws_xpixel: 0, ws_ypixel: 0 };
        unsafe {
            libc::ioctl(self.master.get_ref().as_raw_fd(), libc::TIOCSWINSZ, &ws);
        }
        inner.parser.set_size(nz(rows), nz(cols));
        // Full-screen programs and fancy prompts redraw for a moment after a resize.
        inner.reactive_until = std::time::Instant::now() + std::time::Duration::from_millis(2000);
        inner.cols = cols;
        inner.rows = rows;
    }

    /// Plain text of what the screen shows now (for previews).
    pub fn peek(&self) -> String {
        let inner = self.inner.lock().unwrap();
        inner.parser.screen().contents()
    }

    pub fn owner(&self) -> u64 {
        self.owner.load(std::sync::atomic::Ordering::Relaxed)
    }

    pub fn set_owner(&self, client: u64) {
        self.owner.store(client, std::sync::atomic::Ordering::Relaxed);
    }

    pub fn size(&self) -> (u16, u16) {
        let inner = self.inner.lock().unwrap();
        (inner.cols, inner.rows)
    }

    /// Name of the program in the foreground (e.g. "btop"), and its working directory.
    pub fn foreground(&self) -> (String, String) {
        let pgrp = unsafe { libc::tcgetpgrp(self.master.get_ref().as_raw_fd()) };
        let mut pid = if pgrp > 0 { pgrp } else { self.pid };
        // `fish -c prog` (no job control) keeps prog in the shell's process group, so step down
        // from a shell to its newest child to name the program that is really running.
        for _ in 0..4 {
            let comm = std::fs::read_to_string(format!("/proc/{pid}/comm")).unwrap_or_default();
            if !matches!(comm.trim(), "fish" | "bash" | "zsh" | "sh" | "dash" | "mksh" | "ksh" | "tcsh" | "nu") { break; }
            let kids = std::fs::read_to_string(format!("/proc/{pid}/task/{pid}/children")).unwrap_or_default();
            match kids.split_whitespace().filter_map(|k| k.parse::<i32>().ok()).last() {
                Some(child) => pid = child,
                None => break,
            }
        }
        let comm = std::fs::read_to_string(format!("/proc/{pid}/comm")).map(|s| s.trim().to_string()).unwrap_or_default();
        let cwd = std::fs::read_link(format!("/proc/{pid}/cwd"))
            .or_else(|_| std::fs::read_link(format!("/proc/{}/cwd", self.pid)))
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        (comm, cwd)
    }

    pub fn display_name(&self) -> String {
        self.name.lock().unwrap().clone()
    }

    pub fn summary(&self) -> Value {
        let (program, cwd) = self.foreground();
        let (cols, rows, title, idle) = {
            let inner = self.inner.lock().unwrap();
            (inner.cols, inner.rows, inner.title.clone(), inner.last_output.elapsed().as_secs())
        };
        json!({
            "id": self.id, "name": self.display_name(), "title": title, "program": program, "cwd": cwd,
            "cols": cols, "rows": rows, "created": self.created, "idle_secs": idle, "receivers": self.tx.receiver_count(),
        })
    }

    pub fn kill(&self) {
        unsafe {
            libc::kill(-self.pid, libc::SIGHUP);
            libc::kill(self.pid, libc::SIGHUP);
        }
        let pid = self.pid;
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            unsafe {
                if libc::kill(pid, 0) == 0 {
                    libc::kill(-pid, libc::SIGKILL);
                    libc::kill(pid, libc::SIGKILL);
                }
            }
        });
    }
}
