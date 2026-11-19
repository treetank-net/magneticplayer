# MagneticPlayer: notes for the agent

Home project: magnet link on the phone → Deluge on the PC → watch on the phone, in a browser, or in mpv on the PC with
the phone as a remote. The human-facing description is in `README.md` and `docs/`. Read them before a bigger change:
- `docs/architecture.md`: components, flows, subtitles, lock screen, web client;
- `docs/api.md`: every agent endpoint;
- `docs/install-and-maintenance.md`: deployment, app updates, signing key, common problems;
- `docs/network-and-security.md`: the "LAN only" assumption, tokens, `auto_unlock`;
- `docs/devices.md`: how to query a Chromecast/DLNA/Samsung, what it is and what it can do; Chromecast profiles and sources;
- `docs/plan-multi-server.md`: stage 1 decisions (multiple servers, files vs screen, pairing, per-device tokens), shipped;
- `docs/plan-library.md`: folder tags and visibility, magnet → folder, disk organising, covers (in progress).

Answer the user in Polish.

## Where things are

| what | where |
|---|---|
| PC with the agent | `ssh jmpc` (Linux Mint 21, Cinnamon 5.4, 192.168.100.100), user `jm`, graphical session `c1` (X11) |
| agent on the PC | `~/.local/share/magneticplayer/` (`*.py`, `web/`, `apk/`, `venv/`), service `systemctl --user … magneticplayer`, port 8765 |
| source copy on the PC | `~/.local/src/MagneticPlayer/server` (`install.sh` runs from it) |
| config and token | `jmpc:~/.config/magneticplayer/config.json`; token for terminal tests: `cli-token` next to it (device "Testy z terminala (jmpc)"; the old shared token from `config.json` is disabled). **Never print or paste tokens anywhere** |
| repo | **public** (MIT): `origin` = gitlab.com/treetank/magneticplayer, CI mirrors to github.com/treetank-net/magneticplayer; `ovh` = `ovh-vps:git/MagneticPlayer.git` (private copy). Private notes in `CLAUDE.local.md` (not in git) |
| signing key backup | `ovh-vps:~/backup/MagneticPlayer/` (+ `README.txt`) |
| phones | the user's Pixel 8a (adb over Wi-Fi, the port changes every debugging session) and the user's wife's phone (updates only through the agent) |

## Everyday work

```bash
# agent: deploy after a change in server/
python3 -m py_compile server/*.py
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
scp -q server/*.py server/*.sh jmpc:.local/share/magneticplayer/ && scp -q server/web/index.html jmpc:.local/share/magneticplayer/web/
ssh jmpc systemctl --user restart magneticplayer
ssh jmpc journalctl --user -u magneticplayer -n 50

# app: the phones have the treetank key, so an update = a CI release (a local build has the debug key, for the
# emulator). Tag → CI builds and publishes on GitHub → agents fetch it themselves (mp_appupdate, every 6 h and one
# minute after an agent restart). Right away, without waiting (or in "manual" mode):
phone/tools/tag-release.sh            # after commit and push; CI ~10 min
phone/tools/publish.sh --release latest
```

API tests on the PC, without taking the token off the machine:

```bash
ssh jmpc 'T=$(cat .config/magneticplayer/cli-token); curl -s -H "Authorization: Bearer $T" localhost:8765/api/player/status'
```

Commands in the graphical session over ssh need `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus DISPLAY=:0`.

The user asks for commit and push after a finished change (`git push origin main && git push ovh main`). The repo is
public: titles of films from the user's disk, tokens and other private details never go into commits (use
public-domain films as examples). Code and documentation change together.

## Emulator (when the phone is busy)

Test the app on the user's phone by default; the emulator only when the user needs the phone. jmpc has VT-x off in the BIOS (no KVM), so the emulator runs on the laptop only, against the real jmpc agent. The timeprotect SDK
`~/.cache/tp-emu-sdk` (read-only, android-33), own AVD `~/.cache/mp-emu-avd/mp.avd`, timeprotect's script:

```bash
TP_EMU_AVD=$HOME/.cache/mp-emu-avd TP_EMU_NAME=mp TP_EMU_PORT=5556 TP_EMU_MEM=2048 TP_EMU_MEMMAX=4G TP_EMU_CPUS=8,9 \
  TP_EMU_SYSTEM='system-images;android-33;google_apis;x86_64' TP_EMU_LOG=$HOME/.cache/mp-emu.log \
  bash ~/projects/personal/timeprotect/tools/emulator.sh start   # … adb <command> / stop
```

On the laptop only with these limits, and shut down after testing (one emulator at a time, never alongside an APK
build). The emulator and VirtualBox don't run together: with a VM running the emulator dies with "KVM: entry failed,
hardware error 0x0". Before starting, `VBoxManage controlvm <vm> savestate` (win11 only via LOCK); resume them after
testing. Emulator → LAN works through NAT (192.168.100.100:8765); LAN → emulator: `adb forward` + `socat`.
Win11 in VirtualBox: the `~/.cache/winvm/LOCK` protocol (shared with timeprotect), after a test the snapshot
`przed-magneticplayer`.

## Rules

- The repo is in English: documentation (`docs/`, this file), comments, docstrings, logs. Polish only for the second
  language of user-facing texts (below) and `README.pl.md`.
- Two UI languages: English by default, Polish for `pl*`. App: `res/values/strings_*.xml` (EN) and `res/values-pl/`
  (PL), wording as in `docs/` and the current UI. Agent: `T("en", "pl")` from `mp_i18n` (language from
  `Accept-Language`), web page: `tr()` and `data-i18n`, scripts: `say "EN" "PL"` / `Say`. New user-facing text is
  always in both languages.
- Secrets: `phone/magneticplayer.keystore`, `phone/keystore.properties` and the token are outside git. Hooks block
  access to `.keystore` and `~/.ssh`: don't work around them, give the user the command instead (via the clipboard,
  `xclip`).
- Root on the PC: `ssh -t jmpc sudo …` (pkexec doesn't work over ssh). Locally `pkexec`.
- The user's wife uses the app: every release (tag) reaches all phones, because agents fetch it themselves. The user
  treats CI releases as tested, so tag only working code.
- Don't lock the screen or suspend the PC without need: someone may be sitting at it or watching.

## Pitfalls already hit

- **Cinnamon 5.4:** `cinnamon-screensaver-command --deactivate` crashes the screensaver (`grab_helper`) and hangs.
  Because of it "On computer" returned 500. Unlock only with `loginctl unlock-session <Display>` (see
  `unlock_screen()`). `grab_helper` exceptions in the screensaver log after unlocking an unlocked screen are harmless.
- `pgrep -x cinnamon-screensaver` doesn't find the process (the name is truncated); use `pgrep -af '^cinnamon-screensaver'`.
- **subliminal 2.8:** downloading from OpenSubtitles without a login goes through `download_subtitle.__wrapped__`,
  and the limit exception is called `DownloadLimitExceeded`.
  - Podnapisi.net doesn't work (DNS), and tvsubtitles returns 403, so it was removed.
  - BSPlayer can think for ~45 s; that's what "Enough" is for.
- Without a title filter OpenSubtitles returns "similar" films (Der Golem → a completely different film). The filter
  and a second round with the title in `[brackets]` are in `mp_subs.search`.
- Web: don't pass arrays to `replaceChildren`, use `put()`.
- An adb screenshot of the phone is black while the screen is off. Use `uiautomator dump` then. Don't unlock the phone.
- Samsung (UE43J5500, DLNA) asks on screen for consent for every new controlling address; without `DLNA.ORG_OP=01` it
  doesn't seek.
- The "Duży pokój" Chromecast has fixed volume (volume only with the TV remote).
- **Pixel 8a, XviD/DivX (MPEG-4 ASP with B-frames):** the hardware `c2.exynos.mpeg4.decoder` falls behind (~16 of 24
  fps reached the screen, picture drifted from the sound). The software `c2.android.mpeg4.decoder` keeps 24 fps but
  decodes such AVI files wrong (blocks all over, frames back and forth: B-frames out of order), so it is NOT used.
  Measure what reaches the screen with `dumpsys SurfaceFlinger --timestats` (the SurfaceView layer's `totalFrames`)
  and always look at a paused frame (`adb exec-out screencap -p`), a frame count alone says nothing about the picture.
- Android clears `cacheDir` when the app exceeds its cache quota: downloaded films live in `noBackupFilesDir/media`.
- media3 `MediaSessionService`: the session must be added with `addSession()`, otherwise there's no notification until
  a controller connects.
- **GIL and old disks:** in Python 3.10 `DirEntry.stat()` (and `is_dir()` without `d_type`) calls `stat()` without
  releasing the GIL. A USB disk spinning up (ntfs-3g) froze the whole agent for ~3 s and the app showed "jmpc is
  asleep". In the agent use `os.stat(e.path)` / `os.path.isdir(e.path)`.
- `systemctl --user restart magneticplayer` used to kill mpv (same cgroup); that's why mpv starts via
  `systemd-run --scope`.
- **Windows:** Defender blocks `powershell -c "irm …/ps | iex"` (also over ssh) as `Commando.B!ml`; pasted into a
  PowerShell window it works. Tests over ssh: put the command in a `.ps1` file and use `-File`.
  - mpv: `os.path.exists` on `\\.\pipe\…` can be False while mpv runs (pipe list: `socket_exists()`); a new pipe
    connection takes up to 3 s, so there's one persistent one; mpv doesn't exit after `quit` while a client holds
    the pipe.
  - ssh into win11 is session 0: mpv starts but no window is visible. Agent in the user's session: `schtasks /IT`.
  - Windows itself creates "Block" firewall rules for the base `pythonw.exe` after "Cancel"; `install.ps1` as admin
    removes them.

## Open items

- **TVs, agent 1.2.0, shared Remote: verified on hardware** (jmpc NVENC, laptop VAAPI, "Prepare", Chromecast from
  the server and from the phone, Samsung DLNA with subtitles and seeking, Remote for mpv/Chromecast/DLNA,
  notifications, volume outside the app, mpv survives an agent restart, Chromecast from the web page (via the agent
  API), phone as a source for the jmpc screen, DLNA recovery after the app is killed). Remaining:
  - the web page in a browser (Chromecast card after a `volume_fixed` change) — only the API and JS syntax were
    checked. The page can be tested with Playwright from the npx cache (`~/.npm/_npx/*/node_modules/playwright`)
    with `executablePath: "/usr/bin/google-chrome"` (its own browsers are a different version);
  - update after the app returns to the foreground (wake-up marks itself as verified on a normal wake from the app;
    the "Check wake-up" button was removed as redundant);
  - the Remote refreshes 4×/s, so `uiautomator dump` often fails ("could not get idle state"): use screenshots.
- **Agent 1.5.0 + app v1.261118.2108: verified on the Pixel** (magnet → recognised folder, "Change" moves the torrent
  and remembers the choice, library search). The player's own reconnect wasn't really exercised: an agent restart
  is covered by the buffer. Missing: re-recognising a magnet without `dn=` once metadata arrives.
- **Agent 1.6.1 + app v1.261118.2230: verified on the Pixel** (read-only on the real library: disks with library vs
  other files, previews of free-up, tidy, duplicates and shrink, folder profile and "Also copy to", covers, tag filter,
  agent update switch). Not exercised on hardware yet: running a real move/shrink task to the end and its
  notification (only on local test files), replication on jmpc, self-update from a real release (1.6.0 → 1.6.1 was
  hand-deployed), MCP with Claude Code (needs the user's OAuth approval on the phone).

- Idea: remote access from outside the LAN (Tailscale/WireGuard, described in `docs/network-and-security.md`).
