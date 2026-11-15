#!/bin/sh
# Installs Ripple for the current user (no root needed):
#   ~/.local/bin/ripple            the service + command line
#   ~/.local/bin/ripple-terminal   the desktop window
#   a menu entry, an icon, and a systemd user service that starts Ripple when you log in.
# Run again any time to update. `--update` is used by Ripple's own updater.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HOME/.local/bin"
SHARE="$HOME/.local/share/ripple"
UPDATE=0; [ "$1" = "--update" ] && UPDATE=1
mkdir -p "$BIN" "$SHARE" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/256x256/apps" "$HOME/.config/systemd/user"

say() { [ "$UPDATE" = 1 ] || printf '%s\n' "$*"; }

# 1. The ripple binary: prebuilt (release download) or built from source (repo checkout).
if [ -x "$HERE/bin/ripple" ]; then
  SRC_BIN="$HERE/bin/ripple"
elif [ -f "$HERE/../core/Cargo.toml" ]; then
  say "Building Ripple (first time takes a minute)…"
  (cd "$HERE/../core" && { cargo build --release -p ripple -q 2>/dev/null || cargo build --release -p ripple; })
  SRC_BIN="$HERE/../core/target/release/ripple"
else
  echo "No ripple binary found next to this installer." >&2; exit 1
fi
# Replace atomically so a running service keeps its old copy until it restarts.
cp "$SRC_BIN" "$BIN/.ripple.new" && chmod 755 "$BIN/.ripple.new" && mv -f "$BIN/.ripple.new" "$BIN/ripple"

# 2. Desktop window.
cp "$HERE/ripple_terminal.py" "$SHARE/ripple_terminal.py"
cat > "$BIN/ripple-terminal" <<EOT
#!/bin/sh
exec python3 "$SHARE/ripple_terminal.py" "\$@"
EOT
chmod 755 "$BIN/ripple-terminal"
[ -f "$HERE/ripple.png" ] && cp "$HERE/ripple.png" "$HOME/.local/share/icons/hicolor/256x256/apps/ripple.png"
cat > "$HOME/.local/share/applications/ripple.desktop" <<EOT
[Desktop Entry]
Type=Application
Name=Ripple
GenericName=Terminal
Comment=Terminal you can also use from your phone
Exec=$BIN/ripple-terminal
Icon=ripple
Terminal=false
Categories=System;TerminalEmulator;Utility;
Keywords=terminal;shell;remote;phone;
StartupWMClass=ripple_terminal.py
Actions=Pair;
[Desktop Action Pair]
Name=Pair a phone
Exec=$BIN/ripple-terminal --pair
EOT
command -v update-desktop-database >/dev/null && update-desktop-database "$HOME/.local/share/applications" >/dev/null 2>&1 || true

# 3. Background service: starts at login, restarts if it stops. It runs through your login shell
#    so sessions get the same PATH and settings as any terminal.
LOGIN_SHELL="$(getent passwd "$(id -un)" | cut -d: -f7)"; [ -x "$LOGIN_SHELL" ] || LOGIN_SHELL=/bin/sh
cat > "$HOME/.config/systemd/user/ripple.service" <<EOT
[Unit]
Description=Ripple terminal service (sessions you can reach from your phone)
After=network-online.target

[Service]
ExecStart=$LOGIN_SHELL -l -c 'exec $BIN/ripple daemon'
Restart=always
RestartSec=3
KillMode=process

[Install]
WantedBy=default.target
EOT
if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
  systemctl --user daemon-reload
  if [ "$UPDATE" = 0 ]; then
    systemctl --user enable ripple.service >/dev/null 2>&1 || true
    systemctl --user is-active --quiet ripple.service || systemctl --user start ripple.service || true
  fi
fi

# 4. Checks.
if ! python3 -c "import gi; gi.require_version('Vte','2.91')" 2>/dev/null; then
  say ""
  say "The desktop window needs GTK and VTE for Python:"
  say "  Arch/CachyOS: sudo pacman -S python-gobject vte3"
  say "  Debian/Ubuntu: sudo apt install python3-gi gir1.2-vte-2.91"
  say "  Fedora: sudo dnf install python3-gobject vte291"
fi
if command -v ufw >/dev/null && systemctl is-active --quiet ufw 2>/dev/null; then
  say ""
  say "Your firewall (ufw) is on. Phones still connect through Ripple's doorbell, but at home a direct"
  say "connection is faster:  sudo ufw allow 47800:47801/udp comment Ripple"
fi
case ":$PATH:" in *":$BIN:"*) ;; *) say ""; say "Add $BIN to your PATH to use the ripple command.";; esac
say ""
say "Ripple $("$BIN/ripple" version | cut -d' ' -f2) is installed. Open it from your app menu, or run: ripple-terminal"
say "Pair your phone: ripple pair"
