//! Updating the Linux app from the project's GitHub releases.

use anyhow::{Context, Result, anyhow};
use std::io::Read;

pub const REPO: &str = "thoroftroy/Ripple";

fn newer(a: &str, b: &str) -> bool {
    let p = |s: &str| s.trim_start_matches('v').split('.').map(|x| x.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    p(a) > p(b)
}

/// Returns a message describing what happened.
pub fn run(check_only: bool) -> Result<String> {
    let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(std::time::Duration::from_secs(120))).build().into();
    let mut body = String::new();
    agent
        .get(&format!("https://api.github.com/repos/{REPO}/releases?per_page=10"))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "ripple-updater")
        .call()
        .context("couldn't reach GitHub")?
        .into_body()
        .into_reader()
        .read_to_string(&mut body)?;
    let releases: serde_json::Value = serde_json::from_str(&body)?;
    let rel = releases
        .as_array()
        .and_then(|a| a.iter().find(|r| r["assets"].as_array().map(|x| x.iter().any(|a| a["name"].as_str().map(|n| n.starts_with("ripple-linux-x86_64")).unwrap_or(false))).unwrap_or(false)))
        .ok_or_else(|| anyhow!("no Linux release found"))?;
    // The release tag is the phone app's version; the Linux app's own version is in the file name (ripple-linux-x86_64-0.1.24.tar.gz).
    let tag = rel["assets"]
        .as_array()
        .and_then(|x| x.iter().find_map(|a| a["name"].as_str()?.strip_prefix("ripple-linux-x86_64-")?.strip_suffix(".tar.gz").map(String::from)))
        .unwrap_or_else(|| "0".into());
    if !newer(&tag, ripple::VERSION) {
        return Ok(format!("Ripple {} is up to date.", ripple::VERSION));
    }
    if check_only {
        return Ok(format!("Ripple {tag} is available (you have {}). Run `ripple update`.", ripple::VERSION));
    }
    let asset = rel["assets"].as_array().unwrap().iter().find(|a| a["name"].as_str().unwrap_or("").starts_with("ripple-linux-x86_64")).unwrap();
    let url = asset["browser_download_url"].as_str().unwrap_or("");
    let tmp = std::env::temp_dir().join(format!("ripple-update-{}", std::process::id()));
    std::fs::create_dir_all(&tmp)?;
    let tarball = tmp.join("ripple.tar.gz");
    let mut data = vec![];
    agent.get(url).header("User-Agent", "ripple-updater").call()?.into_body().into_reader().read_to_end(&mut data)?;
    std::fs::write(&tarball, &data)?;
    let ok = std::process::Command::new("tar").arg("-xzf").arg(&tarball).arg("-C").arg(&tmp).status()?.success();
    if !ok {
        return Err(anyhow!("couldn't unpack the download"));
    }
    let installer = std::fs::read_dir(&tmp)?.filter_map(|e| e.ok()).map(|e| e.path().join("install.sh")).find(|p| p.exists()).ok_or_else(|| anyhow!("the download has no installer"))?;
    let ok = std::process::Command::new("sh").arg(&installer).arg("--update").status()?.success();
    let _ = std::fs::remove_dir_all(&tmp);
    if !ok {
        return Err(anyhow!("the installer failed"));
    }
    Ok(format!("Updated to Ripple {tag}. New windows use it now; running sessions keep going until the service restarts."))
}
