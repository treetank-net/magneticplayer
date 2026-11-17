# Urządzenia: jak je zapytać, kim są i co umieją

Ściąga dla każdego protokołu, którego używamy. Wszystko działa w LAN bez logowania. Przykłady są z naszych urządzeń
(„Duży pokój”, Samsung UE43J5500), sprawdzone 17.11.2026.

## Chromecast / Google Cast

**Wykrywanie:** mDNS `_googlecast._tcp.local.` Robi to pychromecast u agenta, a w apce MediaRouter z Cast SDK.
Rekord TXT:

| klucz | co to | przykład |
|---|---|---|
| `id` | identyfikator (UUID bez kresek; pychromecast i agent podają go z kreskami) | `8e881091cff00c7302cab09f6f834dff` |
| `fn` | nazwa nadana przez użytkownika | `Duży pokój` |
| `md` | model, **mało mówi**: „Chromecast” dla 1.–3. gen. i dla wersji z Google TV | `Chromecast` |
| `ca` | maska możliwości (bit 0 obraz, bit 2 dźwięk, …); kodeków nie podaje | `465413` |
| `rs` | co teraz pokazuje (np. „YouTube”), puste przy bezczynności | |
| `bs`, `cd` | BSSID, identyfikator chmurowy | |

**Kim jest:** `GET https://<ip>:8443/setup/eureka_info?params=name,build_info,device_info` (certyfikat
samopodpisany, bez logowania). Kluczowe pole to `device_info.product_name`, czyli nazwa kodowa sprzętu. Są też
`device_info.model_name`, `build_info.cast_build_revision` i `device_info.capabilities` (`audio_surround_mode_supported`,
`audio_hdr_supported`…). Port `8008` (http) na nowym firmware oddaje już tylko stary, płaski format bez `product_name`,
więc służy jako zapas.

```bash
curl -sk "https://192.168.100.24:8443/setup/eureka_info?params=name,build_info,device_info" | python3 -m json.tool
```

**Co umie:** sam Chromecast nie poda listy kodeków. Odbiornik webowy ma `canDisplayType()`, ale tylko w kodzie
odbiornika, a my używamy domyślnego „Default Media Receiver”. Dlatego profile są wpisane w kod
(`server/mp_renderers.py`, `CAST_PROFILES`) według `product_name`:

| `product_name` | urządzenie | obraz | dźwięk dekoduje sam | źródło |
|---|---|---|---|---|
| `sabrina` | Chromecast z Google TV 4K (2020) | H.264 i HEVC 10-bit do 2160p, VP9 P2 | AAC, MP3, Opus, Vorbis, FLAC, **AC3, E-AC3** | dokumentacja Google; **u nas: MKV H.264 + AC3 5.1 (Assassin's Creed) zagrał** |
| `boreal` | Chromecast z Google TV HD (2022) | H.264, HEVC 10-bit, VP9, **AV1**, wszystko do 1080p | jak wyżej | specyfikacja Google Store |
| `kirkwood` | Google TV Streamer (2024) | H.264, HEVC, VP9, AV1 do 2160p | jak wyżej (+ Dolby Atmos bez passthrough) | dokumentacja Google |
| (model „Chromecast Ultra”) | Chromecast Ultra | H.264 do 1080p, HEVC i VP9 10-bit do 2160p | AAC, MP3, Opus, Vorbis, FLAC; AC3/E-AC3 tylko passthrough do TV | dokumentacja Google |
| inne przy `md` = „Chromecast” | 1.–3. generacja | H.264 do 1080p, VP8 | jak Ultra | dokumentacja Google |
| nieznane | np. telewizor z wbudowanym Chromecastem | ocena ogólna (najostrożniejsza) | | |

Źródła: [Supported Media for Google Cast](https://developers.google.com/cast/docs/media) (tabela kodeków na
urządzenie, passthrough AC3/E-AC3) i [LineageOS: sabrina](https://wiki.lineageos.org/devices/sabrina/) (nazwa
kodowa). Nazw kodowych starszych Chromecastów nie znamy, dlatego rozpoznajemy je po `model_name`. Nowe obserwacje
(co zagrało, a co nie) dopisuj do pola `source` profilu i do tej tabeli.

**Rejestr:** agent przy wykryciu Chromecasta zapisuje go w `~/.config/magneticplayer/renderers.json` (`name`,
`model`, `product`, `profile`, `build`, `host`, `first_seen`, `last_seen`) i pyta o model raz na tydzień albo po
zmianie adresu. Podgląd: `GET /api/renderers`. `GET /api/media/probe` zwraca `cast_devices`, czyli ocenę pliku dla
każdego znanego Chromecasta.

**Co gra teraz (dowolny nadawca):** pychromecast łączy się z kanałem `urn:x-cast:com.google.cast.receiver`
(aplikacja: `display_name`, głośność, `volume_control_type` = `fixed` przy stałej głośności) oraz
`urn:x-cast:com.google.cast.media` (tytuł, stan, pozycja). Działa to też dla YouTube czy Spotify. Patrz
`GET /api/cast/status`.

## DLNA / UPnP (telewizory: Samsung, LG, Sony…)

**Wykrywanie:** SSDP `M-SEARCH` na `239.255.255.250:1900` z `ST: urn:schemas-upnp-org:device:MediaRenderer:1`.
Odpowiedź ma nagłówek `LOCATION` z adresem opisu urządzenia (Samsung: `http://<ip>:9197/dmr`). Robi to apka
(`Dlna.kt`).

**Kim jest:** opis urządzenia (XML) pod `LOCATION`: `friendlyName`, `manufacturer`, `modelName`, `modelNumber`,
`UDN`, `dlna:X_DLNADOC` (np. `DMR-1.50`) i lista usług z `controlURL` (AVTransport, RenderingControl,
ConnectionManager).

```bash
curl -s http://192.168.100.6:9197/dmr
```

**Co umie: odpowiada sam.** `ConnectionManager#GetProtocolInfo` zwraca w `Sink` listę wszystkiego, co telewizor
przyjmie (`http-get:*:<mime>:<parametry DLNA>`). Nasz Samsung podaje 290 pozycji, m.in. `video/x-mkv`,
`video/mp4`, `video/hevc`, `video/webm`, `video/avi`, `audio/vnd.dolby.dd-raw` (AC3).

```bash
curl -s -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"' \
  -d '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"/></s:Body></s:Envelope>' \
  http://192.168.100.6:9197/upnp/control/ConnectionManager1
```

Lista mówi o kontenerach i typach MIME, nie o profilach kodeka (np. HEVC 10-bit), więc to drogowskaz, nie gwarancja.
Pułapki sterowania (zgoda na ekranie dla nowego adresu, `DLNA.ORG_OP=01`, `video/x-mkv`) są w
`docs/instalacja-i-utrzymanie.md`.

**Stan:** `AVTransport#GetTransportInfo` (stan), `GetPositionInfo` (pozycja, czas), `GetMediaInfo` (`CurrentURI`:
po nim apka rozpoznaje „swój” film po restarcie) i `RenderingControl#GetVolume`/`GetMute`.

## Samsung Smart TV (2015+): REST

`GET http://<ip>:8001/api/v2/` bez logowania zwraca `device.modelName` (`UE43J5500`), `model` (`15_HAWKM_2D`, czyli
rocznik i platforma), `resolution` (`1920x1080`), `firmwareVersion`, `wifiMac` i `networkType`. Nie używamy tego
w kodzie, ale przydaje się do rozpoznania rocznika: platforma decyduje o kodekach (seria J z 2015 nie ma np. HEVC
10-bit).

## Ekran serwera (mpv) i konwersja

mpv dekoduje wszystko, co ma ffmpeg, więc nie ma profilu. Co umie serwer, podaje `GET /api/info`: `features.player`,
`features.unlock`, `transcode` (koder: NVENC, VAAPI, programowy) i `power`.

## Telefon (odtwarzanie lokalne)

ExoPlayer korzysta z dekoderów telefonu. Listę podaje `MediaCodecList` (w adb: `adb shell dumpsys media.player`).
AC3/E-AC3 zależą od producenta. Obecnie apka tego nie sprawdza z góry.
