# Ripple changelog

## v0.3.0

- Ripple on the Wonderdesk Launcher's floating button: a card for each computer showing whether it is on, asleep or unreachable, its processor and memory, and what each terminal session is doing, with New session, Lock, Sleep and Disconnect (or Wake and Connect when it is off). Session alerts and files sent to you also appear as cards
- A one-tap Ripple note: the floating button can make a new note in a Ripple notepad (synced to your computer) and open it in Scribble
- Wake the computer from the floating button even when Ripple isn't open

## v0.2.1

- The connection (Disconnect) also shows as a card on the Wonderdesk Launcher's floating button, when it is in use

## v0.2.0

- Notepads: write a note in Scribble and Ripple keeps it in any folder you choose on your computer, in both directions, even while Ripple is closed (menu, Notepads). Put a project's notes in the project folder and they are there when you get home
- Clearer status when a computer can't be reached: "asleep since 14:32" if you put it to sleep from the phone, otherwise "not reachable, last seen 5 min ago", in the list, the title bar and the banner

## v0.1.21

- The top bar now shows how busy your computer is (processor, memory, graphics, temperature); tap it from the menu for the full picture with network, disk and uptime. Turn it off in Settings
- Sleep your computer from the menu, lock it, and wake it again from the phone when you are on the same network (the Wake button appears whenever it is offline)
- The usage figures are now always shown under the top bar, with a small bar for each, and say so plainly when the computer's Ripple is too old to report them. Sleep and lock now explain a failure (an older Ripple on the computer) instead of just showing an error
- Sessions you had open come back by themselves after the computer restarts or the Ripple service updates, in the same folders with the same names
- Copied text is shared both ways between the phone and the computer, with a clipboard history list on the phone (menu, Clipboard history)
- New Features section in Settings to turn off the clipboard sharing, Files button, sleep/lock/wake menu and session tabs
- Terminals started on the phone now open as tabs in the computer's Ripple window straight away, and renames show on both sides (needs the updated Linux app)

## v0.1.19

- Videos and music from your computer open in Cinema or Bliss Player at once and are read as they play, with a bigger read-ahead over several streams

## v0.1.18

- Wayfinder can now open big files from your computer straight in a viewer: the file is read as it plays, so a long video starts at once and you can jump around in it
- Fixed a rare crash where the background connection did not announce itself to Android in time

## v0.1.17

- Files from your computer open much faster: big videos and files start playing at once, read as you watch, instead of downloading the whole file first
- A file you already opened opens instantly next time, and a changed file is fetched again
- Saving and opening big files uses several streams at once and bigger network buffers, so transfers fill the connection sooner
- The computer side can now send just a slice of a file, which makes seeking in a video even snappier once the Linux app is updated

## v0.1.16

- New settings: vibrate on the terminal bell, and cursor shape

## v0.1.15

- New setting: how many lines of scrollback a terminal keeps
- Text size and accent colour chosen in Control Room now apply here
- New Rotate with the phone switch in Settings. Rotates by default

## v0.1.14

- A new icon

## v0.1.13

- A failed connection now says why, so it can be fixed
- Autocorrect switches on by itself inside full-screen apps like chats and stays off at the shell prompt
- The desktop terminal fills its window again when you return to it after using the phone
- Asks for the Nearby devices permission newer Android versions need for computers on the home network

## v0.1.12

- Touch apps that ask for hover tracking now get taps and scrolls. Some terminal apps only turn on that hover mode, which the emulator used to ignore, so taps fell through to the keyboard and swipes sent arrow keys

## v0.1.11

- The file link now streams reads as they arrive so Wayfinder can show progress and cancel mid download. Missing paths still fail fast with the real reason

## v0.1.10

- Tapping in the terminal no longer forces the keyboard when an interactive app uses the mouse. Taps reach menus and lists. Open the keyboard with the keyboard button when typing is needed
- Removed the extra drag handling that turned scrolls into arrow keys. Scrolling the transcript and pinching to zoom work as before
- Wayfinder file opens keep the leading slash on PC paths. A photo in the Ripple folder now opens instead of reporting it as missing

## v0.1.9

- Terminal output now goes out over a single background sender, in order: a slow connection can no longer freeze typing, dragging or tapping
- Terminal scrolling works again: vertical swipes move through the scrollback instead of doing nothing

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
- New sessions start at the home folder again. The current folder and recents are still one tap away as chips
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
