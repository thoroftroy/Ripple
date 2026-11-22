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
    let mut tasks = JoinSet::new();
    for (index, addr) in addrs.iter().cloned().enumerate() {
        tasks.spawn(async move { (index, net::resolve(&addr, port).await) });
    }
    let mut resolved = vec![];
    while let Some(Ok(item)) = tasks.join_next().await {
        resolved.push(item);
    }
    resolved.sort_by_key(|(index, _)| *index);
    let mut out: Vec<SocketAddr> = vec![];
    for (_, addresses) in resolved {
        for s in addresses {
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
        let c = ep.connect_with(cfg, a, "ripple").map_err(|e| anyhow!("{a}: {e}"))?.await.map_err(|e| anyhow!("{a}: {e}"))?;
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
    let mut errs: Vec<String> = vec![];
    loop {
        let waiting_for_more = more.is_some();
        if set.is_empty() && !waiting_for_more {
            break;
        }
        tokio::select! {
            r = set.join_next(), if !set.is_empty() => match r {
                Some(Ok(Ok(x))) => return Ok(x),
                Some(Ok(Err(e))) => { errs.push(e.to_string()); last = Some(e) },
                _ => {}
            },
            m = async { match more.as_mut() { Some(rx) => rx.recv().await, None => std::future::pending().await } } => match m {
                Some(list) => for a in list {
                    if !tried.contains(&a) { tried.push(a); }
                    for d in PUNCH_RETRIES { spawn_attempt(&mut set, ep, cfg, a, Duration::from_millis(d)); }
                },
                None => more = None,
            },
            _ = tokio::time::sleep_until(deadline) => break,
        }
    }
    if errs.len() > 1 {
        return Err(anyhow!("{}", errs.join("; ")));
    }
    Err(last.unwrap_or_else(|| anyhow!("no answer ({} addresses tried)", tried.len())))
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

/// What the helpers running beside a connection attempt report back.
enum Heard {
    /// Addresses from the local-network check.
    Local(Vec<SocketAddr>),
    /// The computer answered the doorbell and is sending packets our way now.
    Answer(Vec<SocketAddr>),
    BellFailed(String),
}

/// Start each address as soon as it resolves. A slow DNS server must not
/// delay numeric addresses, local discovery, or the doorbell.
async fn resolve_independently<F, Fut>(addrs: Vec<String>, port: u16, tx: tokio::sync::mpsc::Sender<Heard>, resolve: F)
where
    F: Fn(String, u16) -> Fut + Clone + Send + 'static,
    Fut: std::future::Future<Output = Vec<SocketAddr>> + Send + 'static,
{
    let mut tasks = JoinSet::new();
    for addr in addrs {
        let resolve = resolve.clone();
        tasks.spawn(async move { resolve(addr, port).await });
    }
    while let Some(result) = tasks.join_next().await {
        if let Ok(addresses) = result {
            let addresses = addresses.into_iter().map(net::canonical).collect::<Vec<_>>();
            if !addresses.is_empty() && tx.send(Heard::Local(addresses)).await.is_err() {
                break;
            }
        }
    }
}

/// Fresh attempts to an address the computer is punching towards, lined up with its own packets (sent at 0, 0.3, 0.9 and 2 s).
const PUNCH_RETRIES: [u64; 5] = [0, 150, 450, 1050, 2200];

/// Connects to a paired computer. `status` receives short progress messages for the UI.
///
/// Everything runs at once so a connection away from home doesn't wait for each step to give up:
/// direct attempts start straight away, the local-network check runs beside them, and if nothing
/// has answered after a moment the doorbell is rung while the direct attempts keep going. When the
/// computer answers the doorbell, new attempts are fired in step with the packets it sends.
pub async fn connect(id: &Identity, info: &ComputerInfo, extra: &[String], mut status: impl FnMut(String)) -> Result<Client> {
    let ep = net::endpoint(0, None)?;
    let cfg = tls::client_config(id, Some(info.fp.clone()))?;
    let mut list: Vec<String> = info.addrs.clone();
    list.extend(extra.iter().cloned());
    status(format!("Connecting to {}…", info.name));
    // Testing aid: behave as if no direct path exists, so only the doorbell's answer is used.
    let force_bell = std::env::var_os("RIPPLE_FORCE_DOORBELL").is_some();
    let has_direct_addresses = !force_bell && !list.is_empty();

    let mut set: JoinSet<Result<(quinn::Connection, SocketAddr, bool)>> = JoinSet::new();
    let attempt = |set: &mut JoinSet<Result<(quinn::Connection, SocketAddr, bool)>>, a: SocketAddr, delay: Duration, via_bell: bool| {
        let ep = ep.clone();
        let cfg = cfg.clone();
        set.spawn(async move {
            tokio::time::sleep(delay).await;
            let c = ep.connect_with(cfg, a, "ripple").map_err(|e| anyhow!("{a}: {e}"))?.await.map_err(|e| anyhow!("{a}: {e}"))?;
            Ok((c, a, via_bell))
        });
    };
    let mut tried: Vec<SocketAddr> = vec![];
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Heard>(8);
    let mut helpers: Vec<tokio::task::JoinHandle<()>> = vec![];
    if has_direct_addresses {
        helpers.push(tokio::spawn(resolve_independently(list, info.port, tx.clone(), |addr, port| async move {
            net::resolve(&addr, port).await
        })));
    }
    // Testing aid: skip the local-network check, as when away from home.
    let skip_lan = std::env::var_os("RIPPLE_SKIP_LAN").is_some();
    if !force_bell && !skip_lan {
        // The computer may have a new address at home.
        let tx = tx.clone();
        let fp = info.fp.clone();
        helpers.push(tokio::spawn(async move {
            let found = net::discover_for(Duration::from_millis(900), Some(&fp)).await;
            let mut out = vec![];
            for f in found.iter().filter(|f| f.fp == fp) {
                out.extend(net::resolve(&f.addr, f.port).await.into_iter().map(net::canonical));
            }
            if !out.is_empty() {
                let _ = tx.send(Heard::Local(out)).await;
            }
        }));
    }
    let my_fp = id.fingerprint();
    let my_port = ep.local_addr()?.port();
    let mine: Vec<String> = net::local_ips().into_iter().map(|ip| net::fmt_addr(ip, my_port)).collect();
    let nonce = hex::encode(crate::util::random_bytes(8));
    let bell = info.doorbell.clone();
    // At home a direct path answers within a fraction of a second; past this, start the doorbell too.
    let bell_at = tokio::time::Instant::now() + if has_direct_addresses { Duration::from_millis(700) } else { Duration::ZERO };
    let mut bell_rung = false;
    let mut tx = Some(tx);
    if bell.is_none() {
        tx = None;
    }
    let started = tokio::time::Instant::now();
    let mut deadline = started + if bell.is_some() { Duration::from_secs(20) } else { Duration::from_secs(5) };
    let mut errs: Vec<String> = vec![];
    let mut rx_open = true;
    let outcome: Result<(quinn::Connection, SocketAddr, bool)> = loop {
        if set.is_empty() && !rx_open && (bell_rung || bell.is_none()) {
            break Err(anyhow!("{}", if errs.is_empty() { "no answer".to_string() } else { errs.join("; ") }));
        }
        tokio::select! {
            r = set.join_next(), if !set.is_empty() => match r {
                Some(Ok(Ok(x))) => break Ok(x),
                Some(Ok(Err(e))) => {
                    let m = explain(&e);
                    if m != e.to_string() {
                        break Err(e);
                    }
                    if !errs.contains(&e.to_string()) {
                        errs.push(e.to_string());
                    }
                }
                _ => {}
            },
            m = rx.recv(), if rx_open => match m {
                Some(Heard::Local(list)) => for a in list {
                    if !tried.contains(&a) {
                        tried.push(a);
                        attempt(&mut set, a, Duration::ZERO, false);
                    }
                },
                Some(Heard::Answer(list)) => {
                    status(format!("{} answered. Opening a path…", info.name));
                    for a in list {
                        if !tried.contains(&a) {
                            tried.push(a);
                        }
                        for d in PUNCH_RETRIES {
                            attempt(&mut set, a, Duration::from_millis(d), true);
                        }
                    }
                }
                Some(Heard::BellFailed(e)) => errs.push(e),
                None => rx_open = false,
            },
            _ = tokio::time::sleep_until(bell_at), if !bell_rung && bell.is_some() => {
                bell_rung = true;
                status("Not at home: ringing the doorbell…".into());
                let b = bell.clone().unwrap();
                let tx = tx.take().unwrap();
                let knock = json!({"t": "knock", "from": my_fp, "addrs": mine, "nonce": nonce, "ts": crate::util::now_secs()});
                let nonce = nonce.clone();
                let port = info.port;
                deadline = deadline.max(tokio::time::Instant::now() + Duration::from_secs(15));
                helpers.push(tokio::spawn(async move {
                    let since = crate::util::now_secs().saturating_sub(2);
                    let b2 = b.clone();
                    match tokio::task::spawn_blocking(move || b2.ring(&knock)).await {
                        Ok(Ok(())) => {}
                        Ok(Err(e)) => { let _ = tx.send(Heard::BellFailed(e.to_string())).await; return; }
                        Err(e) => { let _ = tx.send(Heard::BellFailed(e.to_string())).await; return; }
                    }
                    for _ in 0..30 {
                        let b = b.clone();
                        if let Ok(Ok(msgs)) = tokio::task::spawn_blocking(move || b.poll(since)).await {
                            for m in msgs {
                                if m["t"] == "answer" && m["nonce"] == nonce.as_str() {
                                    let list: Vec<String> = m["addrs"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
                                    let _ = tx.send(Heard::Answer(resolve_all(&list, port).await)).await;
                                    return;
                                }
                            }
                        }
                        tokio::time::sleep(Duration::from_millis(350)).await;
                    }
                }));
            },
            _ = tokio::time::sleep_until(deadline) => {
                break Err(anyhow!("{}", if errs.is_empty() { format!("no answer ({} addresses tried)", tried.len()) } else { errs.join("; ") }));
            }
        }
    };
    helpers.iter().for_each(|h| h.abort());
    set.abort_all();
    match outcome {
        Ok((conn, a, via_bell)) => {
            let via = if via_bell { format!("{} via doorbell", describe(&a)) } else { describe(&a) };
            Ok(Client { endpoint: ep, conn, via, remote: a })
        }
        Err(e) => {
            let msg = explain(&e);
            let detail = e.to_string();
            if msg != detail {
                bail!(msg);
            }
            match &bell {
                None => bail!("Couldn't reach {}. Is it on and running Ripple?", info.name),
                Some(b) => {
                    // Tell the computer why, over the same encrypted doorbell, so the cause can be read there.
                    let report = json!({"t": "diag", "from": my_fp, "nonce": nonce, "tried": mine, "errors": detail, "ts": crate::util::now_secs()});
                    let b2 = b.clone();
                    let _ = tokio::task::spawn_blocking(move || b2.ring(&report)).await;
                    bail!("Couldn't reach {}. It may be off or asleep, or its network blocks the path. Details: {detail}", info.name)
                }
            }
        }
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
            // Tell the computer why, over the same encrypted doorbell, so the cause can be read there.
            let detail = e.to_string();
            let report = json!({"t": "diag", "from": my_fp, "nonce": nonce, "tried": mine, "errors": detail, "ts": crate::util::now_secs()});
            let b2 = bell.clone();
            let _ = tokio::task::spawn_blocking(move || b2.ring(&report)).await;
            if msg != detail {
                bail!(msg)
            }
            bail!("Couldn't reach {name}. It may be off or asleep, or its network blocks the path. Details: {detail}")
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_slow_dns_name_does_not_delay_a_ready_address() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let task = tokio::spawn(resolve_independently(vec!["slow".into(), "fast".into()], 42, tx, |name, _| async move {
            if name == "slow" { tokio::time::sleep(Duration::from_millis(300)).await; }
            vec![if name == "slow" { "127.0.0.1:1" } else { "127.0.0.1:2" }.parse().unwrap()]
        }));
        let first = tokio::time::timeout(Duration::from_millis(150), rx.recv()).await.unwrap().unwrap();
        match first {
            Heard::Local(addresses) => assert_eq!(addresses[0].port(), 2),
            _ => panic!("unexpected result"),
        }
        task.await.unwrap();
    }

    #[tokio::test]
    async fn resolving_numeric_addresses_deduplicates_without_reordering() {
        let addresses = resolve_all(&["127.0.0.1:1".into(), "[::ffff:127.0.0.1]:1".into(), "127.0.0.1:2".into()], 9).await;
        assert_eq!(addresses.iter().map(SocketAddr::port).collect::<Vec<_>>(), vec![1, 2]);
    }
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
