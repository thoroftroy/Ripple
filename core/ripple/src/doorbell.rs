//! The doorbell: how a phone outside the home network asks the computer to open a path.
//!
//! Home routers (and carrier-grade NAT) drop unsolicited incoming packets, so neither side can
//! simply connect. Both sides *can* send outgoing packets, and a firewall lets replies back in.
//! So the phone posts a small encrypted "knock" containing its current addresses to a public
//! ntfy-compatible relay topic that only paired devices know. The computer, which listens on that
//! topic, sends packets towards the phone at the same moment the phone sends packets towards the
//! computer: both firewalls see outgoing traffic and let the other side's packets in (UDP hole
//! punching). The secure QUIC connection is then direct between the two devices.
//!
//! The relay only ever sees an encrypted blob (ChaCha20-Poly1305, key shared at pairing). No
//! terminal data, files or credentials pass through it, and the server address is configurable
//! (anyone can run their own ntfy server).

use anyhow::{Context, Result, anyhow};
use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::io::BufRead;
use std::time::Duration;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DoorbellInfo {
    pub server: String,
    pub topic: String,
    /// base64, 32 bytes
    pub key: String,
}

pub const DEFAULT_SERVER: &str = "https://ntfy.sh";

impl DoorbellInfo {
    pub fn generate(server: &str) -> Self {
        Self {
            server: server.trim_end_matches('/').to_string(),
            topic: format!("ripple-{}", hex::encode(crate::util::random_bytes(16))),
            key: B64.encode(crate::util::random_bytes(32)),
        }
    }

    fn cipher(&self) -> Result<ChaCha20Poly1305> {
        let key = B64.decode(&self.key)?;
        ChaCha20Poly1305::new_from_slice(&key).map_err(|_| anyhow!("bad doorbell key"))
    }

    pub fn seal(&self, msg: &Value) -> Result<String> {
        let nonce = crate::util::random_bytes(12);
        let ct = self
            .cipher()?
            .encrypt(Nonce::from_slice(&nonce), serde_json::to_vec(msg)?.as_slice())
            .map_err(|_| anyhow!("encrypt failed"))?;
        let mut out = nonce;
        out.extend(ct);
        Ok(B64.encode(out))
    }

    pub fn open(&self, sealed: &str) -> Result<Value> {
        let raw = B64.decode(sealed.trim())?;
        if raw.len() < 13 {
            return Err(anyhow!("short message"));
        }
        let pt = self
            .cipher()?
            .decrypt(Nonce::from_slice(&raw[..12]), &raw[12..])
            .map_err(|_| anyhow!("not for us"))?;
        Ok(serde_json::from_slice(&pt)?)
    }

    fn url(&self) -> String {
        format!("{}/{}", self.server.trim_end_matches('/'), self.topic)
    }

    /// Posts a message. Blocking; call from a blocking thread.
    pub fn ring(&self, msg: &Value) -> Result<()> {
        let body = self.seal(msg)?;
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(8)))
            .build()
            .into();
        agent
            .post(&self.url())
            .header("Cache", "yes")
            .header("Firebase", "no")
            .send(body.as_bytes())
            .context("Couldn't reach the doorbell relay. Is this device online?")?;
        Ok(())
    }

    /// Messages posted since `since` (unix seconds). Blocking.
    pub fn poll(&self, since: u64) -> Result<Vec<Value>> {
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(8)))
            .build()
            .into();
        let resp = agent.get(&format!("{}/json?poll=1&since={since}", self.url())).call()?;
        let mut out = vec![];
        let reader = std::io::BufReader::new(resp.into_body().into_reader());
        for line in reader.lines() {
            let line = line?;
            if let Ok(ev) = serde_json::from_str::<Value>(&line) {
                if ev["event"] == "message" {
                    if let Some(m) = ev["message"].as_str() {
                        if let Ok(v) = self.open(m) {
                            out.push(v);
                        }
                    }
                }
            }
        }
        Ok(out)
    }

    /// Listens forever, calling `on_msg` for each message that decrypts. Reconnects on errors.
    /// Blocking; run on its own thread. `stop` is checked between reconnects.
    pub fn listen(&self, stop: &std::sync::atomic::AtomicBool, mut on_msg: impl FnMut(Value), mut on_state: impl FnMut(bool, Option<String>)) {
        use std::sync::atomic::Ordering;
        let mut since = format!("{}", crate::util::now_secs());
        let mut backoff = 2u64;
        while !stop.load(Ordering::Relaxed) {
            let agent: ureq::Agent = ureq::Agent::config_builder()
                // ntfy sends a keepalive every 45 s; reconnecting every few minutes also notices dead links.
                .timeout_global(Some(Duration::from_secs(300)))
                .build()
                .into();
            match agent.get(&format!("{}/json?since={since}", self.url())).call() {
                Ok(resp) => {
                    on_state(true, None);
                    backoff = 2;
                    let reader = std::io::BufReader::new(resp.into_body().into_reader());
                    for line in reader.lines() {
                        let Ok(line) = line else { break };
                        if stop.load(Ordering::Relaxed) {
                            return;
                        }
                        let Ok(ev) = serde_json::from_str::<Value>(&line) else { continue };
                        if let Some(id) = ev["id"].as_str() {
                            if ev["event"] == "message" {
                                since = id.to_string();
                            }
                        }
                        if ev["event"] == "message" {
                            if let Some(m) = ev["message"].as_str() {
                                if let Ok(v) = self.open(m) {
                                    on_msg(v);
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    on_state(false, Some(e.to_string()));
                    std::thread::sleep(Duration::from_secs(backoff));
                    backoff = (backoff * 2).min(60);
                }
            }
        }
    }
}
