//! Request handling for every stream, local (Unix socket) or remote (QUIC).

use super::Daemon;
use crate::proto::{self, Frame};
use anyhow::{Result, anyhow, bail};
use serde_json::{Value, json};
use std::path::Path;
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{broadcast, mpsc};

#[derive(Clone)]
pub struct Peer {
    pub local: bool,
    pub fp: String,
    pub addr: String,
}

pub async fn serve_unix(d: Arc<Daemon>, path: &Path) -> Result<()> {
    if path.exists() {
        if tokio::net::UnixStream::connect(path).await.is_ok() {
            bail!("Ripple is already running");
        }
        let _ = std::fs::remove_file(path);
    }
    let l = tokio::net::UnixListener::bind(path)?;
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    tokio::spawn(async move {
        loop {
            let Ok((s, _)) = l.accept().await else { continue };
            // Only this user's programs may talk to the daemon.
            let same_user = s.peer_cred().map(|c| c.uid() == unsafe { libc::getuid() }).unwrap_or(false);
            if !same_user {
                continue;
            }
            let d = d.clone();
            tokio::spawn(async move {
                let (r, w) = s.into_split();
                let _ = handle(d, Peer { local: true, fp: String::new(), addr: "local".into() }, r, w).await;
            });
        }
    });
    Ok(())
}

pub async fn serve_quic(d: Arc<Daemon>, ep: quinn::Endpoint) {
    while let Some(inc) = ep.accept().await {
        let d = d.clone();
        tokio::spawn(async move {
            let Ok(conn) = inc.await else { return };
            let Some(fp) = crate::tls::peer_fingerprint(&conn) else { return };
            let addr = crate::net::canonical(conn.remote_address()).to_string();
            if d.is_trusted(&fp) {
                d.touch_device(&fp);
                let _ = d.events.send(json!({"event": "device", "fp": fp, "connected": true, "addr": addr}));
            }
            loop {
                let Ok((send, recv)) = conn.accept_bi().await else { break };
                let d = d.clone();
                let peer = Peer { local: false, fp: fp.clone(), addr: addr.clone() };
                tokio::spawn(async move {
                    let _ = handle(d, peer, recv, send).await;
                });
            }
            if d.is_trusted(&fp) {
                let _ = d.events.send(json!({"event": "device", "fp": fp, "connected": false}));
            }
        });
    }
}

fn err(msg: impl Into<String>) -> Value {
    json!({"error": msg.into()})
}

pub async fn handle<R, W>(d: Arc<Daemon>, peer: Peer, mut r: R, mut w: W) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let req = proto::read_json(&mut r).await?;
    let op = req["op"].as_str().unwrap_or("").to_string();
    let trusted = peer.local || d.is_trusted(&peer.fp);
    if !trusted && op != "pair" {
        proto::write_json(&mut w, &err("This phone isn't paired with this computer (or was forgotten). Pair again.")).await?;
        return Ok(());
    }
    let local_only = ["pair_code", "pair_answer", "devices", "forget", "config", "set_config", "offer", "notify"];
    if !peer.local && local_only.contains(&op.as_str()) {
        proto::write_json(&mut w, &err("Only allowed on the computer itself")).await?;
        return Ok(());
    }
    let reply: Value = match op.as_str() {
        "hello" | "info" => json!({"computer": d.info(), "sessions": d.list_sessions(), "doorbell": doorbell_status(&d)}),
        "sessions" => json!({"sessions": d.list_sessions()}),
        "new" => {
            let cols = req["cols"].as_u64().unwrap_or(80) as u16;
            let rows = req["rows"].as_u64().unwrap_or(24) as u16;
            match d.new_session(str_opt(&req, "name"), str_opt(&req, "cwd"), str_opt(&req, "command"), cols, rows) {
                Ok(s) => json!({"id": s.id, "session": s.summary()}),
                Err(e) => err(e.to_string()),
            }
        }
        "kill" => match d.session(req["id"].as_u64().unwrap_or(0) as u32) {
            Some(s) => {
                s.kill();
                json!({"ok": true})
            }
            None => err("That session has already ended"),
        },
        "rename" => match d.session(req["id"].as_u64().unwrap_or(0) as u32) {
            Some(s) => {
                *s.name.lock().unwrap() = req["name"].as_str().unwrap_or("Terminal").to_string();
                d.sessions_changed();
                json!({"ok": true})
            }
            None => err("That session has already ended"),
        },
        "peek" => match d.session(req["id"].as_u64().unwrap_or(0) as u32) {
            Some(s) => json!({"text": s.peek(), "session": s.summary()}),
            None => err("That session has ended"),
        },
        "input" => match d.session(req["id"].as_u64().unwrap_or(0) as u32) {
            Some(s) => {
                s.write(req["text"].as_str().unwrap_or("").as_bytes()).await?;
                json!({"ok": true})
            }
            None => err("That session has ended"),
        },
        "attach" => return attach(d, req, r, w).await,
        "watch" => return watch(d, peer, r, w).await,
        "pair" => pair(&d, &peer, &req).await,
        "pair_code" => {
            let code = d.new_code();
            let uri = d.pair_uri(&code);
            let qr = qrcode::QrCode::new(uri.as_bytes()).map_err(|e| anyhow!("{e}"))?;
            let text = qr.render::<qrcode::render::unicode::Dense1x2>().quiet_zone(true).build();
            let svg = qr.render::<qrcode::render::svg::Color>().min_dimensions(260, 260).build();
            let pretty = format!("{}-{}", &code[..5], &code[5..]);
            json!({"code": pretty, "uri": uri, "qr_text": text, "qr_svg": svg, "computer": d.info()})
        }
        "pair_answer" => {
            let id = req["request"].as_str().unwrap_or("");
            match d.pending.lock().unwrap().remove(id) {
                Some(p) => {
                    let _ = p.reply.send(req["allow"].as_bool().unwrap_or(false));
                    json!({"ok": true})
                }
                None => err("That request has expired"),
            }
        }
        "pending" => {
            let list: Vec<Value> = d.pending.lock().unwrap().iter().map(|(k, p)| json!({"request": k, "device": p.device, "compare": p.compare})).collect();
            json!({"pending": list})
        }
        "devices" => json!({"devices": d.devices.lock().unwrap().clone()}),
        "forget" => {
            let fp = req["fp"].as_str().unwrap_or("").to_string();
            d.devices.lock().unwrap().retain(|x| x.fp != fp);
            d.save_devices()?;
            let _ = d.events.send(json!({"event": "devices"}));
            json!({"ok": true})
        }
        "config" => json!({"config": d.cfg.read().unwrap().clone(), "socket": super::socket_path(), "fingerprint": d.id.fingerprint()}),
        "set_config" => set_config(&d, &req),
        "offer" => {
            let path = req["path"].as_str().unwrap_or("");
            match super::files::offer(&d, path).await {
                Ok(v) => v,
                Err(e) => err(e.to_string()),
            }
        }
        "notify" => {
            let _ = d.events.send(json!({"event": "attention", "session": req["session"].as_u64().unwrap_or(0), "name": req["title"].as_str().unwrap_or("Ripple"), "text": req["text"].as_str().unwrap_or(""), "kind": "message"}));
            json!({"ok": true})
        }
        "ls" | "stat" | "mkdir" | "rm" | "mv" | "home" | "du" => match super::files::simple(&d, &op, &req).await {
            Ok(v) => v,
            Err(e) => err(e.to_string()),
        },
        "get" => return super::files::get(req, r, w).await,
        "put" => return super::files::put(d, req, r, w).await,
        _ => err(format!("unknown request {op}")),
    };
    proto::write_json(&mut w, &reply).await?;
    Ok(())
}

fn doorbell_status(d: &Daemon) -> Value {
    let (ok, msg) = d.doorbell_state.lock().unwrap().clone();
    json!({"listening": ok, "message": msg, "enabled": d.cfg.read().unwrap().doorbell_enabled})
}

fn str_opt(v: &Value, k: &str) -> Option<String> {
    v[k].as_str().map(String::from).filter(|s| !s.is_empty())
}

fn set_config(d: &Arc<Daemon>, req: &Value) -> Value {
    {
        let mut c = d.cfg.write().unwrap();
        if let Some(v) = req["name"].as_str() {
            c.name = v.to_string();
        }
        if let Some(v) = req["shell"].as_str() {
            c.shell = v.to_string();
        }
        if let Some(v) = req["doorbell_enabled"].as_bool() {
            c.doorbell_enabled = v;
        }
        if let Some(v) = req["doorbell_server"].as_str() {
            c.doorbell.server = v.trim_end_matches('/').to_string();
        }
        if let Some(v) = req["upnp"].as_bool() {
            c.upnp = v;
        }
        if let Some(v) = req["notify_quiet_secs"].as_u64() {
            c.notify_quiet_secs = v;
        }
        if let Some(v) = req["shared_dir"].as_str() {
            c.shared_dir = v.to_string();
            let _ = std::fs::create_dir_all(v);
        }
        if let Some(v) = req["auto_update"].as_bool() {
            c.auto_update = v;
        }
        if let Some(v) = req["extra_addresses"].as_array() {
            c.extra_addresses = v.iter().filter_map(|x| x.as_str().map(String::from)).filter(|s| !s.trim().is_empty()).collect();
        }
        if let Some(v) = req["port"].as_u64() {
            c.port = v as u16;
        }
    }
    let _ = d.save_config();
    super::reach::restart_doorbell(d.clone());
    json!({"ok": true, "config": d.cfg.read().unwrap().clone()})
}

async fn pair(d: &Arc<Daemon>, peer: &Peer, req: &Value) -> Value {
    if peer.local {
        return err("Pair from the phone");
    }
    let device = req["device"].as_str().unwrap_or("Phone").chars().take(60).collect::<String>();
    let my_fp = d.id.fingerprint();
    let mut ok = false;
    if let Some(proof) = req["proof"].as_str() {
        let now = crate::util::now_secs();
        let codes: Vec<String> = d.codes.lock().unwrap().iter().filter(|(_, e)| *e > now).map(|(c, _)| c.clone()).collect();
        for c in &codes {
            let want = crate::util::hmac_hex(c.as_bytes(), &[my_fp.as_bytes(), peer.fp.as_bytes()]);
            if crate::util::ct_eq(&want, proof) {
                ok = true;
                d.codes.lock().unwrap().retain(|(x, _)| x != c);
            }
        }
        if !ok {
            return err("That pairing code is wrong or has expired. Show a new one on the computer.");
        }
    } else {
        // Ask the person at the computer.
        let (tx, rx) = tokio::sync::oneshot::channel();
        let rid = hex::encode(crate::util::random_bytes(6));
        let compare = crate::util::compare_code(&my_fp, &peer.fp);
        d.pending.lock().unwrap().insert(rid.clone(), super::PairRequest { device: device.clone(), fp: peer.fp.clone(), compare: compare.clone(), reply: tx });
        let _ = d.events.send(json!({"event": "pair_request", "request": rid, "device": device, "compare": compare, "addr": peer.addr}));
        let _ = std::process::Command::new("notify-send")
            .args(["-a", "Ripple", "-i", "utilities-terminal", &format!("{device} wants to connect"), &format!("Open Ripple to allow it. Code {compare}")])
            .spawn();
        ok = matches!(tokio::time::timeout(std::time::Duration::from_secs(180), rx).await, Ok(Ok(true)));
        d.pending.lock().unwrap().remove(&rid);
        let _ = d.events.send(json!({"event": "pair_done", "request": rid}));
        if !ok {
            return err("The computer didn't allow this phone");
        }
    }
    {
        let mut list = d.devices.lock().unwrap();
        list.retain(|x| x.fp != peer.fp);
        list.push(super::Device { fp: peer.fp.clone(), name: device, added: crate::util::now_secs(), last_seen: crate::util::now_secs() });
    }
    let _ = d.save_devices();
    let _ = d.events.send(json!({"event": "devices"}));
    json!({"ok": true, "computer": d.info()})
}

async fn watch<R, W>(d: Arc<Daemon>, peer: Peer, mut r: R, mut w: W) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let mut rx = d.events.subscribe();
    proto::write_json(&mut w, &json!({"ok": true, "sessions": d.list_sessions(), "computer": d.info()})).await?;
    if peer.local {
        let list: Vec<Value> = d.pending.lock().unwrap().iter().map(|(k, p)| json!({"event": "pair_request", "request": k, "device": p.device, "compare": p.compare})).collect();
        for ev in list {
            proto::write_json(&mut w, &ev).await?;
        }
    }
    let (closed_tx, mut closed) = tokio::sync::oneshot::channel::<()>();
    tokio::spawn(async move {
        while let Ok(Some(_)) = proto::read_frame(&mut r).await {}
        let _ = closed_tx.send(());
    });
    loop {
        tokio::select! {
            ev = rx.recv() => match ev {
                Ok(ev) => {
                    let private = ev["event"] == "pair_request" || ev["event"] == "pair_done" || ev["event"] == "devices";
                    if private && !peer.local { continue; }
                    proto::write_json(&mut w, &ev).await?;
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    proto::write_json(&mut w, &json!({"event": "sessions", "sessions": d.list_sessions()})).await?;
                }
                Err(_) => break,
            },
            _ = &mut closed => break,
        }
    }
    Ok(())
}

async fn attach<R, W>(d: Arc<Daemon>, req: Value, mut r: R, mut w: W) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let cols = req["cols"].as_u64().unwrap_or(80) as u16;
    let rows = req["rows"].as_u64().unwrap_or(24) as u16;
    let history = req["history"].as_bool().unwrap_or(true);
    let sess = match req["id"].as_u64() {
        Some(id) => match d.session(id as u32) {
            Some(s) => s,
            None => {
                proto::write_json(&mut w, &err("That session has ended")).await?;
                return Ok(());
            }
        },
        None => match d.new_session(str_opt(&req, "name"), str_opt(&req, "cwd"), str_opt(&req, "command"), cols, rows) {
            Ok(s) => s,
            Err(e) => {
                proto::write_json(&mut w, &err(e.to_string())).await?;
                return Ok(());
            }
        },
    };
    let mut mine = (cols, rows);
    if !req["keep_size"].as_bool().unwrap_or(false) {
        sess.resize(cols, rows);
    }
    let (snap, mut out) = sess.subscribe_with_snapshot(history);
    proto::write_json(&mut w, &json!({"ok": true, "id": sess.id, "session": sess.summary()})).await?;
    proto::write_data(&mut w, &snap).await?;
    d.sessions_changed();
    let (in_tx, mut input) = mpsc::channel::<Frame>(256);
    tokio::spawn(async move {
        while let Ok(Some(f)) = proto::read_frame(&mut r).await {
            if in_tx.send(f).await.is_err() {
                break;
            }
        }
    });
    let mut ended = sess.ended.subscribe();
    let result: Result<()> = async {
        loop {
            let finished: Option<i32> = *ended.borrow();
            if let Some(code) = finished {
                proto::write_json(&mut w, &json!({"ended": code})).await?;
                break;
            }
            tokio::select! {
                b = out.recv() => match b {
                    Ok(first) => {
                        // Coalesce bursts into one write.
                        let mut buf = first.to_vec();
                        while buf.len() < 256 * 1024 {
                            match out.try_recv() { Ok(more) => buf.extend_from_slice(&more), Err(_) => break }
                        }
                        proto::write_data(&mut w, &buf).await?;
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        let snap = sess.snapshot(false);
                        proto::write_data(&mut w, &snap).await?;
                    }
                    Err(_) => break,
                },
                f = input.recv() => match f {
                    Some(Frame::Data(bytes)) => {
                        if sess.size() != mine { sess.resize(mine.0, mine.1); }
                        sess.write(&bytes).await?;
                    }
                    Some(Frame::Json(v)) => {
                        if let Some(sz) = v.get("resize").and_then(|s| s.as_array()) {
                            if sz.len() == 2 {
                                mine = (sz[0].as_u64().unwrap_or(80) as u16, sz[1].as_u64().unwrap_or(24) as u16);
                                sess.resize(mine.0, mine.1);
                            }
                        } else if v["redraw"].as_bool() == Some(true) {
                            let snap = sess.snapshot(v["history"].as_bool().unwrap_or(false));
                            proto::write_data(&mut w, &snap).await?;
                        } else if v["detach"].as_bool() == Some(true) {
                            break;
                        }
                    }
                    None => break,
                },
                _ = ended.changed() => {}
            }
        }
        Ok(())
    }
    .await;
    drop(out);
    d.sessions_changed();
    result
}
