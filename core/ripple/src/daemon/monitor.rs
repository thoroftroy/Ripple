//! One-shot monitors owned by the daemon. They are deliberately not restored
//! after a daemon restart: an old action must never run against a new session.
use super::{Daemon, session::Session};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashSet};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

const SETTLE: Duration = Duration::from_secs(20);
const COUNTDOWN: Duration = Duration::from_secs(30);

#[derive(Default)]
pub struct Monitors {
    next: AtomicU64,
    items: Mutex<BTreeMap<u64, Value>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ClaudeState {
    Working,
    Ready,
    Limit,
    LimitAuto,
    Unknown,
}

fn claude_state(text: &str, program: &str) -> ClaudeState {
    if !matches!(program, "claude" | "claude-code") {
        return ClaudeState::Unknown;
    }
    let lines: Vec<&str> = text.lines().collect();
    let visible = &lines[lines.len().saturating_sub(14)..];
    let footer = visible.join(" ").to_lowercase();
    let footer = footer.split_whitespace().collect::<Vec<_>>().join(" ");
    // Claude often leaves old limit messages in the transcript. Prefer the
    // live status area below the last prompt separator when one is present.
    let status_start = visible.iter().rposition(|line| line.chars().filter(|c| *c == '─').count() >= 5)
        .map(|index| index + 1).unwrap_or(0);
    let status = visible[status_start..].join(" ").to_lowercase();
    let status = status.split_whitespace().collect::<Vec<_>>().join(" ");
    // An active automatic wait takes priority over a generic active indicator.
    if (status.contains("continuing automatically at ") || status.contains("continuing automatically in ")
        || (status.contains("continuing shortly") && status.contains("esc to cancel")))
        && (status.contains("limit") || status.contains("esc to cancel"))
    {
        return ClaudeState::LimitAuto;
    }
    if status.contains("esc to interrupt") {
        return ClaudeState::Working;
    }
    if status.contains("press enter to continue") {
        return ClaudeState::Unknown;
    }
    if status.contains("hit your") && status.contains("limit")
        || status.contains("limit reached") || status.contains("usage limit")
        && (status.contains("reset") || status.contains("reached"))
    {
        return ClaudeState::Limit;
    }
    // A previous limit notice can remain above the input after a restart or
    // redraw, even when the live countdown is missing. It is ambiguous, so
    // never treat that screen as completion.
    if footer.contains("hit your") && footer.contains("limit")
        || footer.contains("limit reached") || footer.contains("usage limit")
        && (footer.contains("reset") || footer.contains("reached"))
    {
        return ClaudeState::Limit;
    }
    // Do not treat dialogs, interruptions, failures or running subagents as a
    // finished response, even when an input prompt is visible behind them.
    if (status.contains('←') && status.contains("agent"))
        || ["permission", "approve", "do you want to", "press enter to continue",
        "interrupted", "error", "failed", "esc to cancel", "retrying",
        "agent running", "agents running"]
        .iter().any(|s| footer.contains(s))
    {
        return ClaudeState::Unknown;
    }
    if lines.iter().rev().take(14).any(|line| line.trim_start().starts_with('❯'))
        && ["shift+tab to cycle", "bypass permissions", "auto mode on", "accept edits", "plan mode", "? for shortcuts"]
            .iter().any(|s| footer.contains(s))
    {
        return ClaudeState::Ready;
    }
    ClaudeState::Unknown
}

struct Watched {
    session: Arc<Session>,
    busy: bool,
    ready_since: Option<Instant>,
    limit: Option<ClaudeState>,
    done: bool,
    label: String,
}

impl Monitors {
    pub fn list(&self) -> Value {
        json!({"monitors": self.items.lock().unwrap().values().cloned().collect::<Vec<_>>()})
    }

    pub fn cancel(&self, id: u64) -> Value {
        let mut items = self.items.lock().unwrap();
        match items.get_mut(&id) {
            Some(item) if item["status"] == "watching" || item["status"] == "countdown" => {
                item["status"] = json!("cancelled");
                json!({"ok": true})
            }
            Some(_) => json!({"error": "This monitor is no longer active"}),
            None => json!({"error": "Monitor not found"}),
        }
    }

    fn update(&self, id: u64, status: &str, subjects: &[Watched], remaining: Option<u64>) -> bool {
        let mut items = self.items.lock().unwrap();
        let Some(item) = items.get_mut(&id) else { return false };
        if item["status"] != "watching" && item["status"] != "countdown" { return false; }
        item["status"] = json!(status);
        item["remaining"] = json!(remaining);
        item["sessions"] = json!(subjects.iter().map(|s| json!({
            "id": s.session.id, "name": s.session.display_name(), "status": s.label,
        })).collect::<Vec<_>>());
        true
    }

    pub fn start(d: &Arc<Daemon>, req: &Value) -> Value {
        let mode = req["mode"].as_str().unwrap_or("");
        let action = req["action"].as_str().unwrap_or("");
        let condition = req["condition"].as_str().unwrap_or("");
        let command = req["command"].as_str().unwrap_or("").trim();
        if !matches!(mode, "claude" | "exit_success")
            || !matches!(action, "notify" | "sleep" | "command")
            || !matches!(condition, "any" | "all")
        {
            return json!({"error": "Invalid monitor settings"});
        }
        if action == "command" && (command.is_empty() || command.len() > 16384 || command.contains('\0')) {
            return json!({"error": "Enter a command to run"});
        }
        let Some(ids) = req["sessions"].as_array() else { return json!({"error": "Select at least one session"}) };
        let run = req["run_command"].as_str().unwrap_or("").trim();
        if !run.is_empty() && (mode != "exit_success" || !ids.is_empty() || run.len() > 16384 || run.contains('\0')) {
            return json!({"error": "A new command must be watched on its own using successful command exit"});
        }
        if run.is_empty() && (ids.is_empty() || ids.len() > 32) {
            return json!({"error": "Select between one and 32 sessions"});
        }
        if d.monitors.items.lock().unwrap().values().filter(|v| v["status"] == "watching" || v["status"] == "countdown").count() >= 32 {
            return json!({"error": "Too many active monitors"});
        }
        let mut seen = HashSet::new();
        let mut subjects = Vec::new();
        let mut started_session = None;
        if !run.is_empty() {
            let cwd = req["cwd"].as_str().map(str::trim).filter(|s| !s.is_empty());
            if cwd.is_some_and(|s| !std::path::Path::new(s).is_dir()) {
                return json!({"error": "The command folder does not exist"});
            }
            let session = match d.new_session(Some("Monitored command".into()), cwd.map(String::from), Some(run.to_string()), 100, 30) {
                Ok(session) => session,
                Err(error) => return json!({"error": error.to_string()}),
            };
            started_session = Some(session.id);
            subjects.push(Watched { session, busy: false, ready_since: None, limit: None, done: false, label: "Starting".into() });
        }
        for id in ids {
            let Some(id) = id.as_u64().and_then(|n| u32::try_from(n).ok()) else { return json!({"error": "Invalid session id"}) };
            if !seen.insert(id) { return json!({"error": "A session was selected twice"}); }
            let Some(session) = d.session(id) else { return json!({"error": "A selected session has ended"}) };
            if mode == "exit_success" && session.command.is_none() {
                return json!({"error": "Exit status is only available for a session started with a command. Start a new session with its Run field."});
            }
            subjects.push(Watched { session, busy: false, ready_since: None, limit: None, done: false, label: "Starting".into() });
        }
        let id = d.monitors.next.fetch_add(1, Ordering::Relaxed) + 1;
        {
            let mut items = d.monitors.items.lock().unwrap();
            items.insert(id, json!({"id": id, "mode": mode, "condition": condition, "action": action,
                "status": "watching", "sessions": [], "created": crate::util::now_secs()}));
        }
        let d = d.clone();
        let mode = mode.to_owned();
        let action = action.to_owned();
        let condition = condition.to_owned();
        let command = command.to_owned();
        tokio::spawn(async move {
            let mut deadline: Option<Instant> = None;
            loop {
                for watched in &mut subjects {
                    if mode == "exit_success" {
                        match *watched.session.ended.borrow() {
                            Some(0) => { watched.done = true; watched.label = "Command succeeded".into(); }
                            Some(code) => {
                                watched.done = false;
                                let label = format!("Command ended with code {code}");
                                if watched.label != label {
                                    let text = format!("{}: {label}", watched.session.display_name());
                                    let _ = d.events.send(json!({"event": "attention", "session": watched.session.id,
                                        "name": "Ripple Monitor", "text": text, "kind": "message"}));
                                }
                                watched.label = label;
                            }
                            None => { watched.label = "Running".into(); }
                        }
                    } else {
                        let (program, _) = watched.session.foreground();
                        let kind = claude_state(&watched.session.peek(), &program);
                        match kind {
                            ClaudeState::Working => {
                                watched.busy = true; watched.ready_since = None; watched.done = false;
                                watched.limit = None; watched.label = "Working".into();
                            }
                            ClaudeState::Ready if watched.busy => {
                                let since = *watched.ready_since.get_or_insert_with(Instant::now);
                                watched.done = since.elapsed() >= SETTLE;
                                watched.label = if watched.done { "Response appears finished" } else { "Checking response" }.into();
                            }
                            ClaudeState::Limit | ClaudeState::LimitAuto => {
                                watched.busy = false; watched.ready_since = None; watched.done = false;
                                watched.label = if kind == ClaudeState::LimitAuto { "Usage limit, automatic continuation shown" } else { "Usage limit, continuation not confirmed" }.into();
                                if watched.limit.is_none() || (watched.limit == Some(ClaudeState::Limit) && kind == ClaudeState::LimitAuto) {
                                    let text = format!("{}: {}", watched.session.display_name(), watched.label);
                                    let _ = d.events.send(json!({"event": "attention", "session": watched.session.id,
                                        "name": "Ripple Monitor", "text": text, "kind": "message"}));
                                }
                                watched.limit = Some(kind);
                            }
                            _ => {
                                watched.busy = false; watched.ready_since = None; watched.done = false; watched.limit = None;
                                watched.label = "Waiting or unknown".into();
                            }
                        }
                    }
                }
                let ready = if condition == "all" { subjects.iter().all(|s| s.done) } else { subjects.iter().any(|s| s.done) };
                if ready && deadline.is_none() {
                    deadline = Some(Instant::now() + if action == "notify" { Duration::ZERO } else { COUNTDOWN });
                } else if !ready {
                    deadline = None;
                }
                let due = deadline.is_some_and(|t| Instant::now() >= t);
                let remaining = deadline.map(|t| t.saturating_duration_since(Instant::now()).as_secs());
                // The transition to "running" and cancellation take the same lock.
                if !d.monitors.update(id, if due { "running" } else if deadline.is_some() { "countdown" } else { "watching" }, &subjects, remaining) {
                    break;
                }
                if due {
                    let names = subjects.iter().filter(|s| s.done).map(|s| s.session.display_name()).collect::<Vec<_>>().join(", ");
                    let text = format!("Monitor condition met: {names}");
                    let _ = d.events.send(json!({"event": "attention", "session": 0, "name": "Ripple Monitor", "text": text, "kind": "message"}));
                    let result = match action.as_str() {
                        "sleep" => super::stats::power("sleep"),
                        "command" => match std::process::Command::new("/bin/sh").args(["-lc", &command])
                            .stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null())
                            .stderr(std::process::Stdio::null()).spawn() {
                            Ok(mut child) => {
                                match tokio::task::spawn_blocking(move || child.wait()).await {
                                    Ok(Ok(status)) if status.success() => json!({"ok": true, "code": 0}),
                                    Ok(Ok(status)) => json!({"error": format!("Action command failed: {status}")}),
                                    Ok(Err(error)) => json!({"error": error.to_string()}),
                                    Err(error) => json!({"error": error.to_string()}),
                                }
                            }
                            Err(e) => json!({"error": e.to_string()}),
                        },
                        _ => json!({"ok": true}),
                    };
                    if let Some(item) = d.monitors.items.lock().unwrap().get_mut(&id) {
                        item["status"] = json!(if result.get("error").is_some() { "failed" } else { "completed" });
                        item["result"] = result.clone();
                    }
                    if let Some(error) = result["error"].as_str() {
                        let _ = d.events.send(json!({"event": "attention", "session": 0, "name": "Ripple Monitor",
                            "text": format!("Monitor action failed: {error}"), "kind": "message"}));
                    }
                    break;
                }
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        });
        json!({"ok": true, "id": id, "session": started_session})
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conservative_claude_screen_detection() {
        let ready = "────────\n❯ \n────────\n⏵⏵ auto mode on (shift+tab to cycle)";
        assert_eq!(claude_state(ready, "claude"), ClaudeState::Ready);
        assert_eq!(claude_state(&format!("{ready} · esc to interrupt"), "claude"), ClaudeState::Working);
        assert_eq!(claude_state(ready, "fish"), ClaudeState::Unknown);
        assert_eq!(claude_state(&format!("Interrupted\n{ready}"), "claude"), ClaudeState::Unknown);
        assert_eq!(claude_state("Usage limit reached · resets 3pm\nContinuing automatically at 3pm · esc to cancel", "claude"), ClaudeState::LimitAuto);
        assert_eq!(claude_state("You've hit your monthly spend limit · your 5-hour limit resets 3:50am\n\
            Usage limit reached · set to continue automatically at 3:50am\n\
            ❯\n⚠ Usage limit reached · limit resets 3:50am\n\
            Continuing automatically at 3:50am · Esc to cancel · /usage-credits to continue now\n\
            ⏵⏵ auto mode on (shift+tab to cycle) · ← 1 agent", "claude"), ClaudeState::LimitAuto);
        assert_eq!(claude_state("You've hit your 5-hour limit · resets 3pm", "claude"), ClaudeState::Limit);
        assert_eq!(claude_state("You've hit your weekly limit · resets Monday", "claude"), ClaudeState::Limit);
        assert_eq!(claude_state("You've hit your monthly spend limit", "claude"), ClaudeState::Limit);
        assert_eq!(claude_state("Your usage limit has reset · Press enter to continue", "claude"), ClaudeState::Unknown);
        assert_eq!(claude_state("Usage limit reached · set to continue automatically at 3pm\n────────\n❯\n────────\n⏵⏵ auto mode on (shift+tab to cycle)", "claude"), ClaudeState::Limit);
        assert_eq!(claude_state(&format!("Error: request failed\n{ready}"), "claude"), ClaudeState::Unknown);
    }
}
