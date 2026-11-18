# Network and security

## Assumption: LAN only

MagneticPlayer is designed for a home network:
- **HTTP without TLS.** The token and streams travel in plain text, and the app has `usesCleartextTraffic`. On a home
  Wi-Fi this is acceptable, on someone else's network it isn't.
- **A token per device.** Every phone and browser gets its own token when pairing. The agent keeps only its SHA-256
  hash (`~/.config/magneticplayer/devices.json`). All devices have the same rights: adding torrents, controlling
  mpv, suspending and reading files from the library and download folders (only videos, music and subtitles; the
  agent doesn't go outside these folders).
- **Pairing.** A pairing code (6 characters, single-use, 10 minutes) can be created only by an already paired device
  or by someone with access to the computer (`--pair`, and after installation `install.sh`). Just being on the
  network isn't enough. After 10 bad attempts per minute the agent refuses.
- **Lost phone.** From any other device: Settings → server → device → "Revoke". Or on the computer
  `magneticplayer_agent.py --devices` and `--revoke ID`.
- **The old shared token** from `config.json` still works (the "legacy" device), so older apps don't lose access.
  Once all devices have been paired again, disable it ("Disable" in the device list).
- **Tickets.** A screen (mpv on another computer) gets the video URL with an HMAC ticket for one file, valid for 12 h,
  instead of a token. A ticket doesn't work for the API.
- **Screen unlock.** With `auto_unlock: true` (default) anyone with a token can remove the computer's lock screen,
  and then a person sitting at it has an open desktop. To prevent this, set `"auto_unlock": false` in `config.json`
  and restart the service. Only the explicit `POST /api/system/unlock` then remains, which the app doesn't use.
- **The pairing code is in the QR code.** The "Pair another device" QR code works once and expires after 10 minutes,
  but during that time show it only to household members.
- **Firewall.** `install-root.sh` opens port 8765 in ufw only for `192.168.0.0/16` and `10.0.0.0/8`.
- **deluge-web** listens only on `127.0.0.1:8112`, so from the network it is reachable only through the agent.
- **Wake-on-LAN** is a broadcast on the local network, so it won't work from outside it.
- **Magnet recognition (agent 1.5+)** goes out to the internet in two places: once a week it downloads the IMDb
  dumps (datasets.imdbws.com, ~250 MB, leaving an index of ~12 MB in `~/.cache/magneticplayer/imdb/`), and for
  uncertain items it asks Wikidata about the title and year (nothing more, no device identifier). To turn it off:
  `"ident_online": false` in `config.json` (recognition then uses what is already in the index);
  `"ident_refresh_days"` changes the refresh frequency. The user's choices are in `ident-memory.json` next to the
  configuration.

## Guests

A guest gets an invitation from a household member's phone: Settings → "Pair another device" → "Guest" → "Send
invitation" (via a messenger). It contains the page address with the code (`http://<server>:8765/#p=CODE`) and a
link for the app. The code works once, for one day, and the access itself lasts a day, a week or without limit. It
works only on the home network, like everything here. A guest watches and starts videos, but doesn't change anything
on the server (list in `docs/api.md`). A household member revokes access in the server details (device list).

### What is visible in the library

Every library folder has a "Who sees it" setting (Library → pencil next to the folder, agent 1.4+): everyone (guests
too), household members only or selected devices. On top of that, hidden subfolders that nobody sees. The agent
checks this on every file access (list, stream, ticket for a screen and Chromecast, subtitles), not just in the
list. A folder tagged `kopia` (backup) doesn't show in the Library to anyone. By default everyone sees everything, as
before 1.4, and `~/Downloads` is no longer added to the library automatically on first start.

### Access requests

Without an invitation: an unpaired browser on the home network enters a name and taps "Request access" (the page
`http://<server>:8765/`). A household member's phone with the app open checks requests every ~15 s and shows the
notification "X is requesting access" with the buttons "Guest (1 day)", "Member", "Decline". After approval, the
browser gets a regular pairing code with the chosen role and pairs by itself.

Safeguards: requests only from a private address, one per address, at most 5 pending and 10 per minute, expiring
after 5 min. Only whoever holds the secret from the request's response can collect the result, and the code is
handed out once. The name is just a label from the requester: the household member decides whether they recognize
the device. Nothing grants access without approval, and a guest doesn't see requests (`403`). The app doesn't check
requests in the background, so with the app closed a request waits until someone opens it.

### AI assistant (MCP, agent 1.6+)

`/mcp` accepts only a device token. Getting one goes through OAuth 2.1 (dynamic registration, PKCE S256, one-time
codes valid for 2 minutes, redirects only to loopback `http` or `https`), but the actual consent is the usual
access request approved on a household member's phone, with the same limits (LAN only, 5 pending, 10 per minute).
The token is an ordinary device: it is listed and revoked like a phone, and a guest token sees read tools only.
Tools that change the library only prepare plans; moving, shrinking and deleting run after `start_task`, deleting
goes to the trash. The assistant sees file names and folder paths of the folders visible to its device; that's what
goes to its model provider.

Plain HTTP in the LAN: OAuth 2.1 expects HTTPS outside localhost. The flow works with clients that accept an
`http://` issuer on a private address; the fallback is to pair a token by hand and pass it as a header.

## Remote access: options for the future

Don't expose port 8765 on the router. Without TLS and with a single token, that's asking for trouble. Sensible
routes, from the simplest:

1. **Tailscale or another WireGuard mesh** on the computer and the phone. The app works unchanged; in Settings you
   enter the address from the Tailscale network (`100.x.y.z` or MagicDNS). Traffic is encrypted and nothing is
   exposed to the world. A small change in the app: switching between "home / remote" addresses or trying both.
2. **WireGuard on the router** (if it supports it). The phone connects to the home network and everything works as at
   home, WoL included, because the router is on the LAN.
3. **A reverse proxy with TLS** (Caddy, Cloudflare Tunnel) in front of the agent. More configuration, and per-device
   tokens would make sense with it. Not worth it without a clear need.

### Waking remotely

A magic packet won't cross the internet. A sleeping computer doesn't answer over Tailscale either, and on a woken
one the tunnel comes up with a delay. Something that always runs on the home network and sends the packet on behalf
of the phone is needed:
- a router with WoL (OpenWrt: `etherwake`, many routers have it in their admin panel);
- a Raspberry Pi or another 24/7 device on Tailscale with a small WoL relay (to be written: a `/wake` endpoint that
  sends a magic packet to the computer's MAC);
- with option 2 (VPN on the router) the app can send the magic packet itself, as long as the router passes broadcasts
  from the VPN. Often it doesn't.

### Watching remotely

The stream goes in the original quality without transcoding, and 1080p x265 videos are 3–10 Mb/s. Over LTE that's
usually enough, but it depends on the home connection's upload speed.
