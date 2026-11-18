# MagneticPlayer

A magnet link on your phone → the torrent downloads on your home computer (woken up with Wake-on-LAN) → you watch
it on the phone, in a browser, on the computer's screen with the phone as a remote, or on the TV (Chromecast, DLNA).
Everything stays in your home network (LAN): no cloud, no account.

[Polska wersja](README.pl.md)

```
server/   agent for the home computer (Linux or Windows 10/11; Python standard library + optional extras in a venv)
phone/    Android 8+ app (Kotlin, Jetpack Compose, Media3/ExoPlayer + FFmpeg decoder for AC3/E-AC3/DTS)
docs/     documentation (in Polish for now)
```

## What it does

- **Magnets:** tap a `magnet:` link or “Share” it from any app and it goes to Deluge (or Transmission) on the
  computer. A sleeping computer is woken up first. Sequential download by default, so you can start watching early.
- **How much is watchable:** for each video, the part downloaded contiguously from the start, in percent and minutes.
- **After download:** nothing, stop the torrent, or put the computer to sleep. History of requests sent from the phone,
  with “Download again”.
- **Library:** chosen folders on the computer, videos and music only.
- **Playback:**
  - on the phone (ExoPlayer, external and embedded subtitles), in VLC, or in mpv on the computer;
  - hand a video over from the phone to the computer at the same position;
  - Chromecast and DLNA TVs, with subtitles; videos the TV can't play can be transcoded first (“Prepare”,
    hardware-accelerated where available);
  - download videos to the phone for offline viewing, and send them from the phone back to a computer.
- **Remote:** for mpv, Chromecast and DLNA: pause, seek, volume (also with the phone's volume keys), audio, subtitle
  and video tracks, subtitle delay, turning the computer's screen on.
- **Subtitles:** search by file hash and name via subliminal (OpenSubtitles.com, BSPlayer, Podnapisi,
  Gestdown/Addic7ed, NapiProjekt), saved next to the video as `<video>.<language>.srt`, enabled immediately if the
  video is playing in mpv.
- **Sleep and wake** buttons in the app. The computer won't fall asleep while something downloads or plays.
- **Web client** for laptops and tablets at `http://<computer>:8765/`: torrents, library, remote (keyboard too),
  subtitles, playback in the browser, Chromecast.
- **Several computers:** file servers and screens can be different machines; a video from one computer can play on
  another's screen.
- **More people:**
  - pair another phone or browser with a QR code or a link; household members get full access, guests can only
    watch (for a day, a week or without a limit);
  - an unpaired device on the home network can request access, and a household member's phone gets a notification
    with “Guest (1 day)” / “Household member” / “Decline”;
  - “Share app” sends the APK via WhatsApp etc.; updates published on the computer are offered by the apps.
- **Install on another computer** from the app: the phone shows a one-line command (`curl … | bash` on Linux,
  `irm … | iex` in PowerShell on Windows). The agent comes from an existing server or from the phone itself, and the
  new computer pairs with the phone automatically.

## Quick start

1. Install the app: the APK is in the [GitHub releases](https://github.com/treetank-net/magneticplayer/releases).
2. Install the agent on the computer, from source:

   ```bash
   bash server/install.sh            # Linux: full install (Deluge, Wake-on-LAN, sleep), root parts via pkexec
   LITE=1 bash server/install.sh     # Linux: lite install, no root (library, screen, subtitles; no torrents)
   ```

   On Windows 10/11 (lite install), in PowerShell: `powershell -ExecutionPolicy Bypass -File server\install.ps1`.

   Or, once the app is installed: Settings → “Install on another computer” → “This phone”, and paste the command it
   shows on the computer.
3. Pair: `install.sh` prints a `magneticplayer://pair?...` link; open it on the phone or paste it in the app
   (Settings → “Add server” → “Paste pairing link”).

Building the app yourself: `phone/tools/build-apk.sh` (Docker with the Android SDK image; signs with a local
development key) and `adb install -r phone/out/MagneticPlayer.apk`.

## Security model

Designed for a trusted home network: plain HTTP on the LAN, a per-device bearer token after pairing, guests limited
to an allow-list of endpoints, access requests only from private addresses. Don't expose the agent's port to the
internet. Details (in Polish): [docs/siec-i-bezpieczenstwo.md](docs/siec-i-bezpieczenstwo.md).

## Documentation

The documentation is in Polish for now:

- [docs/instalacja-i-utrzymanie.md](docs/instalacja-i-utrzymanie.md): installation (also via SSH, from another server,
  from the phone, Windows), updates, releases, signing keys, logs, troubleshooting.
- [docs/architektura.md](docs/architektura.md): components, flows (magnet, playback, subtitles, sleep), files and
  configuration.
- [docs/api.md](docs/api.md): the agent's HTTP API.
- [docs/siec-i-bezpieczenstwo.md](docs/siec-i-bezpieczenstwo.md): the LAN-only assumption, tokens, roles.
- [docs/urzadzenia.md](docs/urzadzenia.md): Chromecast, DLNA and Samsung TVs: discovery and codec profiles.

## Releases

Tagging `v1.YYMMDD.HHMM` on `main` (`phone/tools/tag-release.sh`) makes GitLab CI build the APK, sign it, package the
agent with a signed manifest and publish a GitHub release. The app uses a newer signed agent package from the latest
release when it installs the agent on another computer. Source: [gitlab.com/treetank/magneticplayer](https://gitlab.com/treetank/magneticplayer),
mirrored to [github.com/treetank-net/magneticplayer](https://github.com/treetank-net/magneticplayer).

## License

MIT, see [LICENSE](LICENSE). The project ships no content: what you download and share over BitTorrent is your
responsibility.
