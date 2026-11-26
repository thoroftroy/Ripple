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

#[tokio::test]
async fn ranged_download_uses_small_frames_without_losing_bytes() {
    let path = std::path::PathBuf::from(format!("/tmp/opencode/ripple-frame-test-{}", std::process::id()));
    let input: Vec<u8> = (0..200_000).map(|n| (n % 251) as u8).collect();
    std::fs::write(&path, &input).unwrap();
    let (mut client, server) = tokio::io::duplex(512 * 1024);
    let req = json!({"path": path.to_string_lossy(), "offset": 100, "len": 140_000});
    let task = tokio::spawn(ripple::daemon::files::get(req, tokio::io::empty(), server));
    let header = proto::read_json(&mut client).await.unwrap();
    assert_eq!(header["size"], 200_000);
    let mut output = vec![];
    let mut frames = 0;
    while let Some(frame) = proto::read_frame(&mut client).await.unwrap() {
        match frame {
            proto::Frame::Data(bytes) => {
                assert!(bytes.len() <= 64 * 1024);
                output.extend(bytes);
                frames += 1;
            }
            proto::Frame::Json(v) if v["done"] == true => break,
            _ => panic!("unexpected frame"),
        }
    }
    task.await.unwrap().unwrap();
    assert!(frames >= 3);
    assert_eq!(output, input[100..140_100]);
    std::fs::remove_file(path).unwrap();
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

fn spawn_with_events(cmd: &str) -> (std::sync::Arc<Session>, tokio::sync::broadcast::Receiver<serde_json::Value>) {
    let (tx, rx) = tokio::sync::broadcast::channel(16);
    let s = Session::spawn(
        9500 + std::process::id() % 400,
        Spawn { name: "q".into(), cwd: None, command: Some(cmd.into()), shell: "/bin/sh".into(), cols: 80, rows: 24, env: vec![] },
        tx,
    )
    .unwrap();
    (s, rx)
}

fn quiet_alerts(rx: &mut tokio::sync::broadcast::Receiver<serde_json::Value>) -> usize {
    let mut n = 0;
    while let Ok(v) = rx.try_recv() {
        if v["event"] == "attention" && v["kind"] == "quiet" {
            n += 1;
        }
    }
    n
}

#[tokio::test(flavor = "multi_thread")]
async fn redraws_after_resizes_are_not_a_finished_job() {
    // A prompt that redraws whenever the window changes size, and does nothing else (an idle terminal on a phone whose keyboard slides up and down).
    let (s, mut rx) = spawn_with_events("trap 'printf \"\\r$ prompt redrawn\"' WINCH; while :; do sleep 0.2; done");
    // Sitting idle first, as a terminal left open does.
    tokio::time::sleep(Duration::from_millis(4500)).await;
    for i in 0..10 {
        s.resize(80 + (i % 2) as u16 * 10, 24);
        tokio::time::sleep(Duration::from_millis(1000)).await;
        s.check_quiet(3);
    }
    tokio::time::sleep(Duration::from_millis(3500)).await;
    s.check_quiet(3);
    assert_eq!(quiet_alerts(&mut rx), 0, "screen: {}", s.peek());
    s.kill();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_long_job_going_quiet_is_reported_once() {
    let (s, mut rx) = spawn_with_events("sleep 3.5; i=0; while [ $i -lt 10 ]; do echo working $i; i=$((i+1)); sleep 1; done; sleep 60");
    tokio::time::sleep(Duration::from_millis(17_500)).await;
    s.check_quiet(3);
    s.check_quiet(3);
    assert_eq!(quiet_alerts(&mut rx), 1, "screen: {}", s.peek());
    s.kill();
}
