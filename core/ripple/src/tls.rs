//! TLS 1.3 (inside QUIC) with pinned self-signed certificates on both sides.
//!
//! * The computer accepts any client certificate at the TLS layer (the client must still prove it
//!   holds the key), then checks the fingerprint against its trusted devices before allowing
//!   anything except pairing.
//! * The phone pins the computer's fingerprint learned while pairing. Only during pairing does it
//!   accept an unknown certificate, and then the person compares codes (or the QR code carried the
//!   fingerprint already).

use crate::identity::{Identity, fingerprint};
use anyhow::Result;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, WebPkiSupportedAlgorithms, verify_tls12_signature, verify_tls13_signature};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{DigitallySignedStruct, DistinguishedName, SignatureScheme};
use std::sync::Arc;
use std::time::Duration;

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

fn algs() -> WebPkiSupportedAlgorithms {
    rustls::crypto::ring::default_provider().signature_verification_algorithms
}

#[derive(Debug)]
struct AnyClientCert(WebPkiSupportedAlgorithms);

impl ClientCertVerifier for AnyClientCert {
    fn offer_client_auth(&self) -> bool {
        true
    }
    fn client_auth_mandatory(&self) -> bool {
        true
    }
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }
    fn verify_client_cert(&self, _: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: UnixTime) -> Result<ClientCertVerified, rustls::Error> {
        Ok(ClientCertVerified::assertion())
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(m, c, d, &self.0)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(m, c, d, &self.0)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.supported_schemes()
    }
}

#[derive(Debug)]
struct PinnedServer {
    pin: Option<String>,
    algs: WebPkiSupportedAlgorithms,
}

impl ServerCertVerifier for PinnedServer {
    fn verify_server_cert(&self, end: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        match &self.pin {
            Some(pin) if !crate::util::ct_eq(pin, &fingerprint(end)) => {
                Err(rustls::Error::General("this computer's identity doesn't match the one you paired with".into()))
            }
            _ => Ok(ServerCertVerified::assertion()),
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(m, c, d, &self.algs)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(m, c, d, &self.algs)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algs.supported_schemes()
    }
}

pub fn transport() -> Arc<quinn::TransportConfig> {
    let mut t = quinn::TransportConfig::default();
    // Keeps home-router and phone-network firewalls from forgetting the path while idle.
    t.keep_alive_interval(Some(Duration::from_secs(12)));
    t.max_idle_timeout(Some(Duration::from_secs(40).try_into().unwrap()));
    // Handshake packets that get lost (a firewall not yet opened by the doorbell) are resent sooner than the 333 ms default suggests.
    t.initial_rtt(Duration::from_millis(120));
    t.max_concurrent_bidi_streams(256u32.into());
    t.stream_receive_window((8u32 * 1024 * 1024).into());
    t.receive_window((32u32 * 1024 * 1024).into());
    t.send_window(32 * 1024 * 1024);
    // Start fast instead of crawling up from a tiny window: file transfers are the busiest thing on the link.
    let mut cubic = quinn::congestion::CubicConfig::default();
    cubic.initial_window(1_000_000);
    t.congestion_controller_factory(Arc::new(cubic));
    Arc::new(t)
}

pub fn server_config(id: &Identity) -> Result<quinn::ServerConfig> {
    let mut tls = rustls::ServerConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_client_cert_verifier(Arc::new(AnyClientCert(algs())))
        .with_single_cert(vec![id.cert()], id.key())?;
    tls.alpn_protocols = vec![crate::ALPN.to_vec()];
    tls.max_early_data_size = 0;
    let quic = quinn::crypto::rustls::QuicServerConfig::try_from(tls)?;
    let mut cfg = quinn::ServerConfig::with_crypto(Arc::new(quic));
    cfg.transport_config(transport());
    cfg.migration(true);
    Ok(cfg)
}

/// `pin = None` only while pairing.
pub fn client_config(id: &Identity, pin: Option<String>) -> Result<quinn::ClientConfig> {
    let mut tls = rustls::ClientConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedServer { pin, algs: algs() }))
        .with_client_auth_cert(vec![id.cert()], id.key())?;
    tls.alpn_protocols = vec![crate::ALPN.to_vec()];
    let quic = quinn::crypto::rustls::QuicClientConfig::try_from(tls)?;
    let mut cfg = quinn::ClientConfig::new(Arc::new(quic));
    cfg.transport_config(transport());
    Ok(cfg)
}

/// Fingerprint of the certificate the other side presented.
pub fn peer_fingerprint(conn: &quinn::Connection) -> Option<String> {
    let any = conn.peer_identity()?;
    let certs = any.downcast::<Vec<CertificateDer<'static>>>().ok()?;
    certs.first().map(|c| fingerprint(c))
}
