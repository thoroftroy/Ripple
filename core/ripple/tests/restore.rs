// Sessions must come back after the computer restarts, even though a shutdown stops the programs in them before Ripple itself.
// Its own test binary (own process), so pointing XDG_CONFIG_HOME at a scratch folder never touches a real Ripple setup.
use ripple::daemon::Daemon;
use std::time::Duration;

fn saved_names(dir: &std::path::Path) -> Vec<String> {
    let raw = std::fs::read(dir.join("ripple").join("sessions.json")).unwrap_or_default();
    let list: Vec<serde_json::Value> = serde_json::from_slice(&raw).unwrap_or_default();
    list.iter().filter_map(|v| v["name"].as_str().map(String::from)).collect()
}

async fn wait_ended(d: &std::sync::Arc<Daemon>, id: u32) {
    for _ in 0..100 {
        if d.session(id).is_none() { return; }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("session {id} did not end");
}

#[tokio::test]
async fn stopped_sessions_survive_a_shutdown_but_closed_ones_do_not() {
    let dir = std::env::temp_dir().join(format!("ripple-restore-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    // Safe: this test binary runs only this one test, before any other thread reads the environment.
    unsafe { std::env::set_var("XDG_CONFIG_HOME", &dir) };
    let d = Daemon::load().unwrap();
    let bugs = d.new_session(Some("C1-bugs".into()), Some("/tmp".into()), None, 80, 24).unwrap();
    let keep = d.new_session(Some("C2 - Wonder".into()), Some("/tmp".into()), None, 80, 24).unwrap();
    let closed = d.new_session(Some("Closed".into()), Some("/tmp".into()), None, 80, 24).unwrap();
    let exited = d.new_session(Some("Exited".into()), Some("/tmp".into()), Some("true".into()), 80, 24).unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    d.save_sessions();
    wait_ended(&d, exited.id).await;

    // A shutdown stops the program in C1-bugs before Ripple hears about it: it must stay in the list.
    bugs.kill();
    wait_ended(&d, bugs.id).await;
    // Closed on purpose (from the phone or the computer): it must not come back.
    d.mark_closed(closed.id);
    closed.kill();
    wait_ended(&d, closed.id).await;
    d.save_sessions();

    let names = saved_names(&dir);
    assert!(names.contains(&"C1-bugs".to_string()), "a session stopped by the shutdown was dropped: {names:?}");
    assert!(names.contains(&"C2 - Wonder".to_string()), "{names:?}");
    assert!(!names.contains(&"Closed".to_string()), "a session closed on purpose would come back: {names:?}");
    assert!(!names.contains(&"Exited".to_string()), "a program that finished normally would come back: {names:?}");
    keep.kill();
    let _ = std::fs::remove_dir_all(&dir);
}
