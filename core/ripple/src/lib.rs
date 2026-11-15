//! Ripple core: the protocol, identities, secure transport and connection logic shared by the
//! Linux daemon/CLI and the Android app (through `ripple-jni`).

pub mod doorbell;
pub mod identity;
pub mod net;
pub mod proto;
pub mod tls;
pub mod util;
pub mod client;

#[cfg(target_os = "linux")]
pub mod daemon;

/// Default UDP port for the secure (QUIC) listener.
pub const DEFAULT_PORT: u16 = 47800;
/// UDP port answering "is there a Ripple computer here?" broadcasts on the local network.
pub const DISCOVERY_PORT: u16 = 47801;
/// ALPN protocol id, bumped on incompatible protocol changes.
pub const ALPN: &[u8] = b"ripple/1";
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
