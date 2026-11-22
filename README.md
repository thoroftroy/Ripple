# Ripple

Your computer's terminal, on your phone. Start something on the computer (a build, a server, Claude Code, btop) and pick it up from your phone anywhere: same session, same scrollback, still running when you put the phone down.

## What it does

- **Sessions that keep running.** The computer owns every terminal. Closing the app, losing signal or switching networks never stops what's running; reopen and you're back where you were, screen and all.
- **Tabs.** Several sessions per computer, each with its own tab showing what's running in it. The desktop window and the phone share the same sessions.
- **Works away from home without accounts or VPNs.** At home it connects directly over Wi-Fi. Away from home it rings an encrypted "doorbell" so the computer opens a direct path to the phone (UDP hole punching over IPv6 or IPv4). Terminal data never goes through anyone else's server.
- **Pinned identities.** Phone and computer pair once by comparing a code. After that each side only talks to the exact key it paired with, over QUIC with TLS 1.3.
- **A proper terminal.** Colours, mouse, full-screen programs (vim, htop, btop, Claude Code), pinch to zoom, extra keys row (Esc, Tab, ⇧Tab, Ctrl, Alt, arrows, Home/End, PgUp/PgDn), copy and paste, hardware keyboards.
- **Files both ways.** Browse the computer's files from the phone, download, upload, share anything from Android with "Send to computer", and open computer files from any app through the Android file picker. `ripple send FILE` on the computer offers files to the phone. Progress shows the size, speed and time left.
- **Android command line.** A local shell on the phone with `wd`, a command for managing the Wonderdesk suite (apps and versions, device and battery details).
- **Notifications.** A bell or `ripple notify TEXT` on the computer shows a notification on the phone, so you know when a long job finishes.
- **Monitor.** Open Monitor on the computer or phone to see computer usage and watch sessions. Choose a notification, sleep, or a command to run when the chosen condition is met. Monitors run on the computer even when the phone disconnects.

### Monitor safety and limits

Starting a monitor requires confirmation. Sleep and command actions have a
30 second countdown that can be cancelled. The monitor is one shot and is
not restored after the Ripple service restarts. Connected phones receive
notifications, but offline phone delivery is not guaranteed.

Claude monitoring reads the current terminal screen. It first needs to see
Claude working, then a ready prompt for 20 seconds. A visible usage limit is
not counted as completion. Automatic continuation is reported only when its
banner is visible. Screen detection is an estimate, not proof that the work
succeeded, and a changed layout, a hidden warning, an interrupted task or
background work can defeat it. Review this risk before selecting sleep or a
command. An unknown screen does not trigger the action.

For a script or other command, select **Successful command exit** and start
it from the monitor, or select a Ripple session originally started with a
command. Exit code zero is treated as success. A command entered into an
existing interactive shell cannot be verified this way. An OpenCode command
started in its own Ripple session can use the command exit mode. Detection of
individual turns in an interactive OpenCode session is not supported yet.
Commands used as monitor actions run as the logged in user through `/bin/sh`
with no interactive terminal.

## Install

### Computer (Linux)

Download `ripple-linux-x86_64-<version>.tar.gz` from Releases, unpack it, and run `./install.sh`. No root needed. It installs:

- `ripple`: the background service and command line (`~/.local/bin/ripple`)
- `ripple-terminal`: the desktop window with tabs (menu entry "Ripple")
- a systemd user service that starts Ripple when you log in

Then run `ripple pair` (or "Pair a phone" in the window's menu) and scan the code with the phone.

Update any time with `ripple update`.

If your firewall is on, Ripple still works through the doorbell. Allowing its port makes connecting at home faster:

    sudo ufw allow 47800:47801/udp comment Ripple

### Phone (Android)

Install Ripple from Wonderdesk Apphub (https://github.com/thoroftroy/App-Hub/releases), or download the APK from this repository's Releases. Needs Android 10 or newer.

## Command line

    ripple                      open a terminal attached to a new session
    ripple attach [ID|NAME]     attach to a session (newest if none given)
    ripple new [--name N] [--cwd DIR] [-- COMMAND…]   start a session in the background
    ripple ls                   list sessions
    ripple kill ID|NAME         end a session
    ripple pair                 pair a phone
    ripple devices / forget     paired phones
    ripple send FILE…           offer files to your phone
    ripple notify TEXT          notification on connected phones
    ripple status               addresses, doorbell and connection details
    ripple update               install the newest Linux release

## Privacy

No accounts, no tracking, no cloud. The doorbell only carries an encrypted note with network addresses (ChaCha20-Poly1305, key shared at pairing), sent through a public ntfy server by default; you can point it at your own with `ripple config doorbell_server URL`.

## Licence

Ripple is under the Wonderdesk License (see LICENSE). The Android terminal view is based on Termux's terminal-emulator and terminal-view libraries (Apache-2.0); see NOTICE.
