#!/usr/bin/env python3
"""Ripple Terminal: the desktop window for Ripple.

Every tab is a Ripple session owned by the background service, so whatever runs in a tab keeps
running when the window closes and can be opened from a paired phone at the same time.
"""
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Vte", "2.91")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GdkPixbuf, Gio, GLib, Gtk, Pango, Vte  # noqa: E402

APP_ID = "dev.wonderdesk.ripple"
HERE = os.path.dirname(os.path.realpath(__file__))
SETTINGS_PATH = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "ripple", "terminal.json")


def ripple_bin():
    for c in (os.environ.get("RIPPLE_BIN"), os.path.join(HERE, "ripple"), shutil.which("ripple"), os.path.expanduser("~/.local/bin/ripple")):
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return "ripple"


RIPPLE = ripple_bin()


def socket_path():
    if os.environ.get("RIPPLE_SOCKET"):
        return os.environ["RIPPLE_SOCKET"]
    rd = os.environ.get("XDG_RUNTIME_DIR")
    return os.path.join(rd, "ripple.sock") if rd else "/tmp/ripple-%d.sock" % os.getuid()


# ------------------------------------------------------------------ protocol
def _send(sock, obj):
    body = json.dumps(obj).encode()
    sock.sendall(bytes([1]) + struct.pack(">I", len(body)) + body)


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def _recv(sock):
    head = _recv_exact(sock, 5)
    body = _recv_exact(sock, struct.unpack(">I", head[1:])[0])
    if head[0] == 1:
        return json.loads(body)
    return body


def ensure_daemon():
    path = socket_path()
    try:
        s = socket.socket(socket.AF_UNIX)
        s.connect(path)
        s.close()
        return True
    except OSError:
        pass
    # `ripple ls` starts the service if it isn't running.
    subprocess.run([RIPPLE, "ls"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    return True


def request(obj, timeout=10):
    ensure_daemon()
    s = socket.socket(socket.AF_UNIX)
    s.settimeout(timeout)
    s.connect(socket_path())
    try:
        _send(s, obj)
        r = _recv(s)
        if isinstance(r, dict) and r.get("error"):
            raise RuntimeError(r["error"])
        return r
    finally:
        s.close()


# ------------------------------------------------------------------ settings
DEFAULTS = {"font": "Monospace 11", "scheme": "auto", "opacity": 0.88, "scrollback": 10000, "close_ends_session": True, "width": 1000, "height": 640, "bell": True}


def load_settings():
    try:
        with open(SETTINGS_PATH) as f:
            return {**DEFAULTS, **json.load(f)}
    except Exception:
        return dict(DEFAULTS)


def save_settings(s):
    os.makedirs(os.path.dirname(SETTINGS_PATH), exist_ok=True)
    with open(SETTINGS_PATH, "w") as f:
        json.dump(s, f, indent=2)


# Terminal colour schemes: (background, foreground, cursor, 16-colour palette)
SCHEMES = {
    "glass": ("#0e1a33", "#d8e4f7", "#5ac8fa", ["#3a4a66", "#ff6b62", "#30d158", "#ffd60a", "#0a84ff", "#bf5af2", "#5ac8fa", "#d8e4f7", "#6e7fa3", "#ff8a80", "#7ee787", "#ffe45e", "#5ac8fa", "#da8fff", "#70d7ff", "#ffffff"]),
    "paper": ("#ffffff", "#1c1c1c", "#1e7fe8", ["#000000", "#c23621", "#25bc24", "#adad27", "#2a5fdd", "#d338d3", "#33bbc8", "#cbcccd", "#818383", "#fc391f", "#31e722", "#c5b81c", "#3b6cff", "#f935f8", "#14c5d4", "#e9ebeb"]),
    "midnight": ("#1e1f24", "#e8e8ea", "#6ea8ff", ["#1e1f24", "#ff6b61", "#5bc777", "#f2b33d", "#6ea8ff", "#c792ea", "#5ad4e6", "#d5dae0", "#6b7078", "#ff8a80", "#7ee09a", "#ffd479", "#94c1ff", "#ddb3ff", "#8ae7f2", "#ffffff"]),
    "phosphor": ("#000000", "#28fe14", "#28fe14", ["#000000", "#990000", "#00a600", "#999900", "#0000b2", "#b200b2", "#00a6b2", "#bfbfbf", "#666666", "#e50000", "#00d900", "#e5e500", "#0000ff", "#e500e5", "#00e5e5", "#e5e5e5"]),
    "ocean": ("#1c3f86", "#ffffff", "#7dd3ff", ["#1c3f86", "#ff7a7a", "#7ee08f", "#ffe17a", "#9cc4ff", "#f0a6ff", "#8be9fd", "#e6ecf5", "#6d8ac7", "#ff9a9a", "#a2f0ae", "#fff0a8", "#c1dbff", "#f7c8ff", "#b3f3ff", "#ffffff"]),
    "parchment": ("#f6efd9", "#3b2f22", "#8b5a2b", ["#3b2f22", "#a8322b", "#4f7b2a", "#9a6b11", "#2f5a9e", "#8b3f8b", "#2f7f86", "#d9cfb6", "#7d6e5a", "#c4473f", "#6a9a3c", "#b9861d", "#4b78c2", "#a85ea8", "#45a0a8", "#fbf6e6"]),
    "neon": ("#0d0a1a", "#f2ecff", "#00f0ff", ["#0d0a1a", "#ff4f7b", "#3cffa8", "#ffce68", "#00b3ff", "#ff2e9a", "#00f0ff", "#d8ccff", "#5b3fa8", "#ff7d9c", "#7dffc4", "#ffe09c", "#4dd0ff", "#ff6ec0", "#7df9ff", "#ffffff"]),
    "bliss": ("#fefefe", "#000000", "#0058e6", ["#000000", "#c1311f", "#3c9c3c", "#d08a00", "#0058e6", "#8f3fb0", "#1b8fae", "#ece9d8", "#7f9db9", "#e2483a", "#52c152", "#f2a900", "#3c82ff", "#b45fd6", "#3db3d6", "#ffffff"]),
}
LOOK_SCHEME = {"AQUA": "glass", "BLISS": "bliss", "DARK": "midnight", "SUPER": "neon"}
SCHEME_LABELS = [("auto", "Match the look"), ("glass", "Aqua Glass"), ("paper", "Paper"), ("midnight", "Midnight"), ("phosphor", "Phosphor"), ("ocean", "Ocean"), ("parchment", "Parchment"), ("neon", "Neon"), ("bliss", "Bliss")]


def rgba(hexstr, alpha=1.0):
    c = Gdk.RGBA()
    c.parse(hexstr)
    c.alpha = alpha
    return c


# ------------------------------------------------------------------ looks (CSS)
LOOK_CSS = {
    "AQUA": """
      .ripple-window { background-color: #e8e8e8; background-image: repeating-linear-gradient(to bottom, #ececec 0px, #ececec 2px, #e2e2e2 2px, #e2e2e2 4px); }
      .ripple-toolbar { background-image: linear-gradient(to bottom, #f1f1f1, #d2d2d2); border-bottom: 1px solid #8c8c8c; padding: 6px 8px; }
      .gel { border-radius: 13px; padding: 3px 14px; border: 1px solid #7d7d7d; color: #1c1c1c; font-weight: bold;
             background-image: linear-gradient(to bottom, #ffffff 0%, #f2f2f2 45%, #dcdcdc 50%, #f4f4f4 100%); box-shadow: 0 1px 1px rgba(0,0,0,.25); }
      .gel:hover { background-image: linear-gradient(to bottom, #ffffff 0%, #f8f8f8 45%, #e6e6e6 50%, #fafafa 100%); }
      .gel.primary { color: white; border-color: #1b5fb0; text-shadow: 0 -1px rgba(0,0,0,.3);
             background-image: linear-gradient(to bottom, #a9d4ff 0%, #4ea2f5 45%, #1e7fe8 50%, #6cc0ff 100%); }
      notebook > header { background-image: linear-gradient(to bottom, #dcdcdc, #c4c4c4); border-bottom: 1px solid #8c8c8c; padding: 2px 6px 0 6px; }
      notebook > header tab { border-radius: 8px 8px 0 0; padding: 3px 12px; margin: 0 1px; border: 1px solid #9a9a9a; border-bottom: none; color: #303030;
             background-image: linear-gradient(to bottom, #f4f4f4, #d6d6d6); }
      notebook > header tab:checked { color: white; border-color: #1b5fb0; background-image: linear-gradient(to bottom, #a9d4ff 0%, #4ea2f5 45%, #1e7fe8 50%, #6cc0ff 100%); }
      notebook > header tab:checked label { color: white; text-shadow: 0 -1px rgba(0,0,0,.35); }
      .ripple-status { background-image: linear-gradient(to bottom, #dedede, #c8c8c8); border-top: 1px solid #9a9a9a; padding: 2px 10px; color: #3c3c3c; }
      .tab-close { padding: 0; min-width: 16px; min-height: 16px; border-radius: 8px; background: rgba(0,0,0,.08); border: none; }
      .tab-close:hover { background: #d7372f; color: white; }
    """,
    "BLISS": """
      .ripple-window { background-color: #ece9d8; }
      .ripple-toolbar { background-image: linear-gradient(to bottom, #0a5fea, #2b7cf5 60%, #0054e3); border-bottom: 1px solid #003c9e; padding: 6px 8px; }
      .ripple-toolbar label { color: white; }
      .gel { border-radius: 3px; padding: 3px 14px; border: 1px solid #003c74; color: black; background-image: linear-gradient(to bottom, #ffffff, #ecebe6 85%, #d6d0c5); }
      .gel.primary { background-image: linear-gradient(to bottom, #6fd36f, #3c9c3c); color: white; border-color: #2a6e2a; font-weight: bold; }
      notebook > header { background-color: #ece9d8; border-bottom: 1px solid #919b9c; padding: 2px 6px 0 6px; }
      notebook > header tab { border-radius: 3px 3px 0 0; padding: 3px 12px; border: 1px solid #919b9c; border-bottom: none; background-image: linear-gradient(to bottom, #fefefe, #ecebe6); }
      notebook > header tab:checked { border-top: 3px solid #e68b2c; background: #fcfcfe; }
      .ripple-status { background-color: #ece9d8; border-top: 1px solid #919b9c; padding: 2px 10px; }
      .tab-close { padding: 0; min-width: 16px; min-height: 16px; border-radius: 3px; background: #e1483a; color: white; border: none; }
    """,
    "DARK": """
      .ripple-window { background-color: #1c1f23; }
      .ripple-toolbar { background-image: linear-gradient(to bottom, #454b54, #2a2e34); border-bottom: 1px solid #15171a; padding: 6px 8px; }
      .ripple-toolbar label, .ripple-status label { color: #edeff2; }
      .gel { border-radius: 8px; padding: 3px 14px; border: 1px solid #15171a; color: #edeff2; background-image: linear-gradient(to bottom, #4a5059, #33373e); }
      .gel.primary { background-image: linear-gradient(to bottom, #6ea8ff, #3d6fc4); color: #0b1a2e; font-weight: bold; }
      notebook > header { background-color: #24272c; border-bottom: 1px solid #15171a; padding: 2px 6px 0 6px; }
      notebook > header tab { border-radius: 8px 8px 0 0; padding: 3px 12px; color: #9aa2ad; background-color: #2a2e34; border: none; }
      notebook > header tab:checked { color: #edeff2; background-color: #3b4048; box-shadow: inset 0 2px #6ea8ff; }
      notebook > header tab label { color: inherit; }
      .ripple-status { background-color: #24272c; border-top: 1px solid #15171a; padding: 2px 10px; }
      .tab-close { padding: 0; min-width: 16px; min-height: 16px; border-radius: 8px; background: transparent; color: #9aa2ad; border: none; }
      .tab-close:hover { background: #ff6b61; color: #1c1f23; }
    """,
    "SUPER": """
      .ripple-window { background-image: linear-gradient(to bottom right, #2a1a55, #1b1238); }
      .ripple-toolbar { background-image: linear-gradient(to right, #3b1f7a, #2a1a55); border-bottom: 1px solid #ff2e9a; padding: 6px 8px; }
      .ripple-toolbar label, .ripple-status label { color: #f2ecff; }
      .gel { border-radius: 16px; padding: 3px 14px; border: 1px solid #5b3fa8; color: #f2ecff; background-image: linear-gradient(to bottom, #3b2a6e, #21163f); }
      .gel.primary { border-color: #00f0ff; color: #07131a; font-weight: bold; background-image: linear-gradient(to right, #00f0ff, #b01eff); }
      notebook > header { background-color: #140e28; border-bottom: 1px solid #5b3fa8; padding: 2px 6px 0 6px; }
      notebook > header tab { border-radius: 14px 14px 0 0; padding: 3px 12px; color: #b4a8d6; background-color: #1a1230; border: none; }
      notebook > header tab:checked { color: #7df9ff; background-color: #2a1e4d; box-shadow: inset 0 -2px #ff2e9a; }
      notebook > header tab label { color: inherit; }
      .ripple-status { background-color: #140e28; border-top: 1px solid #5b3fa8; padding: 2px 10px; }
      .tab-close { padding: 0; min-width: 16px; min-height: 16px; border-radius: 8px; background: transparent; color: #b4a8d6; border: none; }
      .tab-close:hover { background: #ff2e9a; color: white; }
    """,
}


class Watcher(threading.Thread):
    """Keeps a 'watch' stream to the service open and forwards events to the UI thread."""

    def __init__(self, on_event):
        super().__init__(daemon=True)
        self.on_event = on_event

    def run(self):
        import time

        while True:
            try:
                ensure_daemon()
                s = socket.socket(socket.AF_UNIX)
                s.connect(socket_path())
                _send(s, {"op": "watch"})
                first = _recv(s)
                GLib.idle_add(self.on_event, {"event": "connected", **first})
                while True:
                    ev = _recv(s)
                    if isinstance(ev, dict):
                        GLib.idle_add(self.on_event, ev)
            except Exception:
                GLib.idle_add(self.on_event, {"event": "disconnected"})
                time.sleep(2)


class Tab:
    def __init__(self, win, sid, name):
        self.win = win
        self.sid = sid
        self.name = name
        self.term = Vte.Terminal()
        self.term.set_scrollback_lines(win.settings["scrollback"])
        self.term.set_mouse_autohide(True)
        self.term.set_allow_hyperlink(True)
        self.term.set_audible_bell(win.settings.get("bell", True))
        self.term.set_cursor_blink_mode(Vte.CursorBlinkMode.ON)
        self.term.connect("child-exited", self.on_exit)
        self.term.connect("window-title-changed", self.on_title)
        self.term.connect("button-press-event", self.on_button)
        self.term.drag_dest_set(Gtk.DestDefaults.ALL, [Gtk.TargetEntry.new("text/uri-list", 0, 0)], Gdk.DragAction.COPY)
        self.term.connect("drag-data-received", self.on_drop)
        self.scroll = Gtk.ScrolledWindow()
        self.scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self.scroll.add(self.term)
        # Tab label: title + close button. Double-click renames.
        self.label = Gtk.Label(label=name)
        self.label.set_ellipsize(Pango.EllipsizeMode.END)
        self.label.set_max_width_chars(24)
        self.label.set_width_chars(10)
        close = Gtk.Button.new_from_icon_name("window-close-symbolic", Gtk.IconSize.MENU)
        close.get_style_context().add_class("tab-close")
        close.set_relief(Gtk.ReliefStyle.NONE)
        close.set_tooltip_text("Close tab")
        close.connect("clicked", lambda *_: win.close_tab(self))
        ev = Gtk.EventBox()
        ev.connect("button-press-event", self.on_label_click)
        box = Gtk.Box(spacing=6)
        box.pack_start(self.label, True, True, 0)
        box.pack_start(close, False, False, 0)
        ev.add(box)
        ev.show_all()
        self.tab_widget = ev
        self.apply_style()
        self.ended = False
        self.reconnects = 0

    def apply_style(self):
        s = self.win.settings
        self.term.set_font(Pango.FontDescription.from_string(s["font"]))
        scheme = s["scheme"]
        if scheme == "auto":
            scheme = LOOK_SCHEME.get(self.win.look, "glass")
        bg, fg, cur, pal = SCHEMES.get(scheme, SCHEMES["glass"])
        self.term.set_colors(rgba(fg), rgba(bg, s.get("opacity", 1.0)), [rgba(c) for c in pal])
        self.term.set_color_cursor(rgba(cur))
        self.term.set_color_cursor_foreground(rgba(bg))

    def spawn(self):
        env = dict(os.environ)
        env["TERM"] = "xterm-256color"
        argv = [RIPPLE, "attach", str(self.sid)]
        self.term.spawn_async(Vte.PtyFlags.DEFAULT, os.path.expanduser("~"), argv, ["%s=%s" % kv for kv in env.items()], GLib.SpawnFlags.DEFAULT, None, None, -1, None, None)

    def on_exit(self, term, status):
        code = os.waitstatus_to_exitcode(status) if hasattr(os, "waitstatus_to_exitcode") else status >> 8
        alive = any(s["id"] == self.sid for s in self.win.sessions)
        if code == 255 or (alive and code != 0 and self.reconnects < 20):
            # Lost the service (restart/update): reattach shortly.
            self.reconnects += 1
            self.term.feed(b"\r\n\x1b[2m[Ripple: reconnecting\xe2\x80\xa6]\x1b[0m\r\n")
            GLib.timeout_add(1200, self._respawn)
            return
        self.ended = True
        self.win.remove_tab(self)

    def _respawn(self):
        self.win.refresh_sessions()
        if any(s["id"] == self.sid for s in self.win.sessions):
            self.term.reset(True, False)
            self.spawn()
        else:
            self.ended = True
            self.win.remove_tab(self)
        return False

    def on_title(self, term):
        t = term.get_window_title() or self.name
        self.label.set_text(self.name if self.name and not self.name.startswith("Terminal ") else t)
        self.label.set_tooltip_text(t)
        if self.win.current_tab() is self:
            self.win.set_title("Ripple: %s" % t)

    def on_label_click(self, widget, event):
        if event.type == Gdk.EventType._2BUTTON_PRESS:
            self.win.rename_tab(self)
            return True
        if event.button == 2:
            self.win.close_tab(self)
            return True
        if event.button == 3:
            self.win.tab_menu(self, event)
            return True
        return False

    def on_button(self, term, event):
        if event.button == 3:
            menu = Gtk.Menu()
            for label, cb in (("Copy", lambda *_: term.copy_clipboard_format(Vte.Format.TEXT)), ("Paste", lambda *_: term.paste_clipboard()),
                              (None, None), ("Send a file to the phone…", lambda *_: self.win.send_file()), ("Rename tab…", lambda *_: self.win.rename_tab(self)),
                              ("Keep running and hide tab", lambda *_: self.win.detach_tab(self)), ("End session", lambda *_: self.win.close_tab(self, force=True))):
                if label is None:
                    menu.append(Gtk.SeparatorMenuItem())
                    continue
                it = Gtk.MenuItem(label=label)
                it.connect("activate", cb)
                menu.append(it)
            menu.show_all()
            menu.popup_at_pointer(event)
            return True
        return False

    def on_drop(self, widget, ctx, x, y, data, info, time):
        uris = data.get_uris() or []
        paths = [GLib.filename_from_uri(u)[0] for u in uris if u.startswith("file://")]
        if paths:
            self.term.feed_child(" ".join(GLib.shell_quote(p) for p in paths).encode() + b" ")


class Window(Gtk.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="Ripple")
        self.settings = load_settings()
        self.look = "AQUA"
        self.sessions = []
        self.known_ids = set()
        self.tabs = []
        self.devices_online = {}
        self.pair_dialog = None
        self.set_default_size(self.settings["width"], self.settings["height"])
        self.set_icon_name("ripple")
        self.get_style_context().add_class("ripple-window")
        screen = self.get_screen()
        visual = screen.get_rgba_visual()
        if visual and screen.is_composited():
            self.set_visual(visual)
            self.set_app_paintable(False)
        self.css = Gtk.CssProvider()
        Gtk.StyleContext.add_provider_for_screen(screen, self.css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.add(root)
        bar = Gtk.Box(spacing=8)
        bar.get_style_context().add_class("ripple-toolbar")
        for label, tip, cb, primary in (("＋ New tab", "New tab (Ctrl+Shift+T)", lambda *_: self.new_tab(), True),
                                        ("Pair a phone", "Show a QR code for the Ripple app", lambda *_: self.show_pair(), False),
                                        ("Send to phone", "Offer a file to your phone", lambda *_: self.send_file(), False),
                                        ("Sessions", "Sessions not open in this window", None, False)):
            b = Gtk.Button(label=label)
            b.set_tooltip_text(tip)
            b.get_style_context().add_class("gel")
            if primary:
                b.get_style_context().add_class("primary")
            if cb:
                b.connect("clicked", cb)
            else:
                self.sessions_button = b
                b.connect("clicked", self.sessions_menu)
            bar.pack_start(b, False, False, 0)
        gear = Gtk.Button(label="Settings")
        gear.get_style_context().add_class("gel")
        gear.connect("clicked", lambda *_: self.show_settings())
        bar.pack_end(gear, False, False, 0)
        root.pack_start(bar, False, False, 0)

        self.nb = Gtk.Notebook()
        self.nb.set_scrollable(True)
        self.nb.set_show_border(False)
        self.nb.connect("switch-page", self.on_switch)
        self.nb.connect("page-reordered", lambda *_: None)
        root.pack_start(self.nb, True, True, 0)

        sb = Gtk.Box(spacing=12)
        sb.get_style_context().add_class("ripple-status")
        self.status = Gtk.Label(label="Starting…", xalign=0)
        self.status.set_ellipsize(Pango.EllipsizeMode.END)
        sb.pack_start(self.status, True, True, 0)
        self.fw_button = Gtk.Button(label="Allow through firewall")
        self.fw_button.get_style_context().add_class("gel")
        self.fw_button.set_no_show_all(True)
        self.fw_button.connect("clicked", lambda *_: self.fix_firewall())
        sb.pack_end(self.fw_button, False, False, 0)
        root.pack_start(sb, False, False, 0)

        self.connect("key-press-event", self.on_key)
        self.connect("delete-event", self.on_close)
        self.apply_look()
        self.show_all()
        threading.Thread(target=self._startup, daemon=True).start()

    # ----- startup
    def _startup(self):
        try:
            ensure_daemon()
            keep = ("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS", "SSH_AUTH_SOCK", "GPG_AGENT_INFO", "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "LANG", "PATH")
            request({"op": "set_env", "env": {k: os.environ[k] for k in keep if k in os.environ}})
            cfg = request({"op": "config"})["config"]
            sessions = request({"op": "sessions"})["sessions"]
        except Exception as e:
            GLib.idle_add(self.status.set_text, "Couldn't start the Ripple service: %s" % e)
            return
        GLib.idle_add(self._started, cfg, sessions)

    def _started(self, cfg, sessions):
        self.look = cfg.get("look", "AQUA")
        self.cfg = cfg
        self.apply_look()
        self.sessions = sessions
        self.known_ids = {s["id"] for s in sessions}
        detached = [s for s in sessions if s.get("receivers", 0) == 0]
        # The first Ripple window on this computer shows every running session, even ones your phone is
        # attached to, so opening the window resumes your work instead of starting yet another session.
        # Extra windows only pick up sessions nobody is looking at (and start a fresh one otherwise).
        for s in (sessions if not other_windows_open() else detached):
            self.open_tab(s["id"], s["name"])
        if not self.tabs:
            self.new_tab()
        Watcher(self.on_event).start()
        threading.Thread(target=self._check_firewall, daemon=True).start()
        self.update_status()
        return False

    def _check_firewall(self):
        # ufw drops incoming connections by default; phones at home connect directly when allowed.
        try:
            active = subprocess.run(["systemctl", "is-active", "ufw"], capture_output=True, text=True).stdout.strip() == "active"
            rules = open("/etc/ufw/user.rules").read() if os.path.exists("/etc/ufw/user.rules") else ""
            port = str(self.cfg.get("port", 47800))
            allowed = port in rules
        except Exception:
            active, allowed = False, True
        if active and not allowed:
            GLib.idle_add(self.fw_button.show)

    def fix_firewall(self):
        port = int(self.cfg.get("port", 47800))
        cmd = ["pkexec", "ufw", "allow", "%d:%d/udp" % (port, port + 1), "comment", "Ripple"]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
            if r.returncode == 0:
                self.fw_button.hide()
                self.status.set_text("Firewall now lets phones on your network connect directly.")
            else:
                self.status.set_text("The firewall wasn't changed (%s)" % (r.stderr.strip() or "cancelled"))
        except Exception as e:
            self.status.set_text("Couldn't change the firewall: %s. Run: sudo ufw allow %d:%d/udp" % (e, port, port + 1))

    # ----- look
    def apply_look(self):
        css = LOOK_CSS.get(self.look, LOOK_CSS["AQUA"]) + """
          .ripple-toolbar button { min-height: 22px; }
          vte-terminal { padding: 4px 6px; }
        """
        self.css.load_from_data(css.encode())
        settings = Gtk.Settings.get_default()
        settings.set_property("gtk-application-prefer-dark-theme", self.look in ("DARK", "SUPER"))
        for t in self.tabs:
            t.apply_style()

    # ----- tabs
    def current_tab(self):
        page = self.nb.get_current_page()
        if page < 0:
            return None
        w = self.nb.get_nth_page(page)
        return next((t for t in self.tabs if t.scroll is w), None)

    def open_tab(self, sid, name, focus=True):
        self.known_ids.add(sid)
        if any(t.sid == sid for t in self.tabs):
            if focus:
                self.nb.set_current_page(self.nb.page_num(next(t for t in self.tabs if t.sid == sid).scroll))
            return
        t = Tab(self, sid, name)
        self.tabs.append(t)
        i = self.nb.append_page(t.scroll, t.tab_widget)
        self.nb.set_tab_reorderable(t.scroll, True)
        t.scroll.show_all()
        if focus:
            self.nb.set_current_page(i)
        t.spawn()
        if focus:
            t.term.grab_focus()

    def new_tab(self, cwd=None):
        cur = self.current_tab()
        if cwd is None and cur:
            cwd = next((s.get("cwd") for s in self.sessions if s["id"] == cur.sid), None)
        cols = max(cur.term.get_column_count(), 20) if cur else 100
        rows = max(cur.term.get_row_count(), 5) if cur else 30
        try:
            r = request({"op": "new", "cwd": cwd or os.path.expanduser("~"), "cols": cols, "rows": rows})
        except Exception as e:
            self.status.set_text("Couldn't open a new tab: %s" % e)
            return
        self.sessions.append(r["session"])
        self.open_tab(r["id"], r["session"]["name"])

    def remove_tab(self, t):
        if t in self.tabs:
            self.tabs.remove(t)
            n = self.nb.page_num(t.scroll)
            if n >= 0:
                self.nb.remove_page(n)
        if not self.tabs:
            self.get_application().quit()

    def close_tab(self, t, force=False):
        if force or self.settings.get("close_ends_session", True):
            prog = next((s.get("program") for s in self.sessions if s["id"] == t.sid), "")
            if not force and prog and prog not in ("bash", "zsh", "fish", "sh", "dash", "ksh", "tcsh", "nu", "xonsh", "elvish", ""):
                d = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.QUESTION, buttons=Gtk.ButtonsType.NONE, text="“%s” is still running" % prog)
                d.format_secondary_text("End it, or keep it running in the background (you can open it again from Sessions or your phone)?")
                d.add_buttons("Keep running", 1, "Cancel", 0, "End it", 2)
                resp = d.run()
                d.destroy()
                if resp == 1:
                    return self.detach_tab(t)
                if resp != 2:
                    return
            try:
                request({"op": "kill", "id": t.sid})
            except Exception:
                pass
            t.ended = True
            self.remove_tab(t)
        else:
            self.detach_tab(t)

    def detach_tab(self, t):
        t.ended = True
        t.term.disconnect_by_func(t.on_exit)
        pty = t.term.get_pty()
        if pty:
            try:
                os.close(pty.get_fd())
            except Exception:
                pass
        self.remove_tab(t)

    def rename_tab(self, t):
        d = Gtk.Dialog(title="Rename tab", transient_for=self, modal=True)
        d.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Rename", Gtk.ResponseType.OK)
        e = Gtk.Entry(text=t.name)
        e.set_activates_default(True)
        d.set_default_response(Gtk.ResponseType.OK)
        d.get_content_area().pack_start(e, True, True, 8)
        d.show_all()
        if d.run() == Gtk.ResponseType.OK and e.get_text().strip():
            t.name = e.get_text().strip()
            t.label.set_text(t.name)
            try:
                request({"op": "rename", "id": t.sid, "name": t.name})
            except Exception:
                pass
        d.destroy()

    def tab_menu(self, t, event):
        menu = Gtk.Menu()
        for label, cb in (("Rename…", lambda *_: self.rename_tab(t)), ("Keep running and hide", lambda *_: self.detach_tab(t)), ("End session", lambda *_: self.close_tab(t, force=True))):
            it = Gtk.MenuItem(label=label)
            it.connect("activate", cb)
            menu.append(it)
        menu.show_all()
        menu.popup_at_pointer(event)

    def sessions_menu(self, button):
        self.refresh_sessions()
        menu = Gtk.Menu()
        hidden = [s for s in self.sessions if not any(t.sid == s["id"] for t in self.tabs)]
        if not hidden:
            it = Gtk.MenuItem(label="Every session is open in this window")
            it.set_sensitive(False)
            menu.append(it)
        for s in hidden:
            home = os.path.expanduser("~")
            label = "%s: %s  (%s)" % (s["name"], s.get("program", ""), (s.get("cwd") or "").replace(home, "~", 1))
            it = Gtk.MenuItem(label=label)
            it.connect("activate", lambda _w, s=s: self.open_tab(s["id"], s["name"]))
            menu.append(it)
        menu.show_all()
        menu.popup_at_widget(button, Gdk.Gravity.SOUTH_WEST, Gdk.Gravity.NORTH_WEST, None)

    def refresh_sessions(self):
        try:
            self.sessions = request({"op": "sessions"})["sessions"]
        except Exception:
            pass

    def on_switch(self, nb, page, num):
        t = next((t for t in self.tabs if t.scroll is page), None)
        if t:
            GLib.idle_add(t.term.grab_focus)
            self.set_title("Ripple: %s" % (t.label.get_tooltip_text() or t.name))

    # ----- keys
    def on_key(self, w, ev):
        ctrl = ev.state & Gdk.ModifierType.CONTROL_MASK
        shift = ev.state & Gdk.ModifierType.SHIFT_MASK
        k = Gdk.keyval_name(ev.keyval) or ""
        t = self.current_tab()
        if ctrl and shift:
            if k in ("T", "t"):
                self.new_tab()
                return True
            if k in ("W", "w") and t:
                self.close_tab(t)
                return True
            if k in ("C", "c") and t:
                t.term.copy_clipboard_format(Vte.Format.TEXT)
                return True
            if k in ("V", "v") and t:
                t.term.paste_clipboard()
                return True
            if k in ("F", "f") and t:
                self.search(t)
                return True
        if ctrl and k in ("Page_Down", "Page_Up"):
            n = self.nb.get_n_pages()
            if n:
                self.nb.set_current_page((self.nb.get_current_page() + (1 if k == "Page_Down" else -1)) % n)
            return True
        if ctrl and k in ("plus", "equal", "minus", "0") and t:
            scale = t.term.get_font_scale()
            scale = 1.0 if k == "0" else max(0.5, min(3.0, scale * (1.1 if k in ("plus", "equal") else 1 / 1.1)))
            for tab in self.tabs:
                tab.term.set_font_scale(scale)
            return True
        return False

    def search(self, t):
        d = Gtk.Dialog(title="Find", transient_for=self)
        d.add_buttons("Previous", 1, "Next", 2, "Close", Gtk.ResponseType.CLOSE)
        e = Gtk.Entry(placeholder_text="Text to find")
        d.get_content_area().pack_start(e, True, True, 8)
        d.show_all()
        while True:
            r = d.run()
            if r in (1, 2) and e.get_text():
                import re
                rx = Vte.Regex.new_for_search(re.escape(e.get_text()), -1, 0x00000008)  # PCRE2_CASELESS
                t.term.search_set_regex(rx, 0)
                (t.term.search_find_previous if r == 1 else t.term.search_find_next)()
            else:
                break
        d.destroy()

    # ----- events from the service
    def on_event(self, ev):
        kind = ev.get("event")
        if kind == "sessions":
            self.sessions = ev.get("sessions", [])
            self.sync_tabs()
            self.update_status()
        elif kind == "connected":
            self.sessions = ev.get("sessions", self.sessions)
            self.sync_tabs()
            self.update_status()
        elif kind == "disconnected":
            self.status.set_text("Reconnecting to the Ripple service…")
        elif kind == "device":
            if ev.get("connected"):
                self.devices_online[ev["fp"]] = ev.get("addr", "")
            else:
                self.devices_online.pop(ev.get("fp"), None)
            self.update_status()
        elif kind == "pair_request":
            self.ask_pair(ev)
        elif kind in ("devices", "pair_done"):
            if self.pair_dialog and kind == "devices":
                self.pair_dialog.destroy()
                self.pair_dialog = None
                self.status.set_text("Phone paired. Open Ripple on it to see your sessions.")
        elif kind == "look":
            self.look = ev.get("look", "AQUA")
            self.apply_look()
        elif kind == "file_received":
            self.status.set_text("Received %s from your phone → %s" % (ev.get("name"), ev.get("path")))
        return False

    def sync_tabs(self):
        """Keeps the tabs in step with the service: a session started from the phone (or the command line)
        gets a tab here at once, and renames made on either side show up on both."""
        ids = {s["id"] for s in self.sessions}
        for s in self.sessions:
            if s["id"] not in self.known_ids:
                self.known_ids.add(s["id"])
                if not any(t.sid == s["id"] for t in self.tabs):
                    self.open_tab(s["id"], s["name"], focus=False)
        self.known_ids &= ids | {t.sid for t in self.tabs}
        for t in self.tabs:
            s = next((s for s in self.sessions if s["id"] == t.sid), None)
            if s and s["name"] != t.name:
                t.name = s["name"]
                t.label.set_text(s["name"])

    def update_status(self):
        n = len(self.sessions)
        phones = len(self.devices_online)
        parts = ["%d session%s" % (n, "" if n == 1 else "s")]
        if phones:
            parts.append("%d phone%s connected" % (phones, "" if phones == 1 else "s"))
        self.status.set_text(" · ".join(parts))

    def ask_pair(self, ev):
        d = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.QUESTION, buttons=Gtk.ButtonsType.NONE,
                              text="%s wants to connect" % ev.get("device", "A phone"))
        d.format_secondary_markup("Allow it only if the phone shows this code:\n\n<span size='xx-large' weight='bold'>%s</span>\n\nIt will be able to use your terminal and files." % GLib.markup_escape_text(ev.get("compare", "")))
        d.add_buttons("Don't allow", 0, "Allow", 1)
        self.present()
        allow = d.run() == 1
        d.destroy()
        try:
            request({"op": "pair_answer", "request": ev["request"], "allow": allow})
        except Exception as e:
            self.status.set_text(str(e))

    # ----- pairing
    def show_pair(self):
        try:
            r = request({"op": "pair_code"})
        except Exception as e:
            self.status.set_text("Couldn't make a pairing code: %s" % e)
            return
        d = Gtk.Dialog(title="Pair a phone", transient_for=self, modal=False)
        d.add_buttons("Done", Gtk.ResponseType.CLOSE)
        box = d.get_content_area()
        box.set_spacing(10)
        box.set_border_width(16)
        title = Gtk.Label()
        title.set_markup("<span size='large' weight='bold'>Scan this with Ripple on your phone</span>")
        box.pack_start(title, False, False, 0)
        loader = GdkPixbuf.PixbufLoader.new_with_type("svg")
        loader.write(r["qr_svg"].encode())
        loader.close()
        img = Gtk.Image.new_from_pixbuf(loader.get_pixbuf())
        frame = Gtk.EventBox()
        frame.override_background_color(Gtk.StateFlags.NORMAL, rgba("#ffffff"))
        frame.add(img)
        box.pack_start(frame, False, False, 0)
        hint = Gtk.Label(label="No camera? In the app pick “%s” and type this code:" % r["computer"]["name"])
        hint.set_line_wrap(True)
        box.pack_start(hint, False, False, 0)
        code = Gtk.Label()
        code.set_selectable(True)
        code.set_markup("<span size='xx-large' weight='bold' font_family='monospace'>%s</span>" % r["code"])
        box.pack_start(code, False, False, 0)
        foot = Gtk.Label()
        foot.set_markup("<small>Works once, for 10 minutes. The phone and computer connect directly; nothing goes through an account.</small>")
        foot.set_line_wrap(True)
        box.pack_start(foot, False, False, 0)
        d.connect("response", lambda dd, _r: (dd.destroy(), setattr(self, "pair_dialog", None)))
        d.show_all()
        self.pair_dialog = d

    def send_file(self):
        d = Gtk.FileChooserNative.new("Send to your phone", self, Gtk.FileChooserAction.OPEN, "Send", "Cancel")
        d.set_select_multiple(True)
        if d.run() == Gtk.ResponseType.ACCEPT:
            names = []
            for f in d.get_filenames():
                try:
                    r = request({"op": "offer", "path": f})
                    names.append(r["name"])
                except Exception as e:
                    self.status.set_text(str(e))
            if names:
                self.status.set_text("Offered %s to your phone (also in your Ripple folder)" % ", ".join(names))
        d.destroy()

    # ----- settings
    def show_settings(self):
        try:
            cfg = request({"op": "config"})["config"]
            devices = request({"op": "devices"})["devices"]
        except Exception as e:
            self.status.set_text(str(e))
            return
        d = Gtk.Dialog(title="Ripple Settings", transient_for=self, modal=True)
        d.add_buttons("Close", Gtk.ResponseType.CLOSE)
        d.set_default_size(560, 520)
        nb = Gtk.Notebook()
        d.get_content_area().pack_start(nb, True, True, 0)

        def grid():
            g = Gtk.Grid(column_spacing=12, row_spacing=10, margin=16)
            return g

        def row(g, i, label, widget, hint=None):
            l = Gtk.Label(label=label, xalign=1)
            g.attach(l, 0, i, 1, 1)
            g.attach(widget, 1, i, 1, 1)
            widget.set_hexpand(True)
            if hint:
                h = Gtk.Label(xalign=0)
                h.set_markup("<small>%s</small>" % GLib.markup_escape_text(hint))
                h.set_line_wrap(True)
                h.set_max_width_chars(50)
                g.attach(h, 1, i + 1, 1, 1)

        # Appearance
        g = grid()
        look = Gtk.ComboBoxText()
        for k, v in (("AQUA", "Aqua"), ("BLISS", "Bliss"), ("DARK", "Dark Bliss"), ("SUPER", "Super")):
            look.append(k, v)
        look.set_active_id(self.look)
        row(g, 0, "Look", look)
        follow = Gtk.CheckButton(label="Match my phone's Wonderdesk look when it connects")
        follow.set_active(cfg.get("follow_phone_look", True))
        g.attach(follow, 1, 1, 1, 1)
        scheme = Gtk.ComboBoxText()
        for k, v in SCHEME_LABELS:
            scheme.append(k, v)
        scheme.set_active_id(self.settings["scheme"])
        row(g, 2, "Colours", scheme)
        font = Gtk.FontButton(font=self.settings["font"])
        font.set_filter_func(lambda fam, face: fam.is_monospace())
        row(g, 3, "Font", font)
        opacity = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0.5, 1.0, 0.05)
        opacity.set_value(self.settings.get("opacity", 1.0))
        row(g, 4, "Opacity", opacity, "Needs a compositor (picom, KWin, Mutter…) for see-through windows.")
        bell = Gtk.CheckButton(label="Play the bell sound")
        bell.set_active(self.settings.get("bell", True))
        g.attach(bell, 1, 6, 1, 1)
        nb.append_page(g, Gtk.Label(label="Appearance"))

        # Terminal
        g = grid()
        shell = Gtk.ComboBoxText.new_with_entry()
        shells = [""]
        try:
            shells += [l.strip() for l in open("/etc/shells") if l.strip().startswith("/") and os.path.exists(l.strip())]
        except Exception:
            pass
        for s in dict.fromkeys(shells):
            shell.append(s, s or "My login shell")
        shell.set_active_id(cfg.get("shell", "")) or shell.get_child().set_text(cfg.get("shell", ""))
        row(g, 0, "Shell", shell, "Used for new tabs on this computer and from your phone.")
        sb = Gtk.SpinButton.new_with_range(1000, 200000, 1000)
        sb.set_value(self.settings["scrollback"])
        row(g, 2, "Scrollback lines", sb)
        closeends = Gtk.CheckButton(label="Closing a tab ends its session (otherwise it keeps running in the background)")
        closeends.set_active(self.settings.get("close_ends_session", True))
        g.attach(closeends, 1, 3, 1, 1)
        quiet = Gtk.SpinButton.new_with_range(0, 3600, 5)
        quiet.set_value(cfg.get("notify_quiet_secs", 20))
        row(g, 4, "Quiet alert (seconds)", quiet, "Tell your phone when a busy session goes quiet for this long: a long job finished or a program is waiting for you. 0 turns it off.")
        nb.append_page(g, Gtk.Label(label="Terminal"))

        # Phones
        g = grid()
        name = Gtk.Entry(text=cfg.get("name", ""))
        row(g, 0, "Computer name", name)
        lst = Gtk.ListBox()
        lst.set_selection_mode(Gtk.SelectionMode.NONE)
        if not devices:
            lst.add(Gtk.Label(label="No phones paired yet", margin=8))
        for dev in devices:
            r = Gtk.Box(spacing=8, margin=6)
            r.pack_start(Gtk.Label(label=dev["name"], xalign=0), True, True, 0)
            fb = Gtk.Button(label="Forget")
            fb.get_style_context().add_class("gel")

            def forget(_b, fp=dev["fp"], rowbox=r):
                request({"op": "forget", "fp": fp})
                rowbox.get_parent().destroy()

            fb.connect("clicked", forget)
            r.pack_end(fb, False, False, 0)
            lst.add(r)
        row(g, 2, "Paired phones", lst)
        shared = Gtk.FileChooserButton.new("Shared folder", Gtk.FileChooserAction.SELECT_FOLDER)
        shared.set_filename(cfg.get("shared_dir", os.path.expanduser("~/Ripple")))
        row(g, 3, "Shared folder", shared, "Files your phone sends land here; your phone sees this folder in Wayfinder.")
        nb.append_page(g, Gtk.Label(label="Phones"))

        # Network
        g = grid()
        bellsw = Gtk.CheckButton(label="Doorbell: let my phone reach this computer from anywhere")
        bellsw.set_active(cfg.get("doorbell_enabled", True))
        g.attach(bellsw, 0, 0, 2, 1)
        expl = Gtk.Label(xalign=0)
        expl.set_line_wrap(True)
        expl.set_max_width_chars(60)
        expl.set_markup("<small>Routers block incoming connections. With the doorbell, your phone leaves an encrypted note on a free relay saying where it is, and this computer opens a direct path to it. Only that note passes through the relay; your terminal and files go straight between your devices. You can run your own relay (any ntfy server).</small>")
        g.attach(expl, 0, 1, 2, 1)
        server = Gtk.Entry(text=cfg.get("doorbell", {}).get("server", "https://ntfy.sh"))
        row(g, 2, "Relay", server)
        upnp = Gtk.CheckButton(label="Ask the router to open a port (UPnP) when it supports it")
        upnp.set_active(cfg.get("upnp", True))
        g.attach(upnp, 1, 3, 1, 1)
        extra = Gtk.Entry(text=", ".join(cfg.get("extra_addresses", [])))
        extra.set_placeholder_text("e.g. myhome.duckdns.org")
        row(g, 4, "Extra addresses", extra, "Names or addresses your phone should also try (a dynamic DNS name, a static IP).")
        upd = Gtk.CheckButton(label="Keep Ripple up to date automatically")
        upd.set_active(cfg.get("auto_update", True))
        g.attach(upd, 1, 6, 1, 1)
        nb.append_page(g, Gtk.Label(label="Network"))

        d.show_all()
        d.run()
        # Save
        self.settings.update({"scheme": scheme.get_active_id() or "auto", "font": font.get_font(), "opacity": opacity.get_value(), "scrollback": int(sb.get_value()),
                              "close_ends_session": closeends.get_active(), "bell": bell.get_active()})
        save_settings(self.settings)
        newlook = look.get_active_id() or "AQUA"
        shell_val = shell.get_active_id() if shell.get_active_id() is not None else shell.get_child().get_text().strip()
        patch = {"op": "set_config", "name": name.get_text().strip() or cfg.get("name"), "shell": shell_val or "", "notify_quiet_secs": int(quiet.get_value()),
                 "doorbell_enabled": bellsw.get_active(), "doorbell_server": server.get_text().strip() or "https://ntfy.sh", "upnp": upnp.get_active(),
                 "extra_addresses": [x.strip() for x in extra.get_text().split(",") if x.strip()], "auto_update": upd.get_active(),
                 "follow_phone_look": follow.get_active(), "shared_dir": shared.get_filename() or cfg.get("shared_dir")}
        try:
            request(patch)
            if newlook != self.look:
                request({"op": "set_look", "look": newlook})
                self.look = newlook
        except Exception as e:
            self.status.set_text(str(e))
        for t in self.tabs:
            t.term.set_scrollback_lines(self.settings["scrollback"])
            t.term.set_audible_bell(self.settings["bell"])
        self.apply_look()
        d.destroy()

    def on_close(self, *_):
        w, h = self.get_size()
        self.settings.update({"width": w, "height": h})
        save_settings(self.settings)
        # Sessions keep running in the background; reopen Ripple or use the phone to get back to them.
        return False


def other_windows_open():
    """True when another Ripple Terminal window is already running for this user."""
    try:
        me = os.getpid()
        for pid in os.listdir("/proc"):
            if not pid.isdigit() or int(pid) == me:
                continue
            try:
                with open("/proc/%s/cmdline" % pid, "rb") as f:
                    cmd = f.read().split(b"\0")
                if os.stat("/proc/" + pid).st_uid == os.getuid() and any(a.endswith(b"ripple_terminal.py") for a in cmd):
                    return True
            except OSError:
                continue
    except OSError:
        pass
    return False


class App(Gtk.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE | Gio.ApplicationFlags.NON_UNIQUE)
        self.win = None

    def do_command_line(self, cmd):
        args = cmd.get_arguments()[1:]
        if not self.win:
            self.win = Window(self)
        if "--pair" in args:
            GLib.timeout_add(600, lambda: (self.win.show_pair(), False)[1])
        self.win.present()
        return 0


if __name__ == "__main__":
    if "--version" in sys.argv:
        print("Ripple Terminal")
        sys.exit(0)
    sys.exit(App().run(sys.argv))
