# Ripple changelog

## v0.1.1

- New Aqua Glass colours: a deep navy terminal that turns milky blue over your wallpaper (see-through at 88% on Linux with a compositor). It's the new default for the Aqua look instead of plain white; the old white one is still there as Paper
- Fixed: `wd apps` and `wd device` in the Android command line could close straight away

## v0.1.0

- First release: your computer's terminal on your phone. Sessions live on the computer and keep running when the phone disconnects; reopen and the screen is redrawn exactly as it was
- Tabs for several sessions per computer, each showing the program running in it; the desktop window and the phone share the same sessions
- Pair once by scanning a code and comparing six digits; after that both sides only accept each other's exact keys (QUIC, TLS 1.3)
- Works away from home with no accounts or VPN: an encrypted doorbell asks the computer to open a direct path (tested at about 3 seconds over IPv6); at home it connects straight over Wi-Fi
- Full terminal: colours, mouse, full-screen programs (vim, htop, btop, Claude Code), pinch to zoom, extra keys row with Esc, Tab, ⇧Tab, Ctrl, Alt, arrows, Home/End and PgUp/PgDn
- Files: browse the computer from the phone, download and upload with size, speed and time left, "Send to computer" from any app's share menu, and computer files in the Android file picker
- Android command line on the phone, with `wd` for the Wonderdesk suite (apps and versions, device and battery)
- Notifications on the phone when the computer rings the bell or runs `ripple notify`
- Linux: `ripple` command line, a tabbed desktop window, a login service and `ripple update`
