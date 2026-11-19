//! Resource usage of this computer and power actions (sleep, lock), read straight from /proc and /sys.

use serde_json::{Value, json};
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Default)]
struct Sample {
    idle: u64,
    total: u64,
    rx: u64,
    tx: u64,
}

static LAST: Mutex<Option<(Instant, Sample)>> = Mutex::new(None);

fn read(path: &str) -> String {
    std::fs::read_to_string(path).unwrap_or_default()
}

fn cpu_counters() -> (u64, u64) {
    let text = read("/proc/stat");
    let line = text.lines().next().unwrap_or("");
    let nums: Vec<u64> = line.split_whitespace().skip(1).filter_map(|x| x.parse().ok()).collect();
    if nums.len() < 5 {
        return (0, 0);
    }
    // idle + iowait count as idle; guest time is already inside user/nice.
    let idle = nums[3] + nums.get(4).copied().unwrap_or(0);
    let total: u64 = nums.iter().take(8).sum();
    (idle, total)
}

fn net_counters() -> (u64, u64) {
    let (mut rx, mut tx) = (0u64, 0u64);
    for l in read("/proc/net/dev").lines().skip(2) {
        let Some((name, rest)) = l.split_once(':') else { continue };
        let name = name.trim();
        if name == "lo" || name.starts_with("docker") || name.starts_with("veth") || name.starts_with("br-") {
            continue;
        }
        let f: Vec<u64> = rest.split_whitespace().filter_map(|x| x.parse().ok()).collect();
        if f.len() >= 9 {
            rx += f[0];
            tx += f[8];
        }
    }
    (rx, tx)
}

fn sample() -> Sample {
    let (idle, total) = cpu_counters();
    let (rx, tx) = net_counters();
    Sample { idle, total, rx, tx }
}

fn meminfo() -> (u64, u64, u64, u64) {
    let text = read("/proc/meminfo");
    let get = |k: &str| -> u64 {
        text.lines().find(|l| l.starts_with(k)).and_then(|l| l.split_whitespace().nth(1)).and_then(|v| v.parse::<u64>().ok()).unwrap_or(0) * 1024
    };
    (get("MemTotal:"), get("MemAvailable:"), get("SwapTotal:"), get("SwapFree:"))
}

/// Hottest CPU-ish temperature in °C.
fn temperature() -> Option<f64> {
    let mut best: Option<f64> = None;
    let mut consider = |v: f64| {
        if (1.0..130.0).contains(&v) {
            best = Some(best.map_or(v, |b: f64| b.max(v)));
        }
    };
    if let Ok(dir) = std::fs::read_dir("/sys/class/hwmon") {
        for h in dir.flatten() {
            let p = h.path();
            let name = read(&p.join("name").to_string_lossy());
            let name = name.trim();
            if !["k10temp", "coretemp", "zenpower", "cpu_thermal", "acpitz"].contains(&name) {
                continue;
            }
            if let Ok(files) = std::fs::read_dir(&p) {
                for f in files.flatten() {
                    let n = f.file_name().to_string_lossy().to_string();
                    if n.starts_with("temp") && n.ends_with("_input") {
                        if let Ok(v) = read(&f.path().to_string_lossy()).trim().parse::<f64>() {
                            consider(v / 1000.0);
                        }
                    }
                }
            }
        }
    }
    best
}

/// Busiest GPU's load in percent (AMD exposes it in sysfs; NVIDIA through nvidia-smi).
fn gpu_busy() -> Option<u32> {
    let mut best: Option<u32> = None;
    if let Ok(dir) = std::fs::read_dir("/sys/class/drm") {
        for c in dir.flatten() {
            let n = c.file_name().to_string_lossy().to_string();
            if n.starts_with("card") && !n.contains('-') {
                if let Ok(v) = read(&c.path().join("device/gpu_busy_percent").to_string_lossy()).trim().parse::<u32>() {
                    best = Some(best.map_or(v, |b| b.max(v)));
                }
            }
        }
    }
    if best.is_none() {
        if let Ok(o) = std::process::Command::new("nvidia-smi").args(["--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"]).output() {
            if o.status.success() {
                best = String::from_utf8_lossy(&o.stdout).lines().filter_map(|l| l.trim().parse::<u32>().ok()).max();
            }
        }
    }
    best
}

fn disk_root() -> (u64, u64) {
    unsafe {
        let mut st: libc::statvfs = std::mem::zeroed();
        let p = std::ffi::CString::new("/").unwrap();
        if libc::statvfs(p.as_ptr(), &mut st) == 0 {
            let bs = st.f_frsize as u64;
            return (st.f_blocks as u64 * bs, st.f_bavail as u64 * bs);
        }
    }
    (0, 0)
}

/// Current usage. Rates come from the difference to the previous call (or a short sample the first time).
pub async fn stats() -> Value {
    let prev = *LAST.lock().unwrap();
    let (since, a) = match prev {
        Some((t, s)) if t.elapsed() < Duration::from_secs(8) && t.elapsed() > Duration::from_millis(400) => (t, s),
        _ => {
            let s = sample();
            tokio::time::sleep(Duration::from_millis(350)).await;
            (Instant::now() - Duration::from_millis(350), s)
        }
    };
    let b = sample();
    let secs = since.elapsed().as_secs_f64().max(0.1);
    *LAST.lock().unwrap() = Some((Instant::now(), b));
    let dt = b.total.saturating_sub(a.total).max(1) as f64;
    let cpu = (100.0 * (1.0 - b.idle.saturating_sub(a.idle) as f64 / dt)).clamp(0.0, 100.0);
    let (mt, ma, st, sf) = meminfo();
    let (dtot, dfree) = disk_root();
    let load: Vec<f64> = read("/proc/loadavg").split_whitespace().take(3).filter_map(|x| x.parse().ok()).collect();
    let uptime = read("/proc/uptime").split_whitespace().next().and_then(|x| x.parse::<f64>().ok()).unwrap_or(0.0);
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    json!({
        "cpu": cpu.round(),
        "cores": cores,
        "mem_total": mt,
        "mem_used": mt.saturating_sub(ma),
        "swap_total": st,
        "swap_used": st.saturating_sub(sf),
        "gpu": gpu_busy(),
        "temp": temperature().map(|t| t.round()),
        "net_down": ((b.rx.saturating_sub(a.rx)) as f64 / secs) as u64,
        "net_up": ((b.tx.saturating_sub(a.tx)) as f64 / secs) as u64,
        "disk_total": dtot,
        "disk_free": dfree,
        "load": load,
        "uptime": uptime as u64,
    })
}

/// Hardware addresses of this computer's network cards, wired first, so a phone can wake it later.
pub fn hardware_addresses() -> Vec<String> {
    let mut wired = vec![];
    let mut wireless = vec![];
    if let Ok(dir) = std::fs::read_dir("/sys/class/net") {
        for i in dir.flatten() {
            let name = i.file_name().to_string_lossy().to_string();
            let p = i.path();
            // Only real cards (they have a device link); skips lo, tailscale, docker, veth, bridges.
            if !p.join("device").exists() {
                continue;
            }
            let mac = read(&p.join("address").to_string_lossy()).trim().to_lowercase();
            if mac.len() != 17 || mac == "00:00:00:00:00:00" {
                continue;
            }
            if p.join("wireless").exists() || name.starts_with("wl") { wireless.push(mac) } else { wired.push(mac) }
        }
    }
    wired.extend(wireless);
    wired
}

/// Makes the wired card (and NetworkManager's profile for it) answer a wake-up packet. Best effort.
fn arm_wake_on_lan() -> Vec<String> {
    let mut notes = vec![];
    let Ok(dir) = std::fs::read_dir("/sys/class/net") else { return notes };
    for i in dir.flatten() {
        let name = i.file_name().to_string_lossy().to_string();
        let p = i.path();
        if !p.join("device").exists() || p.join("wireless").exists() {
            continue;
        }
        // NetworkManager keeps the setting for its profile and applies it when the link comes up; no root needed for the logged-in user.
        let con = std::process::Command::new("nmcli").args(["-t", "-f", "NAME,DEVICE,TYPE", "connection", "show", "--active"]).output().ok().map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default();
        let profile = con.lines().find_map(|l| { let f: Vec<&str> = l.split(':').collect(); (f.len() >= 2 && f[1] == name).then(|| f[0].to_string()) });
        let mut armed = false;
        if let Some(p) = &profile {
            armed = std::process::Command::new("nmcli").args(["connection", "modify", p, "802-3-ethernet.wake-on-lan", "magic"]).status().map(|s| s.success()).unwrap_or(false);
        }
        if !armed {
            armed = std::process::Command::new("ethtool").args(["-s", &name, "wol", "g"]).output().map(|o| o.status.success()).unwrap_or(false);
        }
        if armed {
            notes.push(format!("{name}: wake-on-LAN armed"));
        } else {
            notes.push(format!("{name}: could not arm wake-on-LAN. Turn on Wake-on-LAN in the BIOS and run `nmcli connection modify <connection> 802-3-ethernet.wake-on-lan magic` once."));
        }
    }
    notes
}

/// Runs a power action after a short delay, so the reply reaches the phone first.
pub fn power(action: &str) -> Value {
    let (prog, args): (&str, Vec<&str>) = match action {
        "sleep" | "suspend" => ("systemctl", vec!["suspend"]),
        "lock" => ("loginctl", vec!["lock-session"]),
        "screen_off" => ("sh", vec!["-c", "loginctl lock-session; sleep 1; (xset dpms force off || true) 2>/dev/null"]),
        _ => return json!({"error": "Unknown power action"}),
    };
    let notes = if matches!(action, "sleep" | "suspend") { arm_wake_on_lan() } else { vec![] };
    let prog = prog.to_string();
    let args: Vec<String> = args.into_iter().map(String::from).collect();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(1500));
        let _ = std::process::Command::new(prog).args(args).status();
    });
    json!({"ok": true, "notes": notes})
}
