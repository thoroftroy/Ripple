//! Wire framing shared by every Ripple stream (QUIC streams and the local Unix socket).
//!
//! A frame is `kind: u8`, `len: u32 (big endian)`, then `len` bytes.
//! kind 1 = JSON message, kind 2 = raw bytes (terminal data, file contents).
//! The first frame on every stream is a JSON request naming the operation (`{"op": "attach", ...}`).

use anyhow::{Result, bail};
use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const KIND_JSON: u8 = 1;
pub const KIND_DATA: u8 = 2;
pub const MAX_FRAME: usize = 8 * 1024 * 1024;

#[derive(Debug)]
pub enum Frame {
    Json(Value),
    Data(Vec<u8>),
}

pub async fn write_json<W: AsyncWrite + Unpin>(w: &mut W, v: &Value) -> Result<()> {
    let body = serde_json::to_vec(v)?;
    write_frame(w, KIND_JSON, &body).await
}

pub async fn write_data<W: AsyncWrite + Unpin>(w: &mut W, data: &[u8]) -> Result<()> {
    // Large writes are split so a frame never exceeds the limit on the other side.
    for chunk in data.chunks(MAX_FRAME / 2) {
        write_frame(w, KIND_DATA, chunk).await?;
    }
    Ok(())
}

pub async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, kind: u8, body: &[u8]) -> Result<()> {
    let mut head = [0u8; 5];
    head[0] = kind;
    head[1..].copy_from_slice(&(body.len() as u32).to_be_bytes());
    w.write_all(&head).await?;
    w.write_all(body).await?;
    w.flush().await?;
    Ok(())
}

/// Reads one frame. Returns `None` on a clean end of stream.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> Result<Option<Frame>> {
    let mut head = [0u8; 5];
    match r.read_exact(&mut head).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }
    let len = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
    if len > MAX_FRAME {
        bail!("frame too large ({len} bytes)");
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body).await?;
    match head[0] {
        KIND_JSON => Ok(Some(Frame::Json(serde_json::from_slice(&body)?))),
        KIND_DATA => Ok(Some(Frame::Data(body))),
        k => bail!("unknown frame kind {k}"),
    }
}

pub async fn read_json<R: AsyncRead + Unpin>(r: &mut R) -> Result<Value> {
    match read_frame(r).await? {
        Some(Frame::Json(v)) => Ok(v),
        Some(Frame::Data(_)) => bail!("expected a message, got data"),
        None => bail!("connection closed"),
    }
}

/// Turns `{"error": "..."}` replies into errors.
pub fn check(v: Value) -> Result<Value> {
    if let Some(e) = v.get("error").and_then(|e| e.as_str()) {
        bail!("{e}");
    }
    Ok(v)
}
