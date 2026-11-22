"""Tests the update button without starting, updating, or restarting Ripple."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location("ripple_terminal", Path(__file__).with_name("ripple_terminal.py"))
terminal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(terminal)


class ImmediateThread:
    def __init__(self, target, daemon):
        self.target = target

    def start(self):
        self.target()


class UpdateButtonTest(unittest.TestCase):
    def run_case(self, confirmed, responses):
        status = SimpleNamespace(set_text=mock.Mock())
        window = SimpleNamespace(updating=False, status=status)
        window._reload_after_update = lambda: terminal.Window._reload_after_update(window)
        window._update_failed = lambda msg: terminal.Window._update_failed(window, msg)
        dialog = mock.Mock()
        dialog.run.return_value = terminal.Gtk.ResponseType.OK if confirmed else terminal.Gtk.ResponseType.CANCEL
        with mock.patch.object(terminal.Gtk, "MessageDialog", return_value=dialog), \
                mock.patch.object(terminal.threading, "Thread", ImmediateThread), \
                mock.patch.object(terminal.GLib, "idle_add", side_effect=lambda f, *a: f(*a)), \
                mock.patch.object(terminal.subprocess, "run", side_effect=responses) as run, \
                mock.patch.object(terminal.os, "execl") as execl:
            terminal.Window.update_and_restart(window)
            return run.call_args_list, execl.call_count, window, status

    def test_cancel_never_runs_any_command(self):
        calls, reloads, _, _ = self.run_case(False, [])
        self.assertEqual(calls, [])
        self.assertEqual(reloads, 0)

    def test_failed_update_does_not_restart_service(self):
        failed = SimpleNamespace(returncode=1, stderr="offline", stdout="")
        calls, reloads, window, status = self.run_case(True, [failed])
        self.assertEqual(len(calls), 1)
        self.assertEqual(reloads, 0)
        self.assertFalse(window.updating)
        self.assertIn("offline", status.set_text.call_args.args[0])

    def test_success_restarts_only_after_update(self):
        ok = SimpleNamespace(returncode=0, stderr="", stdout="")
        calls, reloads, _, _ = self.run_case(True, [ok, ok])
        self.assertEqual(calls[0].args[0][1], "update")
        self.assertEqual(calls[1].args[0], ["systemctl", "--user", "restart", "ripple.service"])
        self.assertEqual(reloads, 1)

    def test_failed_restart_does_not_reload_window(self):
        ok = SimpleNamespace(returncode=0, stderr="", stdout="")
        failed = SimpleNamespace(returncode=1, stderr="service unavailable", stdout="")
        calls, reloads, window, status = self.run_case(True, [ok, failed])
        self.assertEqual(len(calls), 2)
        self.assertEqual(reloads, 0)
        self.assertFalse(window.updating)
        self.assertIn("service unavailable", status.set_text.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
