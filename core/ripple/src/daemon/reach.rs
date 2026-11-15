//! Making the computer reachable: the secure listener, answering local-network discovery,
//! asking the router for an open port (UPnP), and listening for doorbell knocks.

use super::Daemon;
use serde_json::{Value, json};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

static DOORBELL_STOP: Mutex<Option<Arc<AtomicBool>>> = Mutex::new(None);

pub async fn start(d: Arc<Daemon>) {
    let port = d.cfg.read().unwrap().port;
    match crate::tls::server_config(&d.id).and_then(|cfg| crate::net::endpoint(port, Some(cfg))) {
        Ok(ep) => {
            *d.endpoint.lock().unwrap() = Some(ep.clone());
            tokio::spawn(super::server::serve_quic(d.clone(), ep));
        }
        Err(e) => eprintln!("ripple: can't listen on UDP port {port}: {e}. Phones won't be able to connect."),
    }
    tokio::spawn(discovery(d.clone()));
    tokio::spawn(upnp(d.clone()));
    restart_doorbell(d);
}

async fn discovery(d: Arc<Daemon>) {
    // Separate IPv4 and IPv6 sockets: IPv4 broadcasts don't reach a dual-stack socket everywhere.
    let mk = |addr: SocketAddr| -> std::io::Result<tokio::net::UdpSocket> {
        use socket2::{Domain, Protocol, Socket, Type};
        let dom = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
        let s = Socket::new(dom, Type::DGRAM, Some(Protocol::UDP))?;
        if addr.is_ipv6() {
            s.set_only_v6(true)?;
        }
        s.set_reuse_address(true)?;
        s.set_broadcast(addr.is_ipv4())?;
        s.bind(&addr.into())?;
        s.set_nonblocking(true)?;
        tokio::net::UdpSocket::from_std(s.into())
    };
    let v4 = mk(SocketAddr::from(([0, 0, 0, 0], crate::DISCOVERY_PORT)));
    let v6 = mk(SocketAddr::from((std::net::Ipv6Addr::UNSPECIFIED, crate::DISCOVERY_PORT)));
    if let Err(e) = &v4 {
        eprintln!("ripple: local discovery unavailable: {e}");
    }
    for sock in [v4.ok(), v6.ok()].into_iter().flatten() {
        let d = d.clone();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let Ok((n, from)) = sock.recv_from(&mut buf).await else { continue };
                if &buf[..n] != crate::net::DISCOVER_MAGIC {
                    continue;
                }
                let (name, port) = {
                    let c = d.cfg.read().unwrap();
                    (c.name.clone(), c.port)
                };
                let reply = json!({"name": name, "fp": d.id.fingerprint(), "port": port, "version": crate::VERSION});
                let _ = sock.send_to(reply.to_string().as_bytes(), from).await;
            }
        });
    }
}

/// The local IPv4 address used to reach `dest`.
fn local_v4_towards(dest: IpAddr) -> Option<std::net::Ipv4Addr> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect(SocketAddr::new(dest, 9)).ok()?;
    match s.local_addr().ok()?.ip() {
        IpAddr::V4(v4) => Some(v4),
        _ => None,
    }
}

async fn upnp(d: Arc<Daemon>) {
    loop {
        let (enabled, port) = {
            let c = d.cfg.read().unwrap();
            (c.upnp, c.port)
        };
        let mut mapped = vec![];
        if enabled {
            let mut opts = igd_next::SearchOptions::default();
            opts.timeout = Some(Duration::from_secs(4));
            if let Ok(gw) = igd_next::aio::tokio::search_gateway(opts).await {
                if let Some(local) = local_v4_towards(gw.addr.ip()) {
                    let ok = gw
                        .add_port(igd_next::PortMappingProtocol::UDP, port, SocketAddr::new(IpAddr::V4(local), port), 3600, "Ripple")
                        .await
                        .is_ok();
                    if ok {
                        if let Ok(ext) = gw.get_external_ip().await {
                            // A router behind carrier-grade NAT reports a shared 100.64/10 or private
                            // address, which isn't reachable from the internet; don't advertise it.
                            let usable = match ext {
                                IpAddr::V4(v4) => !(v4.is_private() || (v4.octets()[0] == 100 && (64..128).contains(&v4.octets()[1]))),
                                IpAddr::V6(_) => true,
                            };
                            if usable {
                                mapped.push(crate::net::fmt_addr(ext, port));
                            }
                        }
                    }
                }
            }
        }
        *d.public_addrs.lock().unwrap() = mapped;
        tokio::time::sleep(Duration::from_secs(30 * 60)).await;
    }
}

pub fn restart_doorbell(d: Arc<Daemon>) {
    if let Some(old) = DOORBELL_STOP.lock().unwrap().take() {
        old.store(true, Ordering::Relaxed);
    }
    let (enabled, bell) = {
        let c = d.cfg.read().unwrap();
        (c.doorbell_enabled, c.doorbell.clone())
    };
    if !enabled {
        *d.doorbell_state.lock().unwrap() = (false, "Off".into());
        return;
    }
    let stop = Arc::new(AtomicBool::new(false));
    *DOORBELL_STOP.lock().unwrap() = Some(stop.clone());
    let rt = tokio::runtime::Handle::current();
    std::thread::spawn(move || {
        let d2 = d.clone();
        bell.listen(
            &stop,
            |msg| {
                let d = d.clone();
                rt.spawn(async move { on_knock(d, msg).await });
            },
            |ok, err| {
                *d2.doorbell_state.lock().unwrap() = (ok, err.unwrap_or_else(|| "Listening".into()));
            },
        );
    });
}

async fn on_knock(d: Arc<Daemon>, msg: Value) {
    if msg["t"] != "knock" {
        return;
    }
    let from = msg["from"].as_str().unwrap_or("");
    let pairing = msg["pair_proof"].as_str().map(|proof| {
        let now = crate::util::now_secs();
        let codes: Vec<String> = d.codes.lock().unwrap().iter().filter(|(_, e)| *e > now).map(|(c, _)| c.clone()).collect();
        codes.iter().any(|c| crate::util::ct_eq(&crate::util::hmac_hex(c.as_bytes(), &[b"knock", from.as_bytes()]), proof))
    });
    if !d.is_trusted(from) && pairing != Some(true) {
        return;
    }
    let ts = msg["ts"].as_u64().unwrap_or(0);
    let now = crate::util::now_secs();
    if ts + 180 < now || ts > now + 180 {
        return;
    }
    let nonce = msg["nonce"].as_str().unwrap_or("").to_string();
    {
        let mut seen = d.seen_nonces.lock().unwrap();
        if seen.contains(&nonce) {
            return;
        }
        seen.push(nonce.clone());
        if seen.len() > 200 {
            seen.remove(0);
        }
    }
    let Some(ep) = d.endpoint.lock().unwrap().clone() else { return };
    let targets: Vec<SocketAddr> = msg["addrs"].as_array().map(|a| a.iter().filter_map(|x| x.as_str()?.parse().ok()).collect()).unwrap_or_default();
    // Packets towards the phone open our router's firewall for its packets coming back.
    if let Ok(cfg) = crate::tls::client_config(&d.id, Some("0".repeat(64))) {
        for t in targets {
            for delay in [0u64, 300, 900, 2000] {
                let ep = ep.clone();
                let cfg = cfg.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(delay)).await;
                    if let Ok(c) = ep.connect_with(cfg, t, "punch") {
                        let _ = tokio::time::timeout(Duration::from_millis(1500), c).await;
                    }
                });
            }
        }
    }
    let info = d.info();
    let answer = json!({"t": "answer", "nonce": nonce, "to": from, "addrs": info.addrs, "ts": crate::util::now_secs()});
    if let Some(bell) = info.doorbell {
        let _ = tokio::task::spawn_blocking(move || bell.ring(&answer)).await;
    }
}
