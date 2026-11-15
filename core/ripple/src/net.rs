//! Addresses, sockets and local-network discovery.

use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::net::{IpAddr, Ipv6Addr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use crate::doorbell::DoorbellInfo;

/// Everything a phone needs to find and trust a computer. Sent while pairing and refreshed on
/// every connection, so addresses that change (new IPv6 prefix, new router) follow along.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ComputerInfo {
    pub name: String,
    pub fp: String,
    pub port: u16,
    #[serde(default)]
    pub addrs: Vec<String>,
    #[serde(default)]
    pub doorbell: Option<DoorbellInfo>,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub home: String,
    #[serde(default)]
    pub shared_dir: String,
}

pub fn is_global_v6(ip: &Ipv6Addr) -> bool {
    (ip.segments()[0] & 0xe000) == 0x2000
}

fn is_ula_v6(ip: &Ipv6Addr) -> bool {
    (ip.segments()[0] & 0xfe00) == 0xfc00
}

/// Every address this device can be reached on, best first: private IPv4 (home network),
/// global IPv6, then anything else (VPN overlays such as 100.x).
pub fn local_ips() -> Vec<IpAddr> {
    let mut v4 = vec![];
    let mut v6 = vec![];
    let mut other = vec![];
    if let Ok(ifs) = if_addrs::get_if_addrs() {
        for i in ifs {
            if i.is_loopback() {
                continue;
            }
            match i.ip() {
                IpAddr::V4(a) if a.is_link_local() => {}
                IpAddr::V4(a) if a.is_private() => v4.push(IpAddr::V4(a)),
                IpAddr::V4(a) => other.push(IpAddr::V4(a)),
                IpAddr::V6(a) if is_global_v6(&a) => v6.push(IpAddr::V6(a)),
                IpAddr::V6(a) if is_ula_v6(&a) => other.push(IpAddr::V6(a)),
                IpAddr::V6(_) => {}
            }
        }
    }
    v4.into_iter().chain(v6).chain(other).collect()
}

pub fn global_v6() -> Vec<Ipv6Addr> {
    local_ips()
        .into_iter()
        .filter_map(|i| match i {
            IpAddr::V6(a) if is_global_v6(&a) => Some(a),
            _ => None,
        })
        .collect()
}

pub fn fmt_addr(ip: IpAddr, port: u16) -> String {
    SocketAddr::new(ip, port).to_string()
}

/// A UDP socket on all interfaces, IPv6 and IPv4 at once where the system allows it.
pub fn dual_stack_udp(port: u16) -> Result<UdpSocket> {
    use socket2::{Domain, Protocol, Socket, Type};
    let sock = Socket::new(Domain::IPV6, Type::DGRAM, Some(Protocol::UDP));
    if let Ok(s) = sock {
        let _ = s.set_only_v6(false);
        let addr: SocketAddr = SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port);
        if s.bind(&addr.into()).is_ok() {
            return Ok(s.into());
        }
    }
    Ok(UdpSocket::bind(SocketAddr::from(([0, 0, 0, 0], port)))?)
}

pub fn endpoint(port: u16, server: Option<quinn::ServerConfig>) -> Result<quinn::Endpoint> {
    let sock = dual_stack_udp(port)?;
    let ep = quinn::Endpoint::new(quinn::EndpointConfig::default(), server, sock, Arc::new(quinn::TokioRuntime))?;
    Ok(ep)
}

/// Turns "host:port", "[v6]:port", "host" or "v6" into socket addresses (resolving names).
pub async fn resolve(addr: &str, default_port: u16) -> Vec<SocketAddr> {
    let a = addr.trim();
    if a.is_empty() {
        return vec![];
    }
    if let Ok(s) = a.parse::<SocketAddr>() {
        return vec![s];
    }
    if let Ok(ip) = a.trim_matches(|c| c == '[' || c == ']').parse::<IpAddr>() {
        return vec![SocketAddr::new(ip, default_port)];
    }
    let with_port = if a.rsplit_once(':').map(|(_, p)| p.parse::<u16>().is_ok()).unwrap_or(false) {
        a.to_string()
    } else {
        format!("{a}:{default_port}")
    };
    match tokio::time::timeout(Duration::from_secs(4), tokio::net::lookup_host(with_port)).await {
        Ok(Ok(it)) => it.collect(),
        _ => vec![],
    }
}

/// Maps an IPv4-mapped IPv6 address back to plain IPv4.
pub fn canonical(a: SocketAddr) -> SocketAddr {
    match a {
        SocketAddr::V6(v6) => match v6.ip().to_ipv4_mapped() {
            Some(v4) => SocketAddr::new(IpAddr::V4(v4), v6.port()),
            None => a,
        },
        _ => a,
    }
}

/// A computer seen on the local network.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Found {
    pub name: String,
    pub fp: String,
    pub port: u16,
    pub addr: String,
}

pub const DISCOVER_MAGIC: &[u8] = b"RIPPLE-DISCOVER/1";

/// Asks the local network "any Ripple computers here?" and collects answers for `wait`.
pub async fn discover(wait: Duration) -> Vec<Found> {
    let sock = match tokio::net::UdpSocket::bind("0.0.0.0:0").await {
        Ok(s) => s,
        Err(_) => return vec![],
    };
    let _ = sock.set_broadcast(true);
    let mut targets: Vec<SocketAddr> = vec![SocketAddr::from(([255, 255, 255, 255], crate::DISCOVERY_PORT))];
    if let Ok(ifs) = if_addrs::get_if_addrs() {
        for i in ifs {
            if let if_addrs::IfAddr::V4(v4) = &i.addr {
                if let Some(b) = v4.broadcast {
                    targets.push(SocketAddr::new(IpAddr::V4(b), crate::DISCOVERY_PORT));
                }
            }
        }
    }
    let v6 = tokio::net::UdpSocket::bind("[::]:0").await.ok();
    let mut found: Vec<Found> = vec![];
    let deadline = tokio::time::Instant::now() + wait;
    let mut next_probe = tokio::time::Instant::now();
    let mut buf = vec![0u8; 4096];
    let mut buf6 = vec![0u8; 4096];
    loop {
        let now = tokio::time::Instant::now();
        if now >= deadline {
            break;
        }
        if now >= next_probe {
            for t in &targets {
                let _ = sock.send_to(DISCOVER_MAGIC, t).await;
            }
            if let Some(s6) = &v6 {
                let _ = s6.send_to(DISCOVER_MAGIC, ("ff02::1", crate::DISCOVERY_PORT)).await;
            }
            next_probe = now + Duration::from_millis(700);
        }
        let until = next_probe.min(deadline);
        let got = tokio::select! {
            r = sock.recv_from(&mut buf) => r.ok().map(|(n, a)| (buf[..n].to_vec(), a)),
            r = async { match &v6 { Some(s) => s.recv_from(&mut buf6).await.ok().map(|(n, a)| (buf6[..n].to_vec(), a)), None => std::future::pending().await } } => r,
            _ = tokio::time::sleep_until(until) => None,
        };
        if let Some((data, from)) = got {
            if let Ok(info) = serde_json::from_slice::<serde_json::Value>(&data) {
                let fp = info["fp"].as_str().unwrap_or_default().to_string();
                if fp.is_empty() || found.iter().any(|f| f.fp == fp) {
                    continue;
                }
                let port = info["port"].as_u64().unwrap_or(crate::DEFAULT_PORT as u64) as u16;
                found.push(Found {
                    name: info["name"].as_str().unwrap_or("Computer").to_string(),
                    fp,
                    port,
                    addr: fmt_addr(canonical(from).ip(), port),
                });
            }
        }
    }
    found
}
