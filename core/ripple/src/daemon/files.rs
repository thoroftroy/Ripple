//! File access for paired phones: browse, download, upload, and the shared "Ripple" folder.

use super::Daemon;
use crate::proto::{self, Frame};
use anyhow::{Result, anyhow, bail};
use serde_json::{Value, json};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub fn expand(p: &str) -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/".into());
    if p.is_empty() || p == "~" {
        PathBuf::from(home)
    } else if let Some(rest) = p.strip_prefix("~/") {
        PathBuf::from(home).join(rest)
    } else {
        PathBuf::from(p)
    }
}

fn entry(path: &Path, name: String) -> Value {
    let lmeta = std::fs::symlink_metadata(path).ok();
    let meta = std::fs::metadata(path).ok().or(lmeta.clone());
    let link = lmeta.map(|m| m.file_type().is_symlink()).unwrap_or(false);
    match meta {
        Some(m) => json!({
            "name": name, "dir": m.is_dir(), "size": if m.is_dir() { 0 } else { m.len() },
            "mtime": m.mtime() * 1000, "link": link, "hidden": name.starts_with('.'),
            "exec": !m.is_dir() && (m.permissions().mode() & 0o111) != 0,
        }),
        None => json!({"name": name, "dir": false, "size": 0, "mtime": 0, "link": link, "broken": true}),
    }
}

pub async fn simple(d: &Arc<Daemon>, op: &str, req: &Value) -> Result<Value> {
    let path = expand(req["path"].as_str().unwrap_or(""));
    match op {
        "home" => Ok(json!({"home": std::env::var("HOME").unwrap_or_default(), "shared": d.cfg.read().unwrap().shared_dir.clone()})),
        "ls" => {
            let p = path.clone();
            tokio::task::spawn_blocking(move || -> Result<Value> {
                let rd = std::fs::read_dir(&p).map_err(|e| anyhow!("Can't open {}: {}", p.display(), e))?;
                let mut list: Vec<Value> = rd
                    .filter_map(|e| e.ok())
                    .map(|e| entry(&e.path(), e.file_name().to_string_lossy().to_string()))
                    .collect();
                list.sort_by(|a, b| {
                    b["dir"].as_bool().cmp(&a["dir"].as_bool()).then_with(|| {
                        a["name"].as_str().unwrap_or("").to_lowercase().cmp(&b["name"].as_str().unwrap_or("").to_lowercase())
                    })
                });
                Ok(json!({"path": p.to_string_lossy(), "entries": list}))
            })
            .await?
        }
        "stat" => {
            if !path.exists() {
                bail!("{} doesn't exist", path.display());
            }
            let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "/".into());
            Ok(json!({"path": path.to_string_lossy(), "entry": entry(&path, name)}))
        }
        "mkdir" => {
            tokio::fs::create_dir_all(&path).await?;
            Ok(json!({"ok": true, "path": path.to_string_lossy()}))
        }
        "rm" => {
            let m = tokio::fs::symlink_metadata(&path).await?;
            if m.is_dir() {
                tokio::fs::remove_dir_all(&path).await?;
            } else {
                tokio::fs::remove_file(&path).await?;
            }
            Ok(json!({"ok": true}))
        }
        "mv" => {
            let to = expand(req["to"].as_str().unwrap_or(""));
            if to.exists() {
                bail!("{} already exists", to.display());
            }
            tokio::fs::rename(&path, &to).await?;
            Ok(json!({"ok": true, "path": to.to_string_lossy()}))
        }
        "du" => {
            let p = path.clone();
            let total = tokio::task::spawn_blocking(move || dir_size(&p)).await?;
            Ok(json!({"size": total}))
        }
        _ => bail!("unknown"),
    }
}

fn dir_size(p: &Path) -> u64 {
    let Ok(m) = std::fs::symlink_metadata(p) else { return 0 };
    if !m.is_dir() {
        return m.len();
    }
    std::fs::read_dir(p).map(|rd| rd.filter_map(|e| e.ok()).map(|e| dir_size(&e.path())).sum()).unwrap_or(0)
}

/// Download: reply {size}, then data frames, then {"done": true}.
pub async fn get<R, W>(req: Value, _r: R, mut w: W) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let path = expand(req["path"].as_str().unwrap_or(""));
    let offset = req["offset"].as_u64().unwrap_or(0);
    let mut f = match tokio::fs::File::open(&path).await {
        Ok(f) => f,
        Err(e) => {
            proto::write_json(&mut w, &json!({"error": format!("Can't open {}: {}", path.display(), e)})).await?;
            return Ok(());
        }
    };
    let meta = f.metadata().await?;
    if meta.is_dir() {
        proto::write_json(&mut w, &json!({"error": "That's a folder"})).await?;
        return Ok(());
    }
    if offset > 0 {
        use tokio::io::AsyncSeekExt;
        f.seek(std::io::SeekFrom::Start(offset)).await?;
    }
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    proto::write_json(&mut w, &json!({"ok": true, "size": meta.len(), "name": name, "mtime": meta.mtime() * 1000})).await?;
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = f.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        proto::write_frame(&mut w, proto::KIND_DATA, &buf[..n]).await?;
    }
    proto::write_json(&mut w, &json!({"done": true})).await?;
    w.shutdown().await.ok();
    Ok(())
}

fn free_name(path: &Path) -> PathBuf {
    if !path.exists() {
        return path.to_path_buf();
    }
    let stem = path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let ext = path.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    let parent = path.parent().unwrap_or(Path::new("/"));
    for i in 2..10000 {
        let p = parent.join(format!("{stem} ({i}){ext}"));
        if !p.exists() {
            return p;
        }
    }
    path.to_path_buf()
}

/// Upload: reply {"ok", "path"}, receive data frames until {"done": true}, reply {"ok", "path", "size"}.
pub async fn put<R, W>(d: Arc<Daemon>, req: Value, mut r: R, mut w: W) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let raw = req["path"].as_str().unwrap_or("");
    let mut path = if raw.is_empty() {
        PathBuf::from(&d.cfg.read().unwrap().shared_dir).join(req["name"].as_str().unwrap_or("file"))
    } else {
        expand(raw)
    };
    if path.is_dir() {
        path = path.join(req["name"].as_str().unwrap_or("file"));
    }
    if !req["overwrite"].as_bool().unwrap_or(false) {
        path = free_name(&path);
    }
    if let Some(parent) = path.parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }
    let part = path.with_file_name(format!(".{}.ripple-part", path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()));
    let mut f = match tokio::fs::File::create(&part).await {
        Ok(f) => f,
        Err(e) => {
            proto::write_json(&mut w, &json!({"error": format!("Can't save to {}: {}", path.display(), e)})).await?;
            return Ok(());
        }
    };
    proto::write_json(&mut w, &json!({"ok": true, "path": path.to_string_lossy()})).await?;
    let mut total = 0u64;
    let complete = loop {
        match proto::read_frame(&mut r).await {
            Ok(Some(Frame::Data(b))) => {
                f.write_all(&b).await?;
                total += b.len() as u64;
            }
            Ok(Some(Frame::Json(v))) if v["done"].as_bool() == Some(true) => break true,
            _ => break false,
        }
    };
    f.flush().await?;
    drop(f);
    if !complete {
        let _ = tokio::fs::remove_file(&part).await;
        return Ok(());
    }
    tokio::fs::rename(&part, &path).await?;
    if let Some(mt) = req["mtime"].as_u64() {
        let ft = std::time::UNIX_EPOCH + std::time::Duration::from_millis(mt);
        if let Ok(file) = std::fs::File::options().write(true).open(&path) {
            let _ = file.set_modified(ft);
        }
    }
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let _ = d.events.send(json!({"event": "file_received", "path": path.to_string_lossy(), "name": name, "size": total}));
    let _ = std::process::Command::new("notify-send").args(["-a", "Ripple", "-i", "document-save", &format!("Received {name}"), &path.to_string_lossy()]).spawn();
    proto::write_json(&mut w, &json!({"ok": true, "path": path.to_string_lossy(), "size": total})).await?;
    Ok(())
}

/// `ripple send FILE`: puts a copy in the shared folder and tells connected phones about it.
pub async fn offer(d: &Arc<Daemon>, path: &str) -> Result<Value> {
    let src = expand(path);
    let meta = tokio::fs::metadata(&src).await.map_err(|e| anyhow!("Can't read {}: {}", src.display(), e))?;
    if meta.is_dir() {
        bail!("Send a file, not a folder (zip it first)");
    }
    let shared = PathBuf::from(d.cfg.read().unwrap().shared_dir.clone());
    tokio::fs::create_dir_all(&shared).await?;
    let src_abs = std::fs::canonicalize(&src)?;
    let dest = if src_abs.starts_with(std::fs::canonicalize(&shared).unwrap_or(shared.clone())) {
        src_abs.clone()
    } else {
        let dest = free_name(&shared.join(src.file_name().ok_or_else(|| anyhow!("no file name"))?));
        tokio::fs::copy(&src, &dest).await?;
        dest
    };
    let name = dest.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let _ = d.events.send(json!({"event": "file_offer", "path": dest.to_string_lossy(), "name": name, "size": meta.len()}));
    Ok(json!({"ok": true, "path": dest.to_string_lossy(), "name": name, "size": meta.len()}))
}
