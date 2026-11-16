//! JNI bridge for the Android app (class dev.wonderdesk.ripple.net.Native).
//! Every call is blocking and meant to run on a background thread; errors become RuntimeExceptions
//! whose message is written for people.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use ripple::client::Client;
use ripple::identity::Identity;
use ripple::net::ComputerInfo;
use ripple::proto::{self, Frame};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static ID: OnceLock<Identity> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
static CONNS: OnceLock<Mutex<HashMap<u64, Arc<Client>>>> = OnceLock::new();
static STREAMS: OnceLock<Mutex<HashMap<u64, Arc<Stream>>>> = OnceLock::new();

struct Stream {
    send: tokio::sync::Mutex<quinn::SendStream>,
    recv: tokio::sync::Mutex<quinn::RecvStream>,
    reply: String,
}

fn rt() -> &'static tokio::runtime::Runtime {
    RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("runtime"))
}
fn conns() -> &'static Mutex<HashMap<u64, Arc<Client>>> {
    CONNS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn streams() -> &'static Mutex<HashMap<u64, Arc<Stream>>> {
    STREAMS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|s| s.into())
}

fn out(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn conn(h: jlong) -> Option<Arc<Client>> {
    conns().lock().unwrap().get(&(h as u64)).cloned()
}
fn stream(h: jlong) -> Option<Arc<Stream>> {
    streams().lock().unwrap().get(&(h as u64)).cloned()
}

fn callback(env: &mut JNIEnv, cb: &JObject, text: &str) {
    if cb.is_null() {
        return;
    }
    if let Ok(s) = env.new_string(text) {
        let _ = env.call_method(cb, "onStatus", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_init<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, dir: JString<'l>, name: JString<'l>) -> jstring {
    let dir = jstr(&mut env, &dir).unwrap_or_default();
    let name = jstr(&mut env, &name).unwrap_or_else(|| "phone".into());
    let _ = rustls::crypto::ring::default_provider().install_default();
    if let Some(id) = ID.get() {
        return out(&mut env, &id.fingerprint());
    }
    match Identity::load_or_create(std::path::Path::new(&dir), &name) {
        Ok(id) => {
            let fp = id.fingerprint();
            let _ = ID.set(id);
            out(&mut env, &fp)
        }
        Err(e) => {
            throw(&mut env, &format!("Couldn't create this phone's identity: {e}"));
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_discover<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, ms: jint) -> jstring {
    let found = rt().block_on(ripple::net::discover(std::time::Duration::from_millis(ms.max(200) as u64)));
    out(&mut env, &serde_json::to_string(&found).unwrap_or_else(|_| "[]".into()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_pair<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    addr: JString<'l>,
    pin: JString<'l>,
    code: JString<'l>,
    device: JString<'l>,
    bell: JString<'l>,
    cb: JObject<'l>,
) -> jstring {
    let Some(id) = ID.get() else {
        throw(&mut env, "not initialised");
        return std::ptr::null_mut();
    };
    let addr = jstr(&mut env, &addr).unwrap_or_default();
    let pin = jstr(&mut env, &pin).filter(|s| !s.is_empty());
    let code = jstr(&mut env, &code).filter(|s| !s.is_empty());
    let device = jstr(&mut env, &device).unwrap_or_else(|| "Phone".into());
    let bell = jstr(&mut env, &bell).and_then(|s| serde_json::from_str(&s).ok());
    let env_ptr = &mut env as *mut JNIEnv;
    let cb_ref = &cb;
    let r = rt().block_on(ripple::client::pair(id, &addr, pin, code, &device, bell, |c| {
        // SAFETY: runs on this thread inside block_on while `env` is alive.
        let e = unsafe { &mut *env_ptr };
        callback(e, cb_ref, &format!("compare:{c}"));
    }));
    match r {
        Ok(info) => out(&mut env, &serde_json::to_string(&info).unwrap_or_default()),
        Err(e) => {
            throw(&mut env, &e.to_string());
            std::ptr::null_mut()
        }
    }
}

/// Pairing in two steps so the phone can show the comparison code while waiting for approval.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_compareCode<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, server_fp: JString<'l>) -> jstring {
    let fp = jstr(&mut env, &server_fp).unwrap_or_default();
    let mine = ID.get().map(|i| i.fingerprint()).unwrap_or_default();
    out(&mut env, &ripple::util::compare_code(&fp, &mine))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_connect<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, info: JString<'l>, cb: JObject<'l>) -> jlong {
    let Some(id) = ID.get() else {
        throw(&mut env, "not initialised");
        return 0;
    };
    let info: ComputerInfo = match jstr(&mut env, &info).and_then(|s| serde_json::from_str(&s).ok()) {
        Some(i) => i,
        None => {
            throw(&mut env, "bad computer details");
            return 0;
        }
    };
    let mut statuses: Vec<String> = vec![];
    // Status lines are reported as they happen.
    let r = {
        let env_ptr = &mut env as *mut JNIEnv;
        let cb_ref = &cb;
        rt().block_on(ripple::client::connect(id, &info, &[], |s| {
            statuses.push(s.clone());
            // SAFETY: the closure runs on this same thread inside block_on, while `env` is alive.
            let e = unsafe { &mut *env_ptr };
            callback(e, cb_ref, &s);
        }))
    };
    match r {
        Ok(c) => {
            let h = NEXT.fetch_add(1, Ordering::SeqCst);
            conns().lock().unwrap().insert(h, Arc::new(c));
            h as jlong
        }
        Err(e) => {
            throw(&mut env, &e.to_string());
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_connInfo<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong) -> jstring {
    match conn(h) {
        Some(c) => {
            let alive = c.conn.close_reason().is_none();
            let rtt = c.conn.rtt().as_millis() as u64;
            let v = json!({"via": c.via, "remote": c.remote.to_string(), "alive": alive, "rtt_ms": rtt});
            out(&mut env, &v.to_string())
        }
        None => out(&mut env, "{\"alive\":false}"),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_isAlive<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, h: jlong) -> jboolean {
    match conn(h) {
        Some(c) => (c.conn.close_reason().is_none()) as jboolean,
        None => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_request<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, req: JString<'l>) -> jstring {
    let Some(c) = conn(h) else {
        throw(&mut env, "Not connected");
        return std::ptr::null_mut();
    };
    let req: Value = jstr(&mut env, &req).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null);
    let r = rt().block_on(async { tokio::time::timeout(std::time::Duration::from_secs(30), c.request(req)).await });
    match r {
        Ok(Ok(v)) => out(&mut env, &v.to_string()),
        Ok(Err(e)) => {
            throw(&mut env, &e.to_string());
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "The computer didn't answer in time");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_open<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, req: JString<'l>) -> jlong {
    let Some(c) = conn(h) else {
        throw(&mut env, "Not connected");
        return 0;
    };
    let req: Value = jstr(&mut env, &req).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null);
    let r = rt().block_on(async { tokio::time::timeout(std::time::Duration::from_secs(30), c.open(req)).await });
    match r {
        Ok(Ok((send, recv, reply))) => {
            let sh = NEXT.fetch_add(1, Ordering::SeqCst);
            streams().lock().unwrap().insert(sh, Arc::new(Stream { send: tokio::sync::Mutex::new(send), recv: tokio::sync::Mutex::new(recv), reply: reply.to_string() }));
            sh as jlong
        }
        Ok(Err(e)) => {
            throw(&mut env, &e.to_string());
            0
        }
        Err(_) => {
            throw(&mut env, "The computer didn't answer in time");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_streamReply<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, sh: jlong) -> jstring {
    let r = stream(sh).map(|s| s.reply.clone()).unwrap_or_else(|| "{}".into());
    out(&mut env, &r)
}

/// Next frame: first byte is the kind (1 = JSON, 2 = data), the rest the payload. Null at the end.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_read<'l>(env: JNIEnv<'l>, _c: JClass<'l>, sh: jlong) -> jbyteArray {
    let Some(s) = stream(sh) else { return std::ptr::null_mut() };
    let r = rt().block_on(async {
        let mut recv = s.recv.lock().await;
        proto::read_frame(&mut *recv).await
    });
    let mut buf = match r {
        Ok(Some(Frame::Json(v))) => {
            let mut b = vec![proto::KIND_JSON];
            b.extend(v.to_string().into_bytes());
            b
        }
        Ok(Some(Frame::Data(d))) => {
            let mut b = Vec::with_capacity(d.len() + 1);
            b.push(proto::KIND_DATA);
            b.extend(d);
            b
        }
        _ => return std::ptr::null_mut(),
    };
    env.byte_array_from_slice(&std::mem::take(&mut buf)).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_writeData<'l>(env: JNIEnv<'l>, _c: JClass<'l>, sh: jlong, data: JByteArray<'l>, off: jint, len: jint) -> jboolean {
    let Some(s) = stream(sh) else { return 0 };
    let Ok(bytes) = env.convert_byte_array(&data) else { return 0 };
    let end = ((off + len) as usize).min(bytes.len());
    let slice = bytes[(off as usize).min(end)..end].to_vec();
    let r = rt().block_on(async {
        let mut send = s.send.lock().await;
        proto::write_data(&mut *send, &slice).await
    });
    r.is_ok() as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_writeJson<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, sh: jlong, js: JString<'l>) -> jboolean {
    let Some(s) = stream(sh) else { return 0 };
    let v: Value = jstr(&mut env, &js).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null);
    let r = rt().block_on(async {
        let mut send = s.send.lock().await;
        proto::write_json(&mut *send, &v).await
    });
    r.is_ok() as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_closeStream<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, sh: jlong) {
    if let Some(s) = streams().lock().unwrap().remove(&(sh as u64)) {
        rt().spawn(async move {
            let mut send = s.send.lock().await;
            let _ = send.finish();
            let mut recv = s.recv.lock().await;
            let _ = recv.stop(0u32.into());
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_close<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, h: jlong) {
    if let Some(c) = conns().lock().unwrap().remove(&(h as u64)) {
        c.conn.close(0u32.into(), b"bye");
        let ep = c.endpoint.clone();
        rt().spawn(async move {
            let _ = tokio::time::timeout(std::time::Duration::from_secs(2), ep.wait_idle()).await;
        });
    }
}

/// Sends a file from an open file descriptor (content:// URIs) to the computer. Progress goes to
/// `cb.onStatus("progress:<sent>")`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_upload<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, fd: jint, req: JString<'l>, cb: JObject<'l>) -> jstring {
    use std::io::Read;
    use std::os::fd::FromRawFd;
    let Some(c) = conn(h) else {
        throw(&mut env, "Not connected");
        return std::ptr::null_mut();
    };
    let req: Value = jstr(&mut env, &req).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null);
    // The caller keeps ownership of the descriptor: duplicate it.
    let dup = unsafe { libc_dup(fd) };
    if dup < 0 {
        throw(&mut env, "Couldn't read the file");
        return std::ptr::null_mut();
    }
    let mut file = unsafe { std::fs::File::from_raw_fd(dup) };
    let env_ptr = &mut env as *mut JNIEnv;
    let cb_ref = &cb;
    let r: anyhow::Result<Value> = rt().block_on(async {
        let (mut send, mut recv, _head) = c.open(req).await?;
        let mut buf = vec![0u8; 256 * 1024];
        let mut sent: u64 = 0;
        let mut last = std::time::Instant::now();
        loop {
            let n = file.read(&mut buf)?;
            if n == 0 {
                break;
            }
            proto::write_frame(&mut send, proto::KIND_DATA, &buf[..n]).await?;
            sent += n as u64;
            if last.elapsed().as_millis() > 200 {
                last = std::time::Instant::now();
                let e = unsafe { &mut *env_ptr };
                callback(e, cb_ref, &format!("progress:{sent}"));
            }
        }
        proto::write_json(&mut send, &json!({"done": true})).await?;
        let r = proto::check(proto::read_json(&mut recv).await?)?;
        let _ = send.finish();
        Ok(r)
    });
    match r {
        Ok(v) => out(&mut env, &v.to_string()),
        Err(e) => {
            throw(&mut env, &e.to_string());
            std::ptr::null_mut()
        }
    }
}

/// Downloads a file into an open, writable descriptor. Progress via `cb.onStatus("progress:<got>/<size>")`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wonderdesk_ripple_net_Native_download<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, fd: jint, path: JString<'l>, cb: JObject<'l>) -> jlong {
    use std::io::Write;
    use std::os::fd::FromRawFd;
    let Some(c) = conn(h) else {
        throw(&mut env, "Not connected");
        return -1;
    };
    let path = jstr(&mut env, &path).unwrap_or_default();
    let dup = unsafe { libc_dup(fd) };
    if dup < 0 {
        throw(&mut env, "Couldn't write the file");
        return -1;
    }
    let mut file = unsafe { std::fs::File::from_raw_fd(dup) };
    let env_ptr = &mut env as *mut JNIEnv;
    let cb_ref = &cb;
    let r: anyhow::Result<u64> = rt().block_on(async {
        let (_send, mut recv, head) = c.open(json!({"op": "get", "path": path})).await?;
        let size = head["size"].as_u64().unwrap_or(0);
        let mut got = 0u64;
        let mut last = std::time::Instant::now();
        while let Some(f) = proto::read_frame(&mut recv).await? {
            match f {
                Frame::Data(d) => {
                    file.write_all(&d)?;
                    got += d.len() as u64;
                    if last.elapsed().as_millis() > 200 {
                        last = std::time::Instant::now();
                        let e = unsafe { &mut *env_ptr };
                        callback(e, cb_ref, &format!("progress:{got}/{size}"));
                    }
                }
                Frame::Json(v) if v.get("error").is_some() => {
                    anyhow::bail!("{}", v["error"].as_str().unwrap_or("Couldn't open the file"));
                }
                Frame::Json(v) if v["done"] == true => break,
                _ => {}
            }
        }
        file.flush()?;
        Ok(got)
    });
    match r {
        Ok(n) => n as jlong,
        Err(e) => {
            throw(&mut env, &e.to_string());
            -1
        }
    }
}

unsafe extern "C" {
    #[link_name = "dup"]
    fn libc_dup_raw(fd: i32) -> i32;
}
unsafe fn libc_dup(fd: i32) -> i32 {
    unsafe { libc_dup_raw(fd) }
}
