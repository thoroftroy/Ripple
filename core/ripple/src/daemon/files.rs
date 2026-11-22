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
                prewarm(&p, &list);
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
        "thumb" => {
            // A small preview of a picture or video, made here so the phone only has to fetch a few kilobytes (tiny first, then sharper).
            let size = req["size"].as_u64().unwrap_or(256).clamp(16, 2048) as u32;
            let p = path.clone();
            tokio::task::spawn_blocking(move || thumb(&p, size)).await?
        }
        "du" => {
            let p = path.clone();
            let total = tokio::task::spawn_blocking(move || dir_size(&p)).await?;
            Ok(json!({"size": total}))
        }
        _ => bail!("unknown"),
    }
}

const VIDEO_EXT: &[&str] = &["mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp", "flv", "wmv", "mpg", "mpeg", "ts"];
const IMAGE_EXT: &[&str] = &["jpg", "jpeg", "png", "webp", "gif"];

fn thumb_cache_file(p: &Path, size: u32, meta: &std::fs::Metadata) -> Option<PathBuf> {
    use sha2::{Digest, Sha256};
    let home = std::env::var("HOME").ok()?;
    let dir = PathBuf::from(home).join(".cache/ripple/thumbs");
    std::fs::create_dir_all(&dir).ok()?;
    let mut h = Sha256::new();
    h.update(p.to_string_lossy().as_bytes());
    h.update(meta.len().to_le_bytes());
    h.update(meta.mtime().to_le_bytes());
    h.update(size.to_le_bytes());
    Some(dir.join(format!("{}.jpg", hex::encode(&h.finalize()[..12]))))
}

/// Makes (or reads from the cache) a JPEG whose longest side is `size`, and returns it base64-encoded with its dimensions.
fn thumb(p: &Path, size: u32) -> Result<Value> {
    use base64::Engine;
    let meta = std::fs::metadata(p).map_err(|e| anyhow!("Can't open {}: {}", p.display(), e))?;
    if meta.is_dir() {
        bail!("That's a folder");
    }
    let ext = p.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    let cache = thumb_cache_file(p, size, &meta);
    let jpeg: Vec<u8> = if let Some(bytes) = cache.as_ref().and_then(|c| std::fs::read(c).ok()) {
        bytes
    } else {
        let made = if IMAGE_EXT.contains(&ext.as_str()) {
            let img = decode_image(p)?;
            // Answer the requested size first. Sharper passes are useful, but
            // must not hold up the first preview while they are generated.
            let first = encode_jpeg(&img, size)?;
            let later = next_sizes(size).iter().filter_map(|s| {
                thumb_cache_file(p, *s, &meta).filter(|c| !c.exists()).map(|c| (*s, c))
            }).collect::<Vec<_>>();
            if !later.is_empty() {
                schedule_sharper(img, later);
            }
            first
        } else if VIDEO_EXT.contains(&ext.as_str()) {
            video_thumb(p, size)?
        } else {
            bail!("No preview for this kind of file");
        };
        if let Some(c) = &cache {
            let tmp = c.with_extension(format!("tmp{:?}", std::thread::current().id()).replace(|ch: char| !ch.is_ascii_alphanumeric(), ""));
            if std::fs::write(&tmp, &made).is_ok() {
                let _ = std::fs::rename(&tmp, c);
            }
        }
        made
    };
    let (w, h) = image::ImageReader::new(std::io::Cursor::new(&jpeg)).with_guessed_format().ok().and_then(|r| r.into_dimensions().ok()).unwrap_or((0, 0));
    Ok(json!({"w": w, "h": h, "size": meta.len(), "jpeg": base64::engine::general_purpose::STANDARD.encode(&jpeg)}))
}

/// At most one image is queued behind the worker. A busy queue is skipped
/// rather than delaying the request or accumulating large decoded images.
fn schedule_sharper(img: image::DynamicImage, sizes: Vec<(u32, PathBuf)>) {
    type Work = (image::DynamicImage, Vec<(u32, PathBuf)>);
    static WORKER: std::sync::OnceLock<std::sync::mpsc::SyncSender<Work>> = std::sync::OnceLock::new();
    let sender = WORKER.get_or_init(|| {
        let (tx, rx) = std::sync::mpsc::sync_channel::<Work>(1);
        std::thread::spawn(move || {
            while let Ok((image, sizes)) = rx.recv() {
                for (size, path) in sizes {
                    store(Some(path), || encode_jpeg(&image, size));
                }
            }
        });
        tx
    });
    let _ = sender.try_send((img, sizes));
}

fn encode_jpeg(img: &image::DynamicImage, size: u32) -> Result<Vec<u8>> {
    let small = if img.width().max(img.height()) > size { img.thumbnail(size, size) } else { img.clone() };
    let rgb = small.to_rgb8();
    let quality = if size <= 96 { 55 } else if size <= 400 { 70 } else { 80 };
    let mut out = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, quality).encode_image(&rgb)?;
    Ok(out)
}

fn decode_image(p: &Path) -> Result<image::DynamicImage> {
    use image::ImageDecoder;
    let mut decoder = image::ImageReader::open(p)?.with_guessed_format()?.into_decoder()?;
    let orientation = decoder.orientation().ok();
    let mut img = image::DynamicImage::from_decoder(decoder)?;
    if let Some(o) = orientation {
        img.apply_orientation(o);
    }
    Ok(img)
}

/// The sharper passes that follow a preview size: Wayfinder asks 48 then 192, Memories 64, 320, then 1280.
fn next_sizes(size: u32) -> &'static [u32] {
    match size {
        48 => &[192],
        64 => &[320, 1280],
        320 => &[1280],
        _ => &[],
    }
}

/// Writes a preview into the cache unless it is already there.
fn store(cache: Option<PathBuf>, make: impl FnOnce() -> Result<Vec<u8>>) {
    let Some(c) = cache else { return };
    if c.exists() {
        return;
    }
    if let Ok(bytes) = make() {
        let tmp = c.with_extension(format!("tmp{:?}", std::thread::current().id()).replace(|ch: char| !ch.is_ascii_alphanumeric(), ""));
        if std::fs::write(&tmp, &bytes).is_ok() {
            let _ = std::fs::rename(&tmp, &c);
        }
    }
}

static WARM_GEN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// When a phone opens a folder, the list previews it is about to ask for are made here straight away (newest first,
/// like the phone shows them), so each request is answered from the cache. Opening another folder stops the old run.
fn prewarm(dir: &Path, list: &[Value]) {
    use std::sync::atomic::Ordering;
    let mut media: Vec<(i64, PathBuf)> = list
        .iter()
        .filter(|e| !e["dir"].as_bool().unwrap_or(true) && e["size"].as_u64().unwrap_or(0) > 0)
        .filter_map(|e| {
            let name = e["name"].as_str()?;
            let ext = Path::new(name).extension()?.to_string_lossy().to_lowercase();
            (IMAGE_EXT.contains(&ext.as_str()) || VIDEO_EXT.contains(&ext.as_str())).then(|| (e["mtime"].as_i64().unwrap_or(0), dir.join(name)))
        })
        .collect();
    if media.is_empty() {
        return;
    }
    media.sort_by(|a, b| b.0.cmp(&a.0));
    media.truncate(400);
    let generation = WARM_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    let queue = Arc::new(std::sync::Mutex::new(media.into_iter().map(|m| m.1).collect::<std::collections::VecDeque<_>>()));
    for _ in 0..2 {
        let queue = queue.clone();
        std::thread::spawn(move || {
            loop {
                if WARM_GEN.load(Ordering::SeqCst) != generation {
                    return;
                }
                let Some(p) = queue.lock().unwrap().pop_front() else { return };
                warm(&p);
            }
        });
    }
}

fn warm(p: &Path) {
    let Ok(meta) = std::fs::metadata(p) else { return };
    let want: Vec<(u32, PathBuf)> = [48u32, 192].iter().filter_map(|s| thumb_cache_file(p, *s, &meta).filter(|c| !c.exists()).map(|c| (*s, c))).collect();
    if want.is_empty() {
        return;
    }
    let ext = p.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    let img = if IMAGE_EXT.contains(&ext.as_str()) {
        decode_image(p).ok()
    } else {
        video_thumb(p, 192).ok().and_then(|j| image::load_from_memory(&j).ok())
    };
    let Some(img) = img else { return };
    for (s, c) in want {
        store(Some(c), || encode_jpeg(&img, s));
    }
}

/// A frame from a video, if ffmpeg is installed.
fn video_thumb(p: &Path, size: u32) -> Result<Vec<u8>> {
    for seek in ["1", "0"] {
        let out = std::process::Command::new("ffmpeg")
            .args(["-v", "error", "-ss", seek, "-i"]).arg(p)
            .args(["-frames:v", "1", "-vf", &format!("scale='min({size},iw)':-2"), "-q:v", "5", "-f", "image2pipe", "-vcodec", "mjpeg", "-"])
            .stdin(std::process::Stdio::null()).stderr(std::process::Stdio::null()).output();
        if let Ok(o) = out {
            if o.status.success() && !o.stdout.is_empty() {
                return Ok(o.stdout);
            }
        }
    }
    bail!("No preview (ffmpeg isn't installed here, or the video can't be read)")
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
    // `len` asks for just a slice of the file (a video player seeking, or a parallel download), so the rest isn't sent.
    let mut left = req["len"].as_u64().unwrap_or(u64::MAX);
    // A receiver reads a whole frame before it can use any of it. Smaller
    // frames improve startup and progress on slow links without changing the
    // protocol or the overall window used by QUIC.
    let mut buf = vec![0u8; 64 * 1024];
    while left > 0 {
        let want = (buf.len() as u64).min(left) as usize;
        let n = f.read(&mut buf[..want]).await?;
        if n == 0 {
            break;
        }
        left -= n as u64;
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

#[cfg(test)]
mod thumb_tests {
    use super::*;

    #[test]
    fn makes_small_jpegs_of_pictures_and_rejects_other_files() {
        let dir = std::env::temp_dir().join(format!("ripple-thumb-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let png = dir.join("pic.png");
        let img = image::RgbImage::from_fn(1200, 800, |x, y| image::Rgb([(x % 256) as u8, (y % 256) as u8, 128]));
        img.save(&png).unwrap();
        let small = thumb(&png, 48).unwrap();
        assert!(small["w"].as_u64().unwrap() <= 48 && small["h"].as_u64().unwrap() <= 48);
        let big = thumb(&png, 400).unwrap();
        assert_eq!(big["w"].as_u64().unwrap(), 400);
        assert!(big["jpeg"].as_str().unwrap().len() > small["jpeg"].as_str().unwrap().len());
        let again = thumb(&png, 400).unwrap(); // second call comes from the cache and is identical
        assert_eq!(again["jpeg"], big["jpeg"]);
        let txt = dir.join("a.txt");
        std::fs::write(&txt, "hi").unwrap();
        assert!(thumb(&txt, 48).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod video_thumb_tests {
    use super::*;
    #[test]
    fn grabs_a_frame_when_ffmpeg_exists() {
        if std::process::Command::new("ffmpeg").arg("-version").output().is_err() { return; }
        let dir = std::env::temp_dir().join(format!("ripple-vthumb-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let v = dir.join("clip.mp4");
        let ok = std::process::Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=320x180:rate=10", "-pix_fmt", "yuv420p"]).arg(&v).status().map(|s| s.success()).unwrap_or(false);
        if !ok { return; }
        let t = thumb(&v, 96).unwrap();
        assert!(t["w"].as_u64().unwrap() <= 96 && t["w"].as_u64().unwrap() > 0);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
