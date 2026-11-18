# Devices: how to query them, who they are and what they can do

A cheat sheet for every protocol we use. Everything works on the LAN without logging in. The examples come from our
devices (the living-room Chromecast "Duży pokój", a Samsung UE43J5500), tested 17.11.2026.

## Chromecast / Google Cast

**Discovery:** mDNS `_googlecast._tcp.local.` pychromecast does this in the agent, and MediaRouter from the Cast SDK
in the app. The TXT record:

| key | what it is | example |
|---|---|---|
| `id` | identifier (UUID without dashes; pychromecast and the agent give it with dashes) | `8e881091cff00c7302cab09f6f834dff` |
| `fn` | the name given by the user | `Duży pokój` |
| `md` | model, **says little**: "Chromecast" for gen. 1–3 and for the Google TV versions | `Chromecast` |
| `ca` | capability mask (bit 0 video, bit 2 audio, …); doesn't list codecs | `465413` |
| `rs` | what it's showing now (e.g. "YouTube"), empty when idle | |
| `bs`, `cd` | BSSID, cloud identifier | |

**Who it is:** `GET https://<ip>:8443/setup/eureka_info?params=name,build_info,device_info` (self-signed
certificate, no login). The key field is `device_info.product_name`, i.e. the hardware code name. There are also
`device_info.model_name`, `build_info.cast_build_revision` and `device_info.capabilities`
(`audio_surround_mode_supported`, `audio_hdr_supported`…). Port `8008` (http) on new firmware returns only the old,
flat format without `product_name`, so it serves as a fallback.

```bash
curl -sk "https://192.168.100.24:8443/setup/eureka_info?params=name,build_info,device_info" | python3 -m json.tool
```

**What it can do:** the Chromecast itself won't list its codecs. A web receiver has `canDisplayType()`, but only in
the receiver's code, and we use the stock "Default Media Receiver". That's why the profiles are hard-coded
(`server/mp_renderers.py`, `CAST_PROFILES`) by `product_name`:

| `product_name` | device | video | audio decoded on-device | source |
|---|---|---|---|---|
| `sabrina` | Chromecast with Google TV 4K (2020) | H.264 and HEVC 10-bit up to 2160p, VP9 P2 | AAC, MP3, Opus, Vorbis, FLAC, **AC3, E-AC3** | Google documentation; **tested by us: MKV H.264 + AC3 5.1 played** |
| `boreal` | Chromecast with Google TV HD (2022) | H.264, HEVC 10-bit, VP9, **AV1**, all up to 1080p | as above | Google Store specs |
| `kirkwood` | Google TV Streamer (2024) | H.264, HEVC, VP9, AV1 up to 2160p | as above (+ Dolby Atmos without passthrough) | Google documentation |
| (model "Chromecast Ultra") | Chromecast Ultra | H.264 up to 1080p, HEVC and VP9 10-bit up to 2160p | AAC, MP3, Opus, Vorbis, FLAC; AC3/E-AC3 passthrough to the TV only | Google documentation |
| others with `md` = "Chromecast" | 1st–3rd generation | H.264 up to 1080p, VP8 | as Ultra | Google documentation |
| unknown | e.g. a TV with built-in Chromecast | overall assessment (the most cautious) | | |

Sources: [Supported Media for Google Cast](https://developers.google.com/cast/docs/media) (codec table per device,
AC3/E-AC3 passthrough) and [LineageOS: sabrina](https://wiki.lineageos.org/devices/sabrina/) (code name). We don't
know the code names of older Chromecasts, so we recognize them by `model_name`. Add new observations (what played
and what didn't) to the profile's `source` field and to this table.

**Registry:** when the agent discovers a Chromecast, it records it in `~/.config/magneticplayer/renderers.json`
(`name`, `model`, `product`, `profile`, `build`, `host`, `first_seen`, `last_seen`) and asks for the model once a
week or after an address change. Preview: `GET /api/renderers`. `GET /api/media/probe` returns `cast_devices`, i.e.
the file assessment for every known Chromecast.

**What's playing now (any sender):** pychromecast connects to the `urn:x-cast:com.google.cast.receiver` channel
(app: `display_name`, volume, `volume_control_type` = `fixed` with fixed volume) and
`urn:x-cast:com.google.cast.media` (title, state, position). This also works for YouTube or Spotify. See
`GET /api/cast/status`.

## DLNA / UPnP (TVs: Samsung, LG, Sony…)

**Discovery:** SSDP `M-SEARCH` to `239.255.255.250:1900` with `ST: urn:schemas-upnp-org:device:MediaRenderer:1`.
The response has a `LOCATION` header with the device description URL (Samsung: `http://<ip>:9197/dmr`). The app
does this (`Dlna.kt`).

**Who it is:** the device description (XML) at `LOCATION`: `friendlyName`, `manufacturer`, `modelName`,
`modelNumber`, `UDN`, `dlna:X_DLNADOC` (e.g. `DMR-1.50`) and a list of services with `controlURL` (AVTransport,
RenderingControl, ConnectionManager).

```bash
curl -s http://192.168.100.6:9197/dmr
```

**What it can do: it tells you itself.** `ConnectionManager#GetProtocolInfo` returns in `Sink` a list of everything
the TV accepts (`http-get:*:<mime>:<DLNA parameters>`). Our Samsung lists 290 entries, including `video/x-mkv`,
`video/mp4`, `video/hevc`, `video/webm`, `video/avi`, `audio/vnd.dolby.dd-raw` (AC3).

```bash
curl -s -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"' \
  -d '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"/></s:Body></s:Envelope>' \
  http://192.168.100.6:9197/upnp/control/ConnectionManager1
```

The list covers containers and MIME types, not codec profiles (e.g. HEVC 10-bit), so it's a signpost, not a
guarantee. Control pitfalls (on-screen permission for a new address, `DLNA.ORG_OP=01`, `video/x-mkv`) are in
`docs/install-and-maintenance.md`.

**State:** `AVTransport#GetTransportInfo` (state), `GetPositionInfo` (position, time), `GetMediaInfo` (`CurrentURI`:
the app uses it to recognize "its" video after a restart) and `RenderingControl#GetVolume`/`GetMute`.

## Samsung Smart TV (2015+): REST

`GET http://<ip>:8001/api/v2/` without a login returns `device.modelName` (`UE43J5500`), `model` (`15_HAWKM_2D`,
i.e. model year and platform), `resolution` (`1920x1080`), `firmwareVersion`, `wifiMac` and `networkType`. We don't
use it in the code, but it helps to identify the model year: the platform determines the codecs (the 2015 J series
lacks e.g. HEVC 10-bit).

## Broadcasts between remotes (ours, multicast)

UDP `239.255.77.77:47700`, TTL 1, JSON (`Announce.kt`, `server/mp_announce.py`):

```json
{"mp": 1, "type": "playing", "from": {"kind": "phone", "id": "…", "name": "Google Pixel 8a"},
 "target": {"kind": "dlna", "id": "uuid:28a4…", "name": "[TV] UE43J5500",
            "ctl": {"avt": "http://192.168.100.6:9197/upnp/control/AVTransport1", "rc": "…/RenderingControl1", "samsung": true}},
 "title": "…", "state": "PLAYING", "pos": 61.0, "dur": 3600, "ts": 1794929824}
```

- `type`: `playing` (every ~3 s), `stopped` (once, at the end), `who` (a request for an immediate answer).
- `target.kind`: `dlna` (announced by the phone, because only it knows what it sent), `screen` (announced by the
  agent: mpv, `ctl.agent` = the agent's address; control still requires a token), `cast` (reserved; everyone sees
  Chromecasts on their own).
- Watching from the console: join the multicast group with a small script, or use `GET /api/announce`.
- The router must pass multicast between Wi-Fi and wired. For us phone → jmpc works, phone → laptop (both on Wi-Fi)
  doesn't.

## Server screen (mpv) and conversion

mpv decodes everything ffmpeg can, so it has no profile. What the server can do is reported by `GET /api/info`:
`features.player`, `features.unlock`, `transcode` (encoder: NVENC, VAAPI, software) and `power`.

## Phone (local playback)

ExoPlayer uses the phone's decoders. `MediaCodecList` lists them (via adb: `adb shell dumpsys media.player`).
AC3/E-AC3 depend on the manufacturer. The app currently doesn't check this in advance.
