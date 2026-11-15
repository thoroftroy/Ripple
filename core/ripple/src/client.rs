//! Connecting to a computer: tries every known path at once and keeps the first that works.
//! Home network → direct internet (IPv6 / mapped port) → doorbell-assisted hole punching.

use crate::identity::Identity;
use crate::net::{self, ComputerInfo};
use crate::proto::{self, Frame};
use crate::tls;
use anyhow::{Result, anyhow, bail};
use serde_json::{Value, json};
use std::net::SocketAddr;
use std::time::Duration;
use tokio::task::JoinSet;

pub struct Client {
    pub endpoint: quinn::Endpoint,
    pub conn: quinn::Connection,
    /// Human description of the path in use ("home network", "internet").
    pub via: String,
    pub remote: SocketAddr,
}

fn describe(a: &SocketAddr) -> String {
    match net::canonical(*a).ip() {
        std::net::IpAddr::V4(v4) if v4.is_private() => "home network".into(),
        std::net::IpAddr::V4(v4) if v4.octets()[0] == 100 && (64..128).contains(&v4.octets()[1]) => "private network (VPN)".into(),
        std::net::IpAddr::V4(_) => "internet".into(),
        std::net::IpAddr::V6(v6) if net::is_global_v6(&v6) => "internet (IPv6)".into(),
        std::net::IpAddr::V6(_) => "home network".into(),
    }
}

async fn resolve_all(addrs: &[String], port: u16) -> Vec<SocketAddr> {
    let mut out: Vec<SocketAddr> = vec![];
    for a in addrs {
        for s in net::resolve(a, port).await {
            let s = net::canonical(s);
            if !out.contains(&s) {
                out.push(s);
            }
        }
    }
    out
}

type Attempt = Result<(quinn::Connection, SocketAddr)>;

fn spawn_attempt(set: &mut JoinSet<Attempt>, ep: &quinn::Endpoint, cfg: &quinn::ClientConfig, a: SocketAddr, delay: Duration) {
    let ep = ep.clone();
    let cfg = cfg.clone();
    set.spawn(async move {
        tokio::time::sleep(delay).await;
        let c = ep.connect_with(cfg, a, "ripple")?.await?;
        Ok((c, a))
    });
}

/// Races connection attempts. New addresses can arrive on `more` while racing (doorbell answers).
async fn race(
    ep: &quinn::Endpoint,
    cfg: &quinn::ClientConfig,
    addrs: Vec<SocketAddr>,
    mut more: Option<tokio::sync::mpsc::Receiver<Vec<SocketAddr>>>,
    limit: Duration,
) -> Result<(quinn::Connection, SocketAddr)> {
    let mut set = JoinSet::new();
    let mut tried: Vec<SocketAddr> = vec![];
    for (i, a) in addrs.into_iter().enumerate() {
        tried.push(a);
        spawn_attempt(&mut set, ep, cfg, a, Duration::from_millis(120 * i as u64));
    }
    let deadline = tokio::time::Instant::now() + limit;
    let mut last: Option<anyhow::Error> = None;
    loop {
        let waiting_for_more = more.is_some();
        if set.is_empty() && !waiting_for_more {
            break;
        }
        tokio::select! {
            r = set.join_next(), if !set.is_empty() => match r {
                Some(Ok(Ok(x))) => return Ok(x),
                Some(Ok(Err(e))) => last = Some(e),
                _ => {}
            },
            m = async { match more.as_mut() { Some(rx) => rx.recv().await, None => std::future::pending().await } } => match m {
                Some(list) => for a in list {
                    if !tried.contains(&a) { tried.push(a); spawn_attempt(&mut set, ep, cfg, a, Duration::ZERO); }
                },
                None => more = None,
            },
            _ = tokio::time::sleep_until(deadline) => break,
        }
    }
    Err(last.unwrap_or_else(|| anyhow!("no answer")))
}

fn explain(e: &anyhow::Error) -> String {
    let s = e.to_string();
    if s.contains("identity doesn't match") {
        "This computer's identity changed. If you reinstalled Ripple there, pair again.".into()
    } else if s.contains("not paired") || s.contains("forgotten") {
        "The computer doesn't recognise this phone any more. Pair again.".into()
    } else {
        s
    }
}

/// Connects to a paired computer. `status` receives short progress messages for the UI.
pub async fn connect(id: &Identity, info: &ComputerInfo, extra: &[String], mut status: impl FnMut(String)) -> Result<Client> {
    let ep = net::endpoint(0, None)?;
    let cfg = tls::client_config(id, Some(info.fp.clone()))?;
    let mut list: Vec<String> = info.addrs.clone();
    list.extend(extra.iter().cloned());
    status(format!("Looking for {}…", info.name));
    let mut addrs = resolve_all(&list, info.port).await;
    // Quick local-network check too: the computer may have a new address at home.
    let found = net::discover(Duration::from_millis(900)).await;
    for f in found.iter().filter(|f| f.fp == info.fp) {
        for s in net::resolve(&f.addr, f.port).await {
            if !addrs.contains(&s) {
                addrs.insert(0, s);
            }
        }
    }
    // Testing aid: behave as if no direct path exists, so only the doorbell's answer is used.
    let force_bell = std::env::var_os("RIPPLE_FORCE_DOORBELL").is_some();
    if force_bell {
        addrs.clear();
    }
    if !addrs.is_empty() {
        status(format!("Connecting to {}…", info.name));
        match race(&ep, &cfg, addrs.clone(), None, Duration::from_secs(4)).await {
            Ok((conn, a)) => return Ok(Client { endpoint: ep, conn, via: describe(&a), remote: a }),
            Err(e) => {
                let msg = explain(&e);
                if msg != e.to_string() {
                    bail!(msg);
                }
            }
        }
    }
    let Some(bell) = info.doorbell.clone() else {
        bail!("Couldn't reach {}. Is it on and running Ripple?", info.name);
    };
    status("Ringing the doorbell…".into());
    match knock_and_race(&ep, &cfg, &bell, &id.fingerprint(), None, addrs, info.port, &info.name, &mut status).await {
        Ok((conn, a)) => Ok(Client { endpoint: ep, conn, via: format!("{} via doorbell", describe(&a)), remote: a }),
        Err(e) => Err(e),
    }
}

/// Rings the doorbell with our addresses, then races connections while the computer opens a path.
/// `pair_code` lets a not-yet-paired phone (that scanned the QR code) ring too.
#[allow(clippy::too_many_arguments)]
async fn knock_and_race(
    ep: &quinn::Endpoint,
    cfg: &quinn::ClientConfig,
    bell: &crate::doorbell::DoorbellInfo,
    my_fp: &str,
    pair_code: Option<&str>,
    addrs: Vec<SocketAddr>,
    port: u16,
    name: &str,
    status: &mut impl FnMut(String),
) -> Result<(quinn::Connection, SocketAddr)> {
    let my_port = ep.local_addr()?.port();
    // Every address we have: the computer sends packets to each, which opens its firewall (and the
    // home router's) for our packets coming back. This also gets through a computer firewall at home.
    let mine: Vec<String> = net::local_ips().into_iter().map(|ip| net::fmt_addr(ip, my_port)).collect();
    let nonce = hex::encode(crate::util::random_bytes(8));
    let mut knock = json!({"t": "knock", "from": my_fp, "addrs": mine, "nonce": nonce, "ts": crate::util::now_secs()});
    if let Some(code) = pair_code {
        knock["pair_proof"] = json!(crate::util::hmac_hex(crate::util::normalise_code(code).as_bytes(), &[b"knock", my_fp.as_bytes()]));
    }
    let since = crate::util::now_secs().saturating_sub(2);
    {
        let bell = bell.clone();
        let knock = knock.clone();
        tokio::task::spawn_blocking(move || bell.ring(&knock)).await??;
    }
    let (tx, rx) = tokio::sync::mpsc::channel(4);
    let poller = {
        let bell = bell.clone();
        let nonce = nonce.clone();
        tokio::spawn(async move {
            for _ in 0..16 {
                let b = bell.clone();
                if let Ok(Ok(msgs)) = tokio::task::spawn_blocking(move || b.poll(since)).await {
                    for m in msgs {
                        if m["t"] == "answer" && m["nonce"] == nonce.as_str() {
                            let list: Vec<String> = m["addrs"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
                            let resolved = resolve_all(&list, port).await;
                            let _ = tx.send(resolved).await;
                            return;
                        }
                    }
                }
                tokio::time::sleep(Duration::from_millis(600)).await;
            }
        })
    };
    status(format!("Opening a path to {name}…"));
    tokio::time::sleep(Duration::from_millis(250)).await;
    let r = race(ep, cfg, addrs, Some(rx), Duration::from_secs(14)).await;
    poller.abort();
    match r {
        Ok(x) => Ok(x),
        Err(e) => {
            let msg = explain(&e);
            if msg != e.to_string() {
                bail!(msg)
            }
            bail!("Couldn't reach {name}. It may be off or asleep, or its network blocks the path.")
        }
    }
}

impl Client {
    /// Sends one request on a fresh stream and returns the reply.
    pub async fn request(&self, req: Value) -> Result<Value> {
        let (mut send, mut recv) = self.conn.open_bi().await?;
        proto::write_json(&mut send, &req).await?;
        let r = proto::read_json(&mut recv).await?;
        let _ = send.finish();
        proto::check(r)
    }

    /// Opens a stream for a long-lived operation (attach, watch, get, put); returns the first reply.
    pub async fn open(&self, req: Value) -> Result<(quinn::SendStream, quinn::RecvStream, Value)> {
        let (mut send, mut recv) = self.conn.open_bi().await?;
        proto::write_json(&mut send, &req).await?;
        let r = proto::check(proto::read_json(&mut recv).await?)?;
        Ok((send, recv, r))
    }
}

/// Pairs with a computer. `pin` is the fingerprint from a QR code (if scanned), `code` the pairing
/// code shown on the computer (if typed). Without either, the computer's owner must approve the
/// request there after checking `on_compare`'s code matches.
pub async fn pair(
    id: &Identity,
    addr: &str,
    pin: Option<String>,
    code: Option<String>,
    device: &str,
    bell: Option<crate::doorbell::DoorbellInfo>,
    on_compare: impl FnOnce(String),
) -> Result<ComputerInfo> {
    let ep = net::endpoint(0, None)?;
    let cfg = tls::client_config(id, pin.clone())?;
    let addrs = resolve_all(&addr.split(',').map(|s| s.trim().to_string()).collect::<Vec<_>>(), crate::DEFAULT_PORT).await;
    if addrs.is_empty() && bell.is_none() {
        bail!("Couldn't understand the address “{addr}”");
    }
    let direct = if addrs.is_empty() { Err(anyhow!("no address")) } else { race(&ep, &cfg, addrs.clone(), None, Duration::from_secs(3)).await };
    let conn = match (direct, &bell, &code) {
        (Ok((c, _)), _, _) => c,
        (Err(_), Some(b), Some(c)) => {
            let mut quiet = |_s: String| {};
            knock_and_race(&ep, &cfg, b, &id.fingerprint(), Some(c), addrs, crate::DEFAULT_PORT, "the computer", &mut quiet).await?.0
        }
        (Err(e), _, _) => bail!("Couldn't reach the computer ({}). Is Ripple running there, on the same network?", explain(&e)),
    };
    let server_fp = tls::peer_fingerprint(&conn).ok_or_else(|| anyhow!("the computer didn't identify itself"))?;
    if let Some(p) = &pin {
        if !crate::util::ct_eq(p, &server_fp) {
            bail!("This isn't the computer from the QR code");
        }
    }
    let my_fp = id.fingerprint();
    on_compare(crate::util::compare_code(&server_fp, &my_fp));
    let proof = code.map(|c| crate::util::hmac_hex(crate::util::normalise_code(&c).as_bytes(), &[server_fp.as_bytes(), my_fp.as_bytes()]));
    let (mut send, mut recv) = conn.open_bi().await?;
    proto::write_json(&mut send, &json!({"op": "pair", "device": device, "proof": proof})).await?;
    let reply = proto::read_json(&mut recv).await?;
    let reply = proto::check(reply)?;
    let info: ComputerInfo = serde_json::from_value(reply["computer"].clone())?;
    conn.close(0u32.into(), b"paired");
    ep.wait_idle().await;
    Ok(info)
}

/// Drains data frames from a stream into `on_data` until it ends; JSON frames go to `on_msg`.
pub async fn pump<R: tokio::io::AsyncRead + Unpin>(recv: &mut R, mut on_data: impl FnMut(Vec<u8>), mut on_msg: impl FnMut(Value)) -> Result<()> {
    while let Some(f) = proto::read_frame(recv).await? {
        match f {
            Frame::Data(d) => on_data(d),
            Frame::Json(v) => on_msg(v),
        }
    }
    Ok(())
}
