"""End to end monitor test against a private Ripple daemon.

Run after `cargo build -p ripple` with Python 3. No real session, phone,
power action, or user configuration is touched.
"""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(os.environ.get("RIPPLE_TEST_BINARY", str(ROOT / "target/debug/ripple")))
CLIENT = Path(os.environ.get("RIPPLE_TEST_CLIENT_BINARY", str(BINARY)))


class MonitorDaemonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ripple-monitor-test-", dir="/tmp/opencode")
        self.root = Path(self.temp.name)
        (self.root / "config/ripple").mkdir(parents=True)
        (self.root / "run").mkdir()
        (self.root / "home").mkdir()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        self.port = port
        config = {"port": port, "doorbell_enabled": False, "upnp": False, "auto_update": False,
                  "restore_sessions": False, "notify_quiet_secs": 0, "shared_dir": str(self.root / "shared")}
        (self.root / "config/ripple/config.json").write_text(json.dumps(config))
        env = dict(os.environ, HOME=str(self.root / "home"), XDG_CONFIG_HOME=str(self.root / "config"),
                   XDG_RUNTIME_DIR=str(self.root / "run"), SHELL="/bin/sh")
        env.pop("RIPPLE_SOCKET", None)
        env.pop("INVOCATION_ID", None)
        self.env = env
        self.daemon = subprocess.Popen([str(BINARY), "daemon"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.wait_for(lambda: (self.root / "run/ripple.sock").exists())

    def tearDown(self):
        self.daemon.terminate()
        try:
            self.daemon.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            self.daemon.kill()
            self.daemon.communicate()
        self.temp.cleanup()

    def wait_for(self, predicate, seconds=10):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if predicate():
                return
            if self.daemon.poll() is not None:
                self.fail("Private daemon stopped: " + self.daemon.stderr.read().decode())
            time.sleep(.1)
        self.fail("Timed out waiting for the private daemon")

    def request(self, value):
        def read_exact(sock, length):
            data = b""
            while len(data) < length:
                part = sock.recv(length - len(data))
                if not part:
                    raise EOFError()
                data += part
            return data
        data = json.dumps(value).encode()
        with socket.socket(socket.AF_UNIX) as sock:
            sock.settimeout(3)
            sock.connect(str(self.root / "run/ripple.sock"))
            sock.sendall(b"\x01" + struct.pack(">I", len(data)) + data)
            head = read_exact(sock, 5)
            return json.loads(read_exact(sock, struct.unpack(">I", head[1:])[0]))

    def start(self, run, action, command=""):
        return self.request({"op": "monitor_start", "mode": "exit_success", "condition": "all",
                             "action": action, "command": command, "sessions": [], "run_command": run,
                             "cwd": str(self.root / "home")})

    def item(self, monitor_id):
        return next(x for x in self.request({"op": "monitor_list"})["monitors"] if x["id"] == monitor_id)

    def subject_status(self, monitor_id):
        sessions = self.item(monitor_id).get("sessions", [])
        return sessions[0]["status"] if sessions else ""

    def test_success_failure_cancel_and_countdown(self):
        marker = self.root / "action-ran"
        command = "printf success > " + str(marker)
        bad = self.start("exit 9", "command", command)
        self.assertTrue(bad["ok"])
        signalled = self.start("kill -9 $$", "command", command)
        self.assertTrue(signalled["ok"])
        good = self.start("sleep 1; exit 0", "command", command)
        self.assertTrue(good["ok"])
        cancel = self.start("sleep 1; exit 0", "command", command)
        self.assertTrue(cancel["ok"])
        self.wait_for(lambda: self.item(good["id"])["status"] == "countdown")
        self.assertFalse(marker.exists(), "action must not execute during the countdown")
        self.assertTrue(self.request({"op": "monitor_cancel", "id": cancel["id"]})["ok"])
        self.wait_for(lambda: self.item(good["id"])["status"] == "completed", seconds=40)
        self.wait_for(marker.exists)
        self.assertEqual(marker.read_text(), "success")
        self.assertEqual(self.item(cancel["id"])["status"], "cancelled")
        self.assertEqual(self.item(bad["id"])["status"], "watching")
        self.assertIn("code 9", self.item(bad["id"])["sessions"][0]["status"])
        self.assertIn("code 137", self.item(signalled["id"])["sessions"][0]["status"])
        self.assertIn("error", self.request({"op": "monitor_cancel", "id": good["id"]}))

    def test_validation_does_not_start_a_command(self):
        marker = self.root / "must-not-exist"
        response = self.request({"op": "monitor_start", "mode": "exit_success", "condition": "all",
                                 "action": "command", "command": "", "sessions": [],
                                 "run_command": "touch " + str(marker), "cwd": str(self.root)})
        self.assertIn("error", response)
        time.sleep(.5)
        self.assertFalse(marker.exists())

    def test_claude_limit_cannot_trigger_action(self):
        fake = self.root / "claude"
        fake.symlink_to("/usr/bin/python3")
        script = self.root / "fake_claude.py"
        script.write_text(
            "import time\n"
            "print('Usage limit reached · limit resets next week', flush=True)\n"
            "print('Continuing automatically at reset · Esc to cancel', flush=True)\n"
            "time.sleep(20)\n"
        )
        session = self.request({"op": "new", "name": "Fake Claude", "cwd": str(self.root),
                                "command": f"exec {fake} -u {script}"})
        sid = session["id"]
        self.wait_for(lambda: self.request({"op": "peek", "id": sid})["session"]["program"] == "claude")
        marker = self.root / "must-not-run"
        result = self.request({"op": "monitor_start", "mode": "claude", "condition": "all", "action": "command",
                               "command": f"touch {marker}", "sessions": [sid]})
        self.assertTrue(result["ok"])
        self.wait_for(lambda: "automatic continuation shown" in self.subject_status(result["id"]))
        self.assertFalse(marker.exists())
        self.assertEqual(self.item(result["id"])["status"], "watching")
        self.assertTrue(self.request({"op": "monitor_cancel", "id": result["id"]})["ok"])
        self.request({"op": "kill", "id": sid})

    def test_limit_left_in_transcript_is_not_completion(self):
        fake = self.root / "claude"
        fake.symlink_to("/usr/bin/python3")
        script = self.root / "fake_claude.py"
        script.write_text(
            "import time\n"
            "print('Working · esc to interrupt', flush=True)\n"
            "time.sleep(4)\n"
            "print('\\x1b[2J\\x1b[H', end='', flush=True)\n"
            "print('Usage limit reached', flush=True)\n"
            "print('────────────', flush=True)\n"
            "print('❯', flush=True)\n"
            "print('────────────', flush=True)\n"
            "print('auto mode on (shift+tab to cycle)', flush=True)\n"
            "time.sleep(20)\n"
        )
        session = self.request({"op": "new", "name": "Fake Claude", "cwd": str(self.root),
                                "command": f"exec {fake} -u {script}"})
        sid = session["id"]
        self.wait_for(lambda: self.request({"op": "peek", "id": sid})["session"]["program"] == "claude")
        marker = self.root / "must-not-run"
        result = self.request({"op": "monitor_start", "mode": "claude", "condition": "all", "action": "command",
                               "command": f"touch {marker}", "sessions": [sid]})
        self.assertTrue(result["ok"])
        self.wait_for(lambda: self.subject_status(result["id"]) == "Working")
        self.wait_for(lambda: "continuation not confirmed" in self.subject_status(result["id"]))
        self.assertFalse(marker.exists())
        self.assertEqual(self.item(result["id"])["status"], "watching")
        self.assertTrue(self.request({"op": "monitor_cancel", "id": result["id"]})["ok"])
        self.request({"op": "kill", "id": sid})

    def test_quic_pair_and_file_transfer(self):
        code = self.request({"op": "pair_code"})["code"]
        subprocess.run([str(CLIENT), "remote", "pair", f"127.0.0.1:{self.port}", code],
                       env=self.env, check=True, capture_output=True, timeout=15)
        source = self.root / "source.bin"
        destination = self.root / "received.bin"
        data = bytes(range(256)) * 8192
        source.write_bytes(data)
        subprocess.run([str(CLIENT), "remote", "get", str(source), str(destination)],
                       env=self.env, check=True, capture_output=True, timeout=15)
        self.assertEqual(destination.read_bytes(), data)


if __name__ == "__main__":
    unittest.main(verbosity=2)
