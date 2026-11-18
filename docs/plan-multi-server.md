# Plan: multiple servers (stage 1)

Status (2026-11-16): stage 1 done. Agent 1.1.0 (API 2) runs on jmpc; the app is built, to be tested on the user's
phone before publishing to the household. How it works is described in `architecture.md`, `api.md` and
`network-and-security.md`; this file stays as a record of decisions and use cases. Postponed: discovery (mDNS),
Docker, Windows/Mac (adapters in `mp_platform.py`), the phone as a server.

Mockups of the app screens: [ux-wiele-serwerow.html](ux-wiele-serwerow.html) (open in a browser; in Polish).

## Goal

Today the app knows one server (jmpc), which does everything: downloads torrents, serves files and plays in mpv.
We want several servers on the LAN (e.g. jmpc, a second Mint, a laptop, a computer in the garage, later someone
else's phone as a file server) and to choose in the app which one we watch from and on which screen.

Outside stage 1: network discovery (mDNS), Docker/Synology, Chromecast, Android as a server, Windows/Mac installers.
Stage 1 should make them possible, not implement them.

## Roles instead of "the server"

Every agent reports what it can do. Same agent code, only the enabled features differ.

| role | what it does | today |
|---|---|---|
| **files** | torrents and/or library, HTTP stream with Range, subtitles, X-Ready-End | jmpc |
| **screen** | plays a URL given from outside in mpv, remote control via `/api/player/*` | jmpc |
| **remote** | controls a screen | phone, browser |

A server can be only a screen (a laptop under the TV without a disk with videos), only files (a computer in the
garage without a monitor) or both (jmpc). The app combines any file server with any screen.

## Permissions

All paired devices have the same permissions (decision: household member = admin). The only change is that **every
device has its own token** instead of one shared token, so one device can be cut off (a lost phone) without
re-pairing the rest.

## Agent: `GET /api/info` (extending the existing one)

Today it returns `hostname`, `version`, `backend`, `keep_awake`, `iface`, `ip`, `mac`, `wake_on`, `wireless`.
Added:

- `id`: a stable UUID from `config.json` (generated on first start). The app recognizes the server by it, not by IP.
- `name`: a display name (hostname by default, changeable in the config).
- `api`: the API version number, so the app knows what to expect.
- `features`:
  - `torrent`: the client name (`deluge`, `transmission`) or nothing;
  - `library`: whether there are library folders;
  - `player`: whether there is mpv and a graphical session (without these the server isn't a screen);
  - `subs_search`: whether subliminal works;
  - `unlock`: whether it can remove the lock screen;
  - `apk`: whether it serves app updates;
  - `wake_others`: whether it will send a magic packet on behalf of the app (`POST /api/wake`).
- `power`: `suspend`, `poweroff` (true/false) and `wake`:
  `{"method": "wol", "mac", "confidence": "yes" | "maybe" | "no", "reason"}`.
  - Linux: cable + `ethtool` `Wake-on: g` → `maybe` (the BIOS may have WoL disabled, which can't be seen);
    Wi-Fi → `no` ("Wi-Fi"); no `ethtool`/`nmcli` → `maybe` with a reason.
  - Other systems later: Windows `powercfg /devicequery wake_armed`, Mac `pmset -g` (`womp`).
  - The agent never reports `yes`. Only the app knows "confirmed", once waking has worked.

An agent without `/api/info` in the new format (old version) is treated by the app like today's jmpc: all features.

### Platform adapters

The Linux-specific parts of the agent (`systemd-inhibit`, `systemctl suspend/poweroff`, `loginctl`, `/proc`, `xset`,
`nmcli`, `ethtool`) go behind one layer: "block sleep", "suspend", "power off", "unlock screen", "WoL state". When
something is missing, the feature drops out of `features`/`power` instead of throwing errors. Stage 1 has only
Linux, but with a split that allows adding Windows/Mac.

## A screen without files: how a screen gets a video from another server

Today `POST /api/player/open` with `ref` opens a local file. A URL variant is added:

1. The app asks the file server for a **ticket**: `POST /api/media/ticket` with `ref` → a stream URL and subtitle
   URLs with the ticket in a parameter (`?ticket=…`), valid e.g. for 12 h and only for this one file.
2. The app sends the screen `POST /api/player/open` with `url`, `subs[]`, `title`, `start`.
3. mpv on the screen reads over HTTP from the file server (X-Ready-End and waiting for pieces work the same as for
   the phone).

Thanks to the ticket the screen doesn't get the file server's token and doesn't need to be paired with it. When the
file and the screen are on the same server, today's path (local file) stays.

The app saves the watching position (already today `Settings.position` on the phone) by reading the screen's
`/api/player/status`, so it doesn't depend on who serves the file.

## App: use cases

1. **Server list** in the settings: add (QR or address + code), rename, remove. Each one shows its state: online /
   asleep (can be woken) / unavailable.
2. **Choosing the file server**: a switcher at the top (like accounts). Torrents and Library show the selected
   server. A shared library from all servers: later.
3. **Choosing the screen**: "On computer" becomes "Play on…" with a list of servers with `player` (plus "this
   phone"). The Remote controls the selected screen. The last choice is remembered.
4. **Server asleep**: instead of an error, "Garage is asleep. Wake it?", then waiting until it answers.
5. **Waking**: first a magic packet from the phone (works only in the same subnet), then through any running server
   with `wake_others` (e.g. when the garage is behind powerline/another router).
6. **Power buttons** according to `power`:

   | state | button |
   |---|---|
   | `suspend` + waking confirmed | "Sleep" |
   | `suspend` + `maybe` | "Sleep" + a one-time warning "not tested whether it can be woken" |
   | `suspend` + `no` | "Sleep" with ⚠ and a confirmation "you won't wake it remotely (reason), you'll have to walk over" |
   | no `suspend` | no button |
   | "Turn off" | always with a confirmation; without waking, the same warning |

7. **Magnet from another app**: one server with `torrent` → straight there; several → a question with "remember".
8. **"On phone"**: an entry knows its server. Offline it plays as today; "Resume" requires that server.
9. **IP change**: the app checks `id` on connect. A different `id` at the old address → it doesn't send the token,
   asks for a new address or rescanning the QR code.
10. **App updates**: from any server with `apk`, the highest version. `publish.sh` still publishes to jmpc.
11. **Migration**: today's server becomes server no. 1 and **keeps its keys without a prefix** (SimpleCache doesn't
    rename keys, so otherwise the buffer and downloaded videos would be lost; the same for positions `pos:`, `cw:`,
    `cu:`). Further servers have the `<id>:` prefix in `MediaRef.key()`.

## Pairing

Principle: **the server doesn't let anyone in on its own.** Trust comes from someone with access to the machine
(shell, SSH) or from an already paired device. There is no "pairing window" for anyone on the network, so a server
without a screen doesn't have to approve anything.

- **First device**: at the end `install.sh` prints a QR code (`qrencode -t ANSI`) and a short code in the terminal;
  later `magneticplayer_agent.py --pair` does the same. The code is single-use and valid for a few minutes.
- **More devices**: "Pair another device" in the app or in the browser generates the same kind of one-time code
  (today that QR contains the shared token).
- **Exchange**: `POST /api/pair` with the code and the device name → the device's own token. The server keeps a
  list: name, pairing date, last connection.
- **Cutting off a device** (lost phone): the device list in the app/browser with a "Revoke" button, and `--devices` /
  `--revoke` in the agent. As long as the servers are LAN-only, a phone away from home can't connect anyway; with
  remote access, revoking becomes essential.
- **The old shared token** keeps working as an "old device" until the phones are re-paired; then it is disabled with
  one command.
- **Later**: pairing a new phone with all servers at once. An app paired with several servers asks each one for a
  one-time code and shows one QR code with all of them.

## What we test on

- jmpc: files + screen (as today).
- a second Mint: separately as screen only (no Deluge and no library) and as files only.
- the phone as a file server: later (Android as a server), but the ticket format and `/api/info` should allow for it
  from the start.

## Order of work

1. Agent: `id`/`name`/`api`/`features`/`power` in `/api/info`, platform adapters (Linux).
2. Agent: per-device tokens, `/api/pair`, `--pair`, `--devices`, `--revoke`; compatibility with the old token.
3. App: server list, switcher, state and waking, power buttons according to `power`, key migration.
4. Agent + app: tickets, `player/open` with a URL, "Play on…".
5. Web: the same as the app, to the extent that makes sense in a browser.
