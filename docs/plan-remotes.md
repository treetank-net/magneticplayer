# Plan: Remote without the agent, "what's playing" broadcasts, notifications and the media card

User decisions (17.11.2026): a separate notification for each playing screen (A), the correct device name on the
system media card (a, without moving playback from the system list), broadcasting what is being played to other
remotes, and the phone should find Chromecasts on its own, because "at the in-laws'" there will be no agent.

## 1. The phone sees Chromecasts on its own (without the agent) — done

Tested 17.11.2026 on "Duży pokój" (living room): a video started by the laptop's agent; the Remote showed it with the
state taken straight from the Chromecast; pause, +10 s, resume and "Stop" work. Model recognition on the phone
(eureka_info, profiles from the agent) not yet.

- **Discovery:** `NsdManager`, `_googlecast._tcp`. TXT: `id`, `fn`, `md`; address and port from service resolution.
  Runs only while the Remote is on screen (saves battery).
- **State and control:** our own client for the Cast v2 protocol, the same one pychromecast uses.
  - Connection: TLS on `:8009` (self-signed certificate), frames are a 4-byte length and a `CastMessage` protobuf
    (encoded by hand, no library).
  - Channels: `tp.connection` (CONNECT), `tp.heartbeat` (PING/PONG), `receiver` (GET_STATUS: app and volume,
    SET_VOLUME, STOP), `media` (GET_STATUS, PLAY, PAUSE, SEEK).
- **Remote:** a Chromecast chip appears when something is playing on one, regardless of the sender. Order of
  sources:
  1. this phone's Cast SDK session (full Google notification);
  2. our own Cast v2 client;
  3. the agent (kept as a fallback, e.g. when the phone can't get TLS).
- **Who the Chromecast is:** the phone asks `https://<ip>:8443/setup/eureka_info` just like the agent and keeps its
  own registry. It takes the profile table from the agent (`/api/renderers/profiles`) and remembers it, so it also
  works away from home.

## 2. "What's playing" broadcasts (multicast) — done

Tested 17.11.2026:
- an announcement from "another phone" (from the laptop) with a real Samsung: the Remote showed the TV chip, took
  over control and read the state straight from the TV;
- jmpc ↔ laptop in both directions; phone → jmpc works;
- phone → laptop (both on Wi-Fi) doesn't arrive. Laptop → phone over Wi-Fi works, so it's probably the router or the
  laptop's Wi-Fi card (power save), not the app. There was no way to test two phones at once.

A stopped TV (end of the video or "Stop" from another phone) closes the notification after ~10 s, and the app stops
tracking it.

- UDP multicast `239.255.77.77:47700`, TTL 1 (this network only), JSON:

  ```json
  {"mp": 1, "type": "playing", "from": {"kind": "phone", "id": "<random installation id>", "name": "Pixel 8a"},
   "target": {"kind": "dlna|cast|screen", "id": "...", "name": "[TV] UE43J5500", "ctl": {...}},
   "title": "...", "state": "PLAYING|PAUSED|BUFFERING", "pos": 123.4, "dur": 5400, "ts": 1794929824}
  ```

- `ctl` says how to control it without asking the sender:
  - DLNA: the AVTransport and RenderingControl URLs;
  - Chromecast: host and port;
  - screen: the agent's address and the server key, while each remote has its own token.
- The sender sends every 3 s while playing, and once `"type": "stopped"` at the end. A listener forgets an entry
  after 10 s of silence. `"type": "who"` asks everyone for an immediate answer (a freshly opened Remote doesn't wait
  3 s).
- Senders: phones (DLNA and Chromecast started from them) and the agent (mpv and Chromecast started by the agent).
  Listeners: phones (while the Remote is on screen or a notification is playing) and the agent, which passes the
  list to the web page (`GET /api/announce`).
- No secrets in the packet: there is no token, and controlling DLNA and Chromecast on the LAN doesn't require a login
  anyway.
- Android: the `CHANGE_WIFI_MULTICAST_STATE` permission and `WifiManager.MulticastLock` while listening.

## 3. A separate notification for each playing screen (A) — done

Tested 17.11.2026: mpv on jmpc and the "Duży pokój" Chromecast at the same time gave two notifications (2001, 2003),
both disappeared on their own ~10 s after stopping. Android 17 doesn't show a separate card for our Chromecast, only
the system Cast card of that device (titled "Default Media Receiver"); there is still one card per target. Sessions
have ids by kind (`remote-screen`, `remote-dlna`, `remote-cast`), not by device: two DLNA TVs at once are rare.


- `RemoteSessionService` keeps a separate `MediaSession` for each playing target (`id` = kind and device).
  A session is created when the target starts playing (from the Remote's state or from a broadcast) and goes away
  after ~10 s of silence.
- Android gives the volume buttons outside the app to one session, usually the most recently active one.
- Chips on the Remote: only targets where something is playing, plus the default screen labelled "nothing playing".

## 4. Media card with the device name (a) — done

Tested 17.11.2026 on a Pixel (Android 17): the mpv card for jmpc has a "jmpc" chip instead of "This phone", and the
route and session disappear ~10 s after stopping. It turned out simpler than planned: no `transferTo`. The provider
(`RemoteRoutes.kt`) creates the route session itself (`notifySessionCreated(REQUEST_ID_NONE, …)`,
`clientPackageName` = our package). The system logs "Ignoring session creation result for unknown request", but keeps
the session, and System UI links it to our media session by `originalId` = `volumeControlId` (media3:
`DeviceInfo.setRoutingControllerId`). Thanks to this several targets at once each have their own name. The system
binds the provider as long as our MediaRouter2 is looking for routes with the feature
`net.treetank.magneticplayer.REMOTE` (without active scanning), i.e. while something is playing. The icon on the chip
is a speaker: an app can't set the route type (TV, computer). Switching the output from the system panel to our
routes is rejected: the target is chosen in the app.

Original plan:

- The app registers as a route provider (`MediaRoute2ProviderService`) with a route for each playing target. The app
  selects it itself (`MediaRouter2.transferTo`), and the controller id goes into the session
  (`DeviceInfo.setRoutingControllerId`). Then System UI shows the name instead of "This phone".
- First a test on the Pixel (Android 17): whether the system shows our name. If not, document why.

## Order

1. Cast v2 client and NSD on the phone, Remote without the agent.
2. Broadcasting (phone and agent), the Remote shows playback started by others.
3. Separate sessions and notifications.
4. Media card.

Each step: test on hardware, commit and push.
