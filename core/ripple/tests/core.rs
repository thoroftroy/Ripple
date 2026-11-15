//! Regression tests for the pieces both the computer and the phone depend on.

use ripple::daemon::session::{Session, Spawn};
use ripple::doorbell::DoorbellInfo;
use ripple::{proto, util};
use serde_json::json;
use std::time::Duration;

#[test]
fn doorbell_notes_round_trip_and_reject_other_keys() {
    let a = DoorbellInfo::generate("https://example.invalid/");
    assert_eq!(a.server, "https://example.invalid");
    let msg = json!({"t": "knock", "addrs": ["[2001:db8::1]:47800"], "nonce": "abc"});
    let sealed = a.seal(&msg).unwrap();
    assert!(!sealed.contains("2001:db8"), "addresses must not be readable on the relay");
    assert_eq!(a.open(&sealed).unwrap(), msg);
    let b = DoorbellInfo::generate("https://example.invalid");
    assert!(b.open(&sealed).is_err(), "a different pairing key must not open the note");
    let mut tampered = sealed.into_bytes();
    let i = tampered.len() / 2;
    tampered[i] = if tampered[i] == b'A' { b'B' } else { b'A' };
    assert!(a.open(&String::from_utf8(tampered).unwrap()).is_err());
}

#[test]
fn pairing_codes() {
    assert_eq!(util::normalise_code(" zctrz-sbftd "), "ZCTRZSBFTD");
    let c = util::compare_code("server", "client");
    assert_eq!(c.len(), 7);
    assert_eq!(c, util::compare_code("server", "client"));
    assert_ne!(c, util::compare_code("server", "other"));
    let code = util::readable_code(10);
    assert!(code.chars().all(|ch| !"01OIL".contains(ch)));
    assert!(util::ct_eq("abc", "abc") && !util::ct_eq("abc", "abd") && !util::ct_eq("abc", "ab"));
}

#[tokio::test]
async fn protocol_frames_round_trip() {
    let (mut a, mut b) = tokio::io::duplex(1 << 16);
    let v = json!({"op": "attach", "id": 3, "text": "héllo"});
    proto::write_json(&mut a, &v).await.unwrap();
    proto::write_data(&mut a, &[0u8, 1, 2, 255]).await.unwrap();
    assert_eq!(proto::read_json(&mut b).await.unwrap(), v);
    let f = proto::read_frame(&mut b).await.unwrap().expect("data frame");
    assert_eq!(format!("{f:?}").contains("255"), true);
}

fn spawn(cmd: &str) -> std::sync::Arc<Session> {
    let (tx, _) = tokio::sync::broadcast::channel(16);
    Session::spawn(
        9000 + std::process::id() % 1000,
        Spawn { name: "t".into(), cwd: None, command: Some(cmd.into()), shell: "/bin/sh".into(), cols: 80, rows: 24, env: vec![] },
        tx,
    )
    .unwrap()
}

async fn wait_for(s: &Session, needle: &str) -> bool {
    for _ in 0..50 {
        if s.peek().contains(needle) {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    false
}

#[tokio::test(flavor = "multi_thread")]
async fn sessions_keep_running_without_viewers() {
    let s = spawn("printf 'ready\\n'; read line; echo got:$line; sleep 30");
    assert!(wait_for(&s, "ready").await);
    // Nobody attached: input still reaches the program and the screen model keeps up.
    s.write(b"ping\r").await.unwrap();
    assert!(wait_for(&s, "got:ping").await, "screen: {}", s.peek());
    // A late viewer gets a snapshot that redraws what is on screen.
    let snap = String::from_utf8_lossy(&s.snapshot(false)).to_string();
    assert!(snap.contains("got:ping"));
    s.resize(100, 30);
    assert_eq!(s.size(), (100, 30));
    s.kill();
}

#[tokio::test(flavor = "multi_thread")]
async fn foreground_program_named_through_the_shell() {
    // `sh -c "sleep 30; true"` keeps sleep in the shell's process group (like `fish -c claude`);
    // the tab should still say "sleep", not "sh".
    let s = spawn("sleep 30; true");
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(s.foreground().0, "sleep");
    s.kill();
}
