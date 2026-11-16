# Ripple changelog

## v0.1.8

- Wayfinder file opens download to a real file first instead of a pipe, so video players (which must seek) and picky image viewers actually play and show instead of going blank

## v0.1.7

- Downloads no longer fail silently: if the computer can't open a file you now get its real reason instead of a blank viewer

## v0.1.6

- New Terminal setting: keyboard suggestions (autocorrect). Off by default so commands stay exactly as typed; turn it on for prose

## v0.1.5

- Terminal typing: keys typed while offline for over a minute are dropped on reconnect instead of firing stale commands you can no longer see or delete, with a notice
- Cursor drag is sideways-only now, so scrolling the transcript with a vertical swipe no longer sprays arrow keys and fights the scroll
- The paired-computers list now includes each computer's shared Ripple folder path, so Wayfinder can open straight into it

## v0.1.4

- New machine link for Wonderdesk apps: Wayfinder lists paired computers and browses their files by itself, no picker scavenger hunt. It's guarded by a signature permission so only our apps get in
- File provider now honours the columns each caller asks for instead of crashing on subsets

## v0.1.3

- File provider now honours the columns each caller asks for, so Wayfinder lists paired computers and browses their files instead of coming back empty
- New sessions start at the home folder again — the current folder and recents are still one tap away as chips
- Drag anywhere in the terminal to move the cursor, trackpad-style: sideways for left/right, up/down for history or lines. Taps, long-press selection and pinch zoom work as before

## v0.1.2

- Opening Ripple on the phone goes back to the tab you were using on that computer, instead of jumping to the newest one
- A session can never be started by accident: a phone or window asking for a session that's gone now gets told so, instead of a new session being opened
- On the computer, the first Ripple window you open shows all your running sessions (even ones the phone is attached to) instead of starting another one
- `ripple <command> --help` shows the help instead of running "--help" in a new session

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
