# Installation and maintenance

## Computer

Requirements: Linux Mint or Ubuntu with a graphical session (Cinnamon), Python 3.10+, an Ethernet cable (for WoL).

```bash
bash server/install.sh                 # at the computer: the root part via pkexec (password dialog)
BACKEND=none bash server/install.sh    # computer only as a screen (or only a library): no Deluge
BACKEND=none PLAYER=0 POWER=0 PORT=8766 bash server/install.sh   # e.g. a laptop: files only, no sleep over the network
LITE=1 bash server/install.sh          # someone else's computer: no administrator password (below)
bash ~/.local/share/magneticplayer/uninstall.sh [--purge]   # uninstall
```

The variables are saved in `config.json` (`backend`, `player`, `power`, `port`; `LITE=1` adds `wake: false`), and
running `install.sh` again without them changes nothing there. `player: false`: the server isn't a screen, even if
it has mpv. `power: false`: it can't be put to sleep or turned off over the network (the app hides "Sleep"). On the
user's laptop the agent runs on 8766, because another service (`ekrand`) uses 8765 there.

At the end the script shows a pairing code and a QR code. Scan it with the phone's camera (it opens the app) or in
the app: Settings → Add server → address and code. A new code: `~/.local/share/magneticplayer/venv/bin/python
~/.local/share/magneticplayer/magneticplayer_agent.py --pair`, or from an already paired phone.

### Light install (`LITE=1`)

For a computer whose owner won't give the administrator password or doesn't want system changes (a friend,
parents). There is no root part, no torrents, no sleep over the network and no Wake-on-LAN (`backend: none`,
`power: false`, `wake: false`). The Library, uploads from the phone, subtitles and the screen remain (if mpv and a
graphical session are already there). Only `python3` and `systemd --user` are needed.

- Without the `python3-venv` package (fresh Ubuntu) the venv is created with `--without-pip`, and pip comes from
  `bootstrap.pypa.io/get-pip.py`. The venv provides subtitles and the QR code in the terminal (the `qrcode` module
  instead of `qrencode`).
- When there is no `~/Wideo`, `~/Videos` or `~/Filmy`, the installer creates the XDG videos folder (usually
  `~/Videos`). Without it there would be no Library, and uploads would have nowhere to go. `BACKEND=none` does the
  same.
- `loginctl enable-linger` (without root, when polkit allows it): the agent starts with the computer and keeps
  running after logout. The marker `~/.local/share/magneticplayer/.linger` tells `uninstall.sh` that the installer
  turned linger on.
- The firewall can't be opened without root. On Ubuntu and Mint ufw is off by default, so this usually doesn't
  matter.

Tested on 17.11.2026 on a clean Ubuntu 24.04 (VirtualBox, bridged network): installation, pairing, uploading with
Polish characters in the name, start after reboot without logging in, reinstallation and uninstallation with
`--purge`.

`uninstall.sh` stops and removes the services (Deluge only if our installer created them), the root extras of the
full install (polkit, udev WoL, ufw rules with the MagneticPlayer comment; via pkexec or sudo) and
`~/.local/share/magneticplayer`. With `--purge` it also removes `~/.config/magneticplayer` (token, paired phones).
It doesn't touch videos or system packages.

### From an existing server (agent 1.3+)

In the app: Settings → "Install on another computer" → "Show command". On the new computer (Linux, same network)
you paste into a terminal e.g. `curl -fsSL http://192.168.100.100:8765/i/ABC123 | bash`. The script downloads the
agent from the server (no GitHub or repository needed), installs it light or full (the "Without administrator
password" switch), and the new agent registers with a pairing code. The phone that showed the command pairs with it
on its own. The code works once, for 30 min. Without a phone at hand: `--pair` on the new computer, as usual.
Details: `docs/api.md`.

The installer copies `install.sh`, `install-root.sh` and `uninstall.sh` next to the agent, because the server builds
the bundle from them. An older server gets them after running `install.sh` again (or via `scp` as in the quick
update).

Tested on 17.11.2026: jmpc → clean Ubuntu 24.04 in VirtualBox (light install, registration, pairing with the code
from the registration, reuse of the code rejected).

### From the phone, without a server (app 1.2611+)

The same "Install on another computer" button also works without any server: the source is "This phone".
`build-apk.sh` bundles the agent into the APK (`assets/agent/agent.tgz`, ~80 KB: the files from `server/` tracked by
git; not `.tar.gz`, because AAPT unpacks such assets) together with the `join.sh`/`join.ps1` templates. While the
dialog is open the phone runs a small HTTP server on the Wi-Fi (`InstallShare.kt`, random port) with the same routes
as the agent: `/i/<code>`, `/i/<code>/ps`, `/i/<code>/bundle.tar.gz` and `POST /api/install/join`. The command looks
as usual (`curl -fsSL http://<phone>:<port>/i/<code> | bash`), and once the new agent registers, the phone pairs on
its own. The dialog must stay open until the installation finishes.

When the dialog opens, the app checks (at most every 6 hours) the latest GitHub release (`AgentBundle.kt`): it
downloads the manifest, verifies its signature with the key in the app and, when the bundle is newer than the one
in the APK, downloads it and compares the sha256. The phone then serves that bundle ("agent … from release
d.MM.yyyy" in the dialog); without a network or on a signature error the bundle from the APK stays. The version
(commit time) is carried by the signed manifest, so an older bundle can't be slipped in.

Reinstalling on the same computer (e.g. after `uninstall.sh --purge`, with a new agent id) replaces the old entry
with the same address and port in the app instead of adding a second one (`Pairing.pair`); this applies to every
pairing, not only from the phone.

The phone doesn't need internet, but the new computer does: the bundle is just the agent code, and the installer
fetches the dependencies (pip: subtitles, Chromecast, QR; mpv; Python on Windows). Without internet on Linux the
library, uploads and watching on the phone work; on Windows without Python the installation won't start.

Python: Linux requires 3.8+ (checked by `join.sh` and `install.sh`). On Windows the installer looks for Python 3.9+,
and when it's missing it installs 3.12 for the user via winget, or without winget (Windows 10 LTSC, old Windows 10)
with the official python.org installer (`/quiet InstallAllUsers=0`). Both without an administrator.

### Windows 10/11 (light install)

From the app: the "Windows" switch in the same dialog. The command `irm http://<server>:8765/i/<code>/ps | iex` is
pasted **into an open PowerShell window** (right-click Start → Terminal). Given directly on a command line (Win+R,
`powershell -c "irm … | iex"`) Defender blocks it as `Trojan:Win32/Commando.B!ml`; pasted into the window it goes
through. Without another server: unpack the repository and run
`powershell -ExecutionPolicy Bypass -File server\install.ps1`.

`install.ps1` (without an administrator):
- Python 3.10+ from `py`/`python` (skipping the Store stub), and when it's missing,
  `winget install Python.Python.3.12 --scope user`;
- the agent in `%LOCALAPPDATA%\MagneticPlayer`, a venv with `subliminal`, `pychromecast`, `qrcode`, `numpy`, `webrtcvad-wheels` and `onnxruntime` (optional, better speech detection) (fitting subtitles to the film; needs `ffmpeg` on the PATH, which the Windows install doesn't bring);
- mpv (screen): the `mpv-*-x86_64-w64-mingw32-full.zip` release from GitHub into `…\MagneticPlayer\mpv` (the MSVC
  build without the VC++ runtime doesn't start, code 0xC0000135);
- port: the first free one from 8765 (exclusive bind: a plain `SO_REUSEADDR` on Windows allows sharing the port with
  another program listening on 127.0.0.1);
- autostart: `HKCU\…\Run` → `pythonw -X utf8 magneticplayer_agent.py` (log in `agent.log` next to the agent);
- firewall: when run as administrator it opens the TCP port and UDP 47700 for private networks and removes the
  "Block" rules Windows creates by itself after "Cancel" in the firewall dialog (they apply to the base
  `pythonw.exe`, because the venv launcher runs the base interpreter). Without an administrator Windows asks for
  access at the first start: tick private networks. On a "public" network the firewall blocks the phone anyway.

Works: Library, uploads, subtitles, screen (mpv via the pipe `\\.\pipe\magneticplayer-mpv`, one persistent
connection, because a new one to mpv takes up to 3 s), Chromecast/DLNA from the server. Missing: torrents, sleep,
waking, unlocking the screen. Uninstall: `uninstall.ps1 [-Purge]` from the agent folder.

Tested on 17.11.2026 on Windows 11 in VirtualBox: installation and reinstallation, port taken by another program,
pairing, uploading, playback (open, pause, seek, stop), joining from jmpc via `irm … | iex`, uninstalling with
`-Purge`. The mpv screen requires the agent to run in the logged-in user's session (autostart ensures that).

### Over SSH

Over SSH `pkexec` doesn't work, because there is no polkit session. In that case do the root part with `sudo`:

```bash
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
ssh -t jmpc sudo bash .local/src/MagneticPlayer/server/install-root.sh jm
ssh jmpc 'SKIP_ROOT=1 bash .local/src/MagneticPlayer/server/install.sh'
```

What the scripts do:

| script | what it does |
|---|---|
| `install-root.sh <user> [port] [backend]` | installs the packages `mpv ethtool qrencode python3-venv`, and with the Deluge backend also `deluged deluge-web`<br>disables the system Deluge services (they run as user services)<br>adds the polkit rule `/etc/polkit-1/rules.d/49-magneticplayer.rules`, so the background service can block sleep and suspend<br>enables Wake-on-LAN: NetworkManager `802-3-ethernet.wake-on-lan magic`, udev `81-magneticplayer-wol.rules`, ethtool<br>opens port 8765 and UDP 47700 ("what's playing", multicast) in ufw for private networks |
| `install.sh` | copies the agent to `~/.local/share/magneticplayer/`<br>creates a venv with `subliminal` and `numpy` + `webrtcvad-wheels` + `onnxruntime` (optional; subtitles fitted to the film)<br>sets up `systemd --user` services: `deluged`, `deluge-web` (127.0.0.1:8112) and `magneticplayer` (:8765)<br>switches Deluge GTK to client mode, connecting to `deluged`<br>prints a pairing code with a QR code (`--pair`)<br>copies `uninstall.sh` next to the agent |

Running `install.sh` again updates the agent and leaves the configuration and token alone.

### Chromecast and its versions

`install-root.sh` installs `ffmpeg`, and `install.sh` installs `pychromecast` in the venv. The agent picks the
encoder for "Prepare a Chromecast version" by itself at startup and the app shows it in the server details. On jmpc
(GTX 970) it should be NVENC, as long as the NVIDIA driver with `libnvidia-encode` works. On Intel it's VAAPI, on a
Mac VideoToolbox. The versions are stored in `.mp-cast/` next to the videos and can be deleted by hand.

### Whisper for subtitle timing (optional, agent 1.20+)

Subtitles of a release cut differently from the film on disk are re-timed by Whisper (see architecture.md). It lives
in its own venv next to the agent, `~/.local/share/magneticplayer/whisper-venv` (or set `"whisper": {"python": …}` in
`config.json`), so a model of ~0.5-1 GB and the CUDA libraries stay out of the agent's venv:

```bash
python3 -m venv ~/.local/share/magneticplayer/whisper-venv
~/.local/share/magneticplayer/whisper-venv/bin/pip install faster-whisper     # recent NVIDIA cards and CPU
```

The model (`small.en`, `small`) downloads on first use. Old NVIDIA cards (Maxwell, e.g. GTX 970): CTranslate2 4.x fails
with "invalid device ordinal"; 3.24 with CUDA 11 works:

```bash
V=~/.local/share/magneticplayer/whisper-venv
$V/bin/pip install ctranslate2==3.24.0 nvidia-cublas-cu11 'nvidia-cudnn-cu11==8.9.*' nvidia-cuda-nvrtc-cu11 \
  'av==12.3.0' 'numpy<2' 'huggingface_hub<1' tokenizers onnxruntime
$V/bin/pip install --no-deps faster-whisper==0.10.1
```

The agent finds the CUDA libraries in the venv itself (`LD_LIBRARY_PATH` for the worker). Without a usable GPU it runs
on the processor (`int8`). `features.subs_retime` in `/api/info` says whether the agent sees it.

### Quick update of just the agent (development)

```bash
scp server/*.py server/*.sh jmpc:.local/share/magneticplayer/ && ssh jmpc systemctl --user restart magneticplayer
```

### Agent self-update (agent 1.6+, Linux)

Like the app, the agent updates itself from GitHub releases (`server/mp_selfupdate.py`). Mode `agent_updates` in
`config.json`: `auto` (default) or `manual`; in the app: Settings → server → "Agent updates", with "Update now" when a
newer version exists.

- 90 s after start and then every 6 hours the agent fetches `agent.json` and `agent.json.sig` from the latest
  release and verifies the ECDSA P-256 signature itself (pure Python, the public key is in the module, the same as
  `phone/tools/agent-signing.pub`). Only a **higher** `agent_version` than the running one counts, so the agent is
  never downgraded and a hand-deployed development agent is left alone. That's why `tag-release.sh` refuses a release
  where `server/` changed but `VERSION` didn't.
- The bundle (`agent.tgz`, checked against the sha256 and size from the manifest; only `server/*.py|sh|ps1` and
  `server/web/*` entries) is unpacked into `~/.local/share/magneticplayer/update/<version>/`.
- It waits for a quiet moment: no stream or upload in the last 5 minutes, no queued conversion, no copy, move or
  replication under way. mpv survives a restart, so watching on the computer screen doesn't block it, and so do
  encodes (agent 1.9+: shrinking and "for Chromecast" versions run detached and the new agent attaches to them).
- `self-update.sh` from the new bundle runs in its own systemd unit (`systemd-run --user`, it survives the agent
  restart): it backs up the current agent to `prev/`, runs `install.sh` with `UPDATE=1` (files and missing Python
  packages only: no root, no config, no Deluge, no unit changes, no pairing; nothing is upgraded with pip) and waits
  up to 90 s for `/api/ping` with the new version. Otherwise it puts `prev/` back and restarts the old agent.
- The outcome is in `update-result.json` and in the agent log after it starts; the details in `update.log` next to
  the agent. A version that was rolled back isn't retried automatically, only after "Update now".
- Apt packages (`install-root.sh`) aren't touched: a version that needs new ones needs a manual `install.sh`.
- Windows: not yet (the feature is missing from `/api/info`, the app shows nothing).

Tested on jmpc (18.11.2026): signature and download with the real release, a bundle with a wrong sha256 rejected,
update 1.6.0 → 1.6.1 in 4 s, an agent that fails at start (1.6.2) rolled back to 1.6.1 after 90 s.

### Files on the computer

| path | contents |
|---|---|
| `~/.config/magneticplayer/config.json` | the server's `id` and `name`, the old shared token, port, backend (`deluge`/`transmission`/`none`), the deluge-web password, library folders, the OpenSubtitles key and optional login, `mpv_args`, `auto_unlock`, `upload_dir` (folder for videos uploaded from the phone; by default `Z telefonu` ("From phone") in the first library folder) |
| `~/.config/magneticplayer/devices.json` | paired devices (token hashes), active pairing codes, the ticket key |
| `~/.config/magneticplayer/jobs.json` | jobs from the phone: magnet, after-download action, whether it was done |
| `~/.local/share/magneticplayer/` | the agent (`magneticplayer_agent.py`, `mp_*.py`) and `venv/` with subliminal, numpy, webrtcvad, onnxruntime |
| `~/.local/share/magneticplayer/apk/` | the published app and `version.json`, used by phone updates |
| `$XDG_RUNTIME_DIR/magneticplayer-mpv.sock` | mpv's JSON IPC socket |

Logs:
```bash
journalctl --user -u magneticplayer -f
journalctl --user -u deluged
```

## Phone

### Build

```bash
phone/tools/build-apk.sh        # in the ghcr.io/cirruslabs/android-sdk:36 container → phone/out/MagneticPlayer.apk
adb install -r phone/out/MagneticPlayer.apk
```

- `versionCode` increases by itself (minutes since 2025-01-01), and `versionName` has the form `1.YYMMDD.HHMM`. The
  build also writes `phone/out/version.json`.
- Gradle keeps its cache in `phone/.gradle-home` (about 1 GB, outside git).
- The container gets 4 CPUs and 6 GB of memory by default. Change this with `CPUS=` and `MEM=`.

### Signing key

Since November 2026 the app has the id `net.treetank.magneticplayer` (previously `eu.jm.magneticplayer`), and
releases are signed with the **treetank key** (the same as timeprotect, only in CI variables, see "Releases"). The
old app won't update to the new one (different package and key): the new one is installed alongside ("Share app" or
from a GitHub release), paired again, and the old one is uninstalled.

`phone/magneticplayer.keystore` and `phone/keystore.properties` (**outside git**, generated by the first build) are
now the development key: it signs local builds (`build-apk.sh`), i.e. the emulator and tests. Such a build won't
install over a release version without uninstalling first. We keep a backup anyway:

The **backup** is on `ovh-vps` in `~/backup/MagneticPlayer/` (folder 700, files 600). Next to it is `README.txt`
with restore instructions and sha256 sums. Restoring:

```bash
scp 'ovh-vps:backup/MagneticPlayer/{magneticplayer.keystore,keystore.properties}' phone/
```

The code is public (MIT): `origin` = gitlab.com/treetank/magneticplayer, mirror github.com/treetank-net/magneticplayer
(the `mirror-github` job).

### Releases (GitHub)

GitLab CI makes releases, following timeprotect: locally only `phone/tools/tag-release.sh` (tag `v1.YYMMDD.HHMM` on
the pushed `main`). The `release` job on the `vps2` runner (`phone/tools/release-ci.sh`):

1. builds the APK (2 CPUs, 5 GB: production runs on vps2) signed with the treetank key (alias `treetank`); the
   certificate fingerprint is checked (`EXPECTED_CERT`), because every release must have the same key, otherwise
   phones won't accept the update;
2. packages the agent (`phone/tools/agent-bundle.sh`) and writes the `agent.json` manifest (commit time of `server/`,
   commit, version, sha256 of the bundle) signed with ECDSA P-256 (`agent.json.sig`);
3. waits for the mirror to push the tag to GitHub and publishes the release (`phone/tools/github-release.py`): APK,
   `version.json`, `agent.tgz`, `agent.json`, `agent.json.sig`.

The keys exist only in protected CI variables of the project with the `release` environment (`MP_KEYSTORE_B64`,
`MP_KEYSTORE_PASS`: copies of `TP_KEYSTORE_*` from `treetank/timeprotect-android`; `MP_AGENT_KEY_B64`), so no other
job gets them; `v*` tags are protected. The bundle key: `~/.config/magneticplayer-release/agent-signing.pem` with a
copy on `ovh-vps` in `~/backup/MagneticPlayer/`; the public key is in the repo (`phone/tools/agent-signing.pub`), and
`build-apk.sh` copies it into the app. A GitHub release doesn't replace `publish.sh`: household members get updates
from the computer as before.

### More phones (household members)

1. On your phone: Settings → **"Share app (WhatsApp etc.)"** sends the installed APK file. In WhatsApp, send it as a
   document.
2. The other person installs the APK, and Android asks for permission to install from the messenger.
3. On your phone: Settings → **"Pair another device"**, tick the servers → a QR code with one-time codes (10 min).
   The other person scans it with the camera and gets all the ticked servers at once. If the link doesn't open:
   "Share link", and in the app Settings → Add server → "Paste pairing link". Or they type the address and code by
   hand.

### Several servers

- Each computer with the agent is a separate server: "files" (torrents and/or library) and/or "screen" (mpv).
  A computer just for watching: `BACKEND=none bash server/install.sh`.
- The server name is at the top of the app; ▾ (when there are several servers) switches where Downloading and Library
  come from.
- The screen button next to a video plays it on the selected computer. When the video is on another server, the
  screen gets a URL with a ticket and pulls the video over the network. The Remote has target chips: the screen (▾
  when there are several), Chromecast and TV.
- A sleeping server is woken by the phone, and after a few seconds also by another running server (when the phone
  can't reach it). The "Sleep" dialog warns when waking over the network is uncertain or impossible (Wi-Fi, no WoL).
- An old single-token server moves to the list by itself, keeping positions, the buffer and downloaded videos.

### Updates for household members

The agent downloads every new GitHub release by itself (within a few hours of the release, right away after an
agent restart) and offers it to the phones; details in [architecture.md](architecture.md#app-updates). To publish
only by hand, turn it off in the app: Settings → server → "App updates from GitHub". Then, after testing on your own
phone:

```bash
phone/tools/publish.sh          # out/MagneticPlayer.apk + version.json → jmpc:~/.local/share/magneticplayer/apk/
phone/tools/publish.sh --release latest   # the same with the APK from the GitHub release (treetank key, as on the phones)
```

- Every time the app opens or returns to the screen (after connecting to the computer) it asks `/api/app/version`,
  without caching. When the version is newer than the installed one, it shows the "Update available" bar. An open
  app (e.g. the Remote while watching) doesn't check in the background.
- "Update" downloads the file through PackageInstaller and Android asks for confirmation. The first time it also
  asks for permission to install apps from MagneticPlayer.
- A build alone doesn't publish anything.

### Magnet links

When another app (e.g. Flud) opens magnets, go to Android Settings → Apps → that app → "Open by default" and clear
the defaults. On the next magnet choose MagneticPlayer → "Always".

## Web client (laptop, tablet)

The phone gives you a link with a code: Settings → "Pair another device" → the address
`http://192.168.100.100:8765/#p=<CODE>`. The browser exchanges the code for its own token (the device
"Browser · system" in the list), the code disappears from the address bar, so later plain
`http://192.168.100.100:8765/` is enough. The code can also be typed on the login page. The old `#t=<token>` way
works as long as the old shared token is enabled. Adding `#tab=remote` (or `library`, `torrents`) opens the chosen
tab right away.

| tab | what it can do |
|---|---|
| Downloading | adding a magnet, "after download", pause and resume, files with "how much can be watched", earlier jobs |
| Library | browsing folders (they are added in the app) |
| Remote | the same as in the app, plus keys: space, ← →, ↑ ↓, `f`, `m` |

With every video: "▶ On computer", "In browser", "VLC link" and "Subtitles…".

Browser limitations:
- **Playback:** the browser plays H.264 with AAC, sometimes AV1. It won't play HEVC/x265 or E-AC3/DTS audio. Then
  "On computer" or VLC remain.
- **Subtitles:** the agent converts them on the fly from SRT to WebVTT (`/sub?…&vtt=1`).
- **Waking:** the browser can't send a magic packet, so wake a sleeping computer from the phone.

The page is a single file `server/web/index.html` (no dependencies). `install.sh` copies it to
`~/.local/share/magneticplayer/web/`.

## Troubleshooting

| symptom | cause and fix |
|---|---|
| "The computer isn't responding" on a new phone | no token, pair with the QR code; or the phone isn't on the home Wi-Fi |
| WoL doesn't wake it | the computer must be on a cable<br>check `nmcli -g 802-3-ethernet.wake-on-lan c show <connection>` (should be `magic`) and `cat /sys/class/net/<if>/device/power/wakeup` (`enabled`)<br>after a full shutdown (S5) WoL must also be enabled in the BIOS |
| mpv isn't visible on the computer | screen locked: "On computer" and the menu → "Turn on and unlock screen" remove the lock (`auto_unlock`). If not, check `loginctl show-user $USER -p Display` (graphical session) |
| "On computer" hangs for 5 s and returns an error from `cinnamon-screensaver-command` | old agent version: `--deactivate` in Cinnamon 5.4 crashes the screensaver (`grab_helper`). Update the agent |
| OpenSubtitles: "daily download limit" | an "Under development" key gives 100 downloads per day, and anonymous downloads have the account limit. Entering a login in the app or another provider helps |
| `pkexec` over SSH: "No session for cookie" | use the `sudo` variant described above |
| Samsung TV shows "loading…" and nothing plays | the TV is showing a permission prompt for a new device (phone or PC). Allow it with the TV remote and send the video again |
| a downloaded video "is gone from the phone" | old versions kept downloads in `cacheDir`, which Android clears when space runs low. Since 1.2.0 they are in `noBackupFilesDir/media`, and the list shows how much is left. Download again |
| torrent "paused" although nobody clicked | the "stop after download" action or a pause from Deluge GTK, check the job history |

### Streaming diagnostics

`ssh jmpc touch ~/.config/magneticplayer/debug-stream` turns on a line in the agent log
(`journalctl --user -u magneticplayer`) for every video and subtitle request: the range (`Range`), KB sent, time.
`rm` that file to turn it off.
