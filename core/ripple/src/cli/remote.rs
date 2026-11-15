//! `ripple remote …`: the phone side of Ripple, from a Linux terminal. Useful for connecting from a
//! laptop, and for testing the whole path (pairing, doorbell, attach) without a phone.

use anyhow::{Result, anyhow, bail};
use ripple::client;
use ripple::identity::Identity;
use ripple::net::ComputerInfo;
use serde_json::json;
use std::path::PathBuf;

fn dir() -> PathBuf {
    ripple::daemon::config_dir().with_file_name("ripple-remote")
}

fn load() -> Vec<ComputerInfo> {
    std::fs::read(dir().join("computers.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

fn save(list: &[ComputerInfo]) -> Result<()> {
    std::fs::create_dir_all(dir())?;
    ripple::identity::write_private(&dir().join("computers.json"), &serde_json::to_vec_pretty(list)?)
}

fn identity() -> Result<Identity> {
    Identity::load_or_create(&dir(), &format!("{}-remote", ripple::util::hostname()))
}

fn pick(list: &[ComputerInfo], name: Option<&str>) -> Result<ComputerInfo> {
    match name {
        Some(n) => list.iter().find(|c| c.name.eq_ignore_ascii_case(n)).cloned().ok_or_else(|| anyhow!("no paired computer called {n}")),
        None => list.first().cloned().ok_or_else(|| anyhow!("pair first: ripple remote pair ADDRESS [CODE]")),
    }
}

fn flag(rest: &mut Vec<String>, name: &str) -> Option<String> {
    let i = rest.iter().position(|a| a == name)?;
    rest.remove(i);
    if i < rest.len() { Some(rest.remove(i)) } else { None }
}

pub async fn connect(name: Option<&str>, quiet: bool) -> Result<(client::Client, ComputerInfo)> {
    let id = identity()?;
    let mut list = load();
    let info = pick(&list, name)?;
    let c = client::connect(&id, &info, &[], |s| if !quiet { eprintln!("{s}") }).await?;
    if !quiet {
        eprintln!("Connected over {} ({})", c.via, c.remote);
    }
    // Refresh what we know (addresses change).
    if let Ok(r) = c.request(json!({"op": "info"})).await {
        if let Ok(fresh) = serde_json::from_value::<ComputerInfo>(r["computer"].clone()) {
            if fresh.fp == info.fp {
                for x in list.iter_mut().filter(|x| x.fp == info.fp) {
                    *x = fresh.clone();
                }
                let _ = save(&list);
            }
        }
    }
    Ok((c, info))
}

pub async fn run(mut rest: Vec<String>) -> Result<i32> {
    let computer = flag(&mut rest, "--computer");
    let sub = if rest.is_empty() { String::new() } else { rest.remove(0) };
    match sub.as_str() {
        "discover" => {
            for f in ripple::net::discover(std::time::Duration::from_secs(2)).await {
                println!("{}  {}  {}", f.name, f.addr, &f.fp[..16]);
            }
            Ok(0)
        }
        "pair" => {
            let id = identity()?;
            let target = rest.first().cloned().ok_or_else(|| anyhow!("ripple remote pair ADDRESS [CODE] | ripple://… link"))?;
            let (addr, pin, code, bell) = if target.starts_with("ripple://") {
                let q = target.split_once('?').map(|x| x.1).unwrap_or("");
                let get = |k: &str| q.split('&').find_map(|kv| kv.strip_prefix(&format!("{k}="))).map(|v| percent_decode(v));
                let port = get("p").unwrap_or_else(|| ripple::DEFAULT_PORT.to_string());
                let addrs = get("a").unwrap_or_default().split(',').map(|a| if a.contains(':') && !a.starts_with('[') && a.matches(':').count() == 1 { a.to_string() } else if a.starts_with('[') { a.to_string() } else { format!("{a}:{port}") }).collect::<Vec<_>>().join(",");
                let bell = match (get("bs"), get("bt"), get("bk")) {
                    (Some(server), Some(topic), Some(key)) => Some(ripple::doorbell::DoorbellInfo { server, topic, key }),
                    _ => None,
                };
                (addrs, get("f"), get("c"), bell)
            } else {
                (target, None, rest.get(1).cloned(), None)
            };
            let me = format!("{} (Linux)", ripple::util::hostname());
            let info = client::pair(&id, &addr, pin, code.clone(), &me, bell, |cmp| {
                if code.is_none() {
                    eprintln!("Check the computer shows {cmp}, and allow it there.");
                }
            })
            .await?;
            let mut list = load();
            list.retain(|c| c.fp != info.fp);
            list.insert(0, info.clone());
            save(&list)?;
            println!("Paired with {}", info.name);
            Ok(0)
        }
        "list" | "computers" => {
            for c in load() {
                println!("{}  {}  {}", c.name, c.addrs.join(", "), if c.doorbell.is_some() { "doorbell" } else { "" });
            }
            Ok(0)
        }
        "ls" => {
            let (c, _) = connect(computer.as_deref(), false).await?;
            let r = c.request(json!({"op": "sessions"})).await?;
            for s in r["sessions"].as_array().cloned().unwrap_or_default() {
                println!("{:>3}  {:<20} {:<12} {}", s["id"], s["name"].as_str().unwrap_or(""), s["program"].as_str().unwrap_or(""), s["cwd"].as_str().unwrap_or(""));
            }
            Ok(0)
        }
        "attach" | "a" => {
            let (c, _) = connect(computer.as_deref(), false).await?;
            let (cols, rows) = super::term_size();
            let mut req = json!({"op": "attach", "cols": cols, "rows": rows, "history": true});
            match rest.first().map(|s| s.as_str()) {
                Some("--new") => {}
                Some(k) => req["id"] = json!(k.parse::<u64>().map_err(|_| anyhow!("give a session number"))?),
                None => {
                    let r = c.request(json!({"op": "sessions"})).await?;
                    if let Some(s) = r["sessions"].as_array().and_then(|l| l.last().cloned()) {
                        req["id"] = s["id"].clone();
                    }
                }
            }
            let (send, recv) = c.conn.open_bi().await?;
            let code = super::bridge(recv, send, req).await?;
            c.conn.close(0u32.into(), b"bye");
            Ok(code)
        }
        "get" => {
            let (c, _) = connect(computer.as_deref(), true).await?;
            let path = rest.first().ok_or_else(|| anyhow!("which file?"))?;
            let (_s, mut recv, head) = c.open(json!({"op": "get", "path": path})).await?;
            let name = head["name"].as_str().unwrap_or("download").to_string();
            let dest = rest.get(1).cloned().unwrap_or(name);
            let mut f = std::fs::File::create(&dest)?;
            let mut got = 0u64;
            use std::io::Write;
            while let Some(fr) = ripple::proto::read_frame(&mut recv).await? {
                match fr {
                    ripple::proto::Frame::Data(d) => {
                        f.write_all(&d)?;
                        got += d.len() as u64;
                    }
                    ripple::proto::Frame::Json(v) if v["done"] == true => break,
                    _ => {}
                }
            }
            println!("{dest}: {got} bytes");
            Ok(0)
        }
        "put" => {
            let (c, _) = connect(computer.as_deref(), true).await?;
            let src = rest.first().ok_or_else(|| anyhow!("which file?"))?;
            let data = std::fs::read(src)?;
            let name = std::path::Path::new(src).file_name().unwrap().to_string_lossy().to_string();
            let (mut send, mut recv, head) = c.open(json!({"op": "put", "path": rest.get(1).cloned().unwrap_or_default(), "name": name, "size": data.len()})).await?;
            ripple::proto::write_data(&mut send, &data).await?;
            ripple::proto::write_json(&mut send, &json!({"done": true})).await?;
            let done = ripple::proto::check(ripple::proto::read_json(&mut recv).await?)?;
            println!("Saved {} ({} bytes)", done["path"].as_str().unwrap_or(head["path"].as_str().unwrap_or("")), done["size"]);
            Ok(0)
        }
        "run" => {
            // One request, printed as JSON (for scripts and tests).
            let (c, _) = connect(computer.as_deref(), true).await?;
            let v: serde_json::Value = serde_json::from_str(&rest.join(" "))?;
            println!("{}", c.request(v).await?);
            Ok(0)
        }
        "forget" => {
            let mut list = load();
            let n = rest.join(" ");
            list.retain(|c| !c.name.eq_ignore_ascii_case(&n));
            save(&list)?;
            Ok(0)
        }
        _ => bail!("ripple remote discover | pair ADDR [CODE] | list | ls | attach [ID|--new] | get PATH [DEST] | put FILE [DEST] | run JSON  (--computer NAME)"),
    }
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = vec![];
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() + 0 && i + 2 <= b.len() - 1 {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}
