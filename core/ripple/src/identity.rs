//! A device's long-lived identity: a self-signed certificate whose SHA-256 fingerprint is what
//! the other side pins after pairing. No certificate authorities or accounts are involved.

use anyhow::{Context, Result};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::path::Path;

#[derive(Clone)]
pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
}

impl Identity {
    pub fn generate(name: &str) -> Result<Self> {
        let ck = rcgen::generate_simple_self_signed(vec![sanitise(name), "ripple".to_string()])?;
        Ok(Self { cert_der: ck.cert.der().to_vec(), key_der: ck.signing_key.serialize_der() })
    }

    /// Loads the identity stored in `dir`, creating one the first time.
    pub fn load_or_create(dir: &Path, name: &str) -> Result<Self> {
        let cert = dir.join("identity.cert");
        let key = dir.join("identity.key");
        if cert.exists() && key.exists() {
            return Ok(Self {
                cert_der: std::fs::read(&cert).context("reading identity certificate")?,
                key_der: std::fs::read(&key).context("reading identity key")?,
            });
        }
        std::fs::create_dir_all(dir)?;
        let id = Self::generate(name)?;
        write_private(&key, &id.key_der)?;
        std::fs::write(&cert, &id.cert_der)?;
        Ok(id)
    }

    pub fn fingerprint(&self) -> String {
        fingerprint(&self.cert_der)
    }

    pub fn cert(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.cert_der.clone())
    }

    pub fn key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone()))
    }
}

pub fn fingerprint(der: &[u8]) -> String {
    hex::encode(Sha256::digest(der))
}

fn sanitise(name: &str) -> String {
    let s: String = name.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
    if s.is_empty() { "ripple-device".into() } else { s.to_lowercase() }
}

/// Writes a file only the owner can read.
pub fn write_private(path: &Path, data: &[u8]) -> Result<()> {
    use std::io::Write;
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let tmp = path.with_extension("tmp");
        let mut f = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
        std::fs::rename(&tmp, path)?;
    }
    Ok(())
}
