# HTTP API agenta

Adres: `http://<komputer>:8765`. Wszystko poza `/api/ping` i `/api/pair` wymaga tokenu urządzenia w nagłówku
`Authorization: Bearer <token>` albo w parametrze `?token=` (to drugie dla odtwarzaczy, które nie ustawiają
nagłówków). Token urządzenia daje parowanie (niżej); stary wspólny `token` z `config.json` działa dalej jako
urządzenie „legacy”, dopóki ktoś go nie wyłączy. Odpowiedzi są w JSON, a błędy mają postać `{"error": "..."}`
z kodem 4xx lub 5xx.

**Język komunikatów.** Teksty dla użytkownika w odpowiedziach (`error`, `reason` w `power.wake`, `problems` oceny
dla Chromecasta, stany źródeł napisów, nazwa profilu Chromecasta) są po angielsku albo po polsku, zależnie od
nagłówka `Accept-Language`: najwyżej oceniony język `pl*` → polski, inaczej (także bez nagłówka) angielski. Apka
wysyła język telefonu, strona `navigator.language`, a przeglądarka i tak dodaje ten nagłówek sama. Zlecenia w tle
(szukanie napisów, konwersja) piszą w języku żądania, które je zaczęło. Klucze JSON i wartości stanów (`state`,
`verdict` itd.) się nie zmieniają. Logi agenta zostają po polsku.

`/media`, `/lib` i `/sub` przyjmują zamiast tokenu `?ticket=` z `POST /api/media/ticket`: podpis na jeden plik,
ważny 12 h. Tak inny serwer (ekran) dostaje film, nie będąc sparowany z serwerem plików. Bilet na API nie działa.

Odpowiedzi `/media`, `/lib` i `/sub` mają `Access-Control-Allow-Origin: *` (plus `OPTIONS` dla preflightu), bo
odbiornik Chromecasta czyta film i napisy z innego „origin”. Bez tego pomija napisy.

`ref` oznacza wskazanie pliku: `torrent_id` + `file_index`, `root` + `path` albo `playing: true`
(zob. [architektura](architektura.md#wskazanie-pliku-ref)). W GET przekazuje się je jako parametry zapytania,
w POST w treści JSON.

## Klient webowy

| metoda i ścieżka | opis |
|---|---|
| `GET /` | strona `web/index.html`, bez tokenu (dane i tak idą przez API z tokenem) |

## System

| metoda i ścieżka | opis |
|---|---|
| `GET /api/ping` | `{"ok": true, "app": "magneticplayer", "version", "id"}`, bez tokenu. `id` pozwala apce sprawdzić, że pod adresem jest ten sam serwer, zanim wyśle token |
| `GET /api/info` | `id`, `name`, `api` (poziom API, teraz 2), `hostname`, `version`, `backend`, `keep_awake` (powód blokady usypiania), `iface`, `ip`, `mac`, `wake_on`, `wireless`, `device` (kto pyta), `features` i `power` (niżej) |
| `POST /api/wake/verified` | apka obudziła ten serwer przez sieć; agent zapisuje to w configu (`wake_verified`) i od tej pory zgłasza budzenie jako `yes` wszystkim urządzeniom |
| `POST /api/wake` | `{"mac", "ip"?}`: wysyła magic packet z tego serwera (budzenie innego serwera, gdy telefon go nie dosięga) |
| `POST /api/system/suspend` | zamyka mpv i usypia komputer |
| `POST /api/system/screen_on` | budzi ekran komputera, a przy `auto_unlock` zdejmuje też ekran blokady → `{"unlocked"}` |
| `POST /api/system/unlock` | budzi ekran i zdejmuje blokadę niezależnie od `auto_unlock` |
| `GET /api/app/version` | `{"version_code", "version_name"}` opublikowanej apki albo `{"version_code": 0}` |
| `GET /app/MagneticPlayer.apk` | plik opublikowanej apki |

`features`: `torrent` (`"deluge"`, `"transmission"` albo `null`), `library`, `player` (mpv + sesja graficzna, chyba że config `player: false`),
`subs_search`, `unlock`, `apk`, `wake_others`, `upload` (przyjmuje filmy z telefonu, niżej), `install` (instaluje agenta na kolejnym komputerze, niżej). `power`: `suspend`, `poweroff` (oba `false` przy config `power: false`), `wake` (`confidence: "no"` przy config `wake: false`, instalacja lekka)
(`{"method": "wol"|null, "mac", "confidence": "maybe"|"no", "reason"}`). `yes` w `confidence` wie tylko apka,
gdy budzenie raz się udało; agent zgłasza `yes` po `POST /api/wake/verified`. Liczniki wybudzeń z
`/sys/class/wakeup` nie pomagają: na płycie jmpc (ASUS H110M-A) każde wybudzenie idzie na konto przycisku zasilania.

## Parowanie i urządzenia

| metoda i ścieżka | opis |
|---|---|
| `POST /api/pair` | bez tokenu: `{"code", "name"}` → `{"token", "device_id", "role", "expires"}`. Kod działa raz, 10 min (kod gościa: dobę); po 10 złych próbach w minutę odmowa |
| `POST /api/pair/code` | nowy kod dla kolejnego urządzenia → `{"code", "expires_in", "host", "port", "id", "name"}`; z `{"role": "guest", "days": N}` kod dla gościa (`days` = ważność dostępu, 0 lub brak = bez limitu) |
| `GET /api/devices` | `[{id, name, created, last_seen, current, role, expires, legacy?}]` |
| `POST /api/devices/revoke` | `{"id"}`; `"legacy"` wyłącza stary wspólny token |
| `POST /api/media/ticket` | `ref` (+ `"variant": "cast"`) → `{"url", "subs": [{name, lang, url, vtt}], "title", "expires", "variant", "content_type"}`; adresy z biletem, host z nagłówka `Host`; `vtt` to napisy po konwersji do WebVTT (dla Chromecasta); z `variant: "cast"` URL wskazuje wersję „dla Chromecasta”, jeśli jest (`&v=cast`, `content_type: video/mp4`) |

**Gość** (`role: "guest"`, agent 1.3+) ma tylko listę dozwolonych endpointów (`GUEST_ALLOWED` w agencie): info,
Biblioteka, lista torrentów i ich plików, pliki filmów i napisów, bilety, format i wersje dla Chromecasta, ekran
(`player/*`, `screen_on`), Chromecast, napisy, budzenie innych serwerów. Na resztę `403` („gość nie ma do tego
dostępu”): dodawanie i akcje torrentów, ustawienia biblioteki i OpenSubtitles, `fs`, usypianie, odblokowanie,
przesyłanie, parowanie i lista urządzeń, instalacja. `/api/info` mówi gościowi `role: "guest"` i ma u niego
`upload`, `install`, `unlock`, `suspend` i `poweroff` na `false`. Po `expires` token przestaje działać (`401`).

`/media` i `/lib` z `&v=cast` dają wersję „dla Chromecasta” (gdy jest), a z `&cap=<napisy>` i nagłówkiem żądania
`getcaptionInfo.sec` odpowiadają nagłówkiem `CaptionInfo.sec` z adresem tych napisów (telewizory Samsung, DLNA).

## Format pliku, wersja dla Chromecasta (agent 1.2+)

| metoda i ścieżka | opis |
|---|---|
| `GET /api/media/probe?ref` | `{"ffmpeg", "file": {container, duration, video, audio[], subs[]}, "cast": {verdict: ok\|maybe\|no, problems[], video_copy, audio_copy}, "complete", "variant": {size, cast} \| null, "job", "encoder": {encoder, label, hw}, "cast_devices": [{id, name, label, verdict, problems}]}`; `cast` to ocena ogólna (najostrożniejsza), `cast_devices` ocena dla każdego Chromecasta o znanym modelu |
| `POST /api/media/convert` | `ref` → stan zlecenia (`state: queued\|running\|done\|error`, `pct`, `speed`, `encoder`, `error`, `finishing`: ffmpeg skończył kodowanie i przenosi indeks na początek pliku, co na wolnym dysku trwa do minuty); `"cancel": true` przerywa, `"remove": true` kasuje wersję; 409, gdy torrent nie jest pobrany w całości |
| `GET /api/media/converts` | wszystkie zlecenia od startu agenta |

Wersja leży obok filmu w `.mp-cast/<nazwa>.cast.mp4`. Obraz H.264 8-bit ≤1080p zostaje, inny idzie przez najlepszy
działający koder: NVENC, VideoToolbox, VAAPI, Quick Sync, V4L2 albo x264, w 1080p. Dźwięk AAC/MP3 zostaje,
inny → AAC stereo. `features.transcode` w `/api/info` to etykieta kodera, a `transcode_hw` mówi, czy sprzętowy.

## Chromecast sterowany przez agenta (klient webowy, Pilot w apce)

Agent słucha wszystkich Chromecastów w sieci, więc status pokazuje też to, co puścił ktoś inny (telefon przez Cast SDK,
YouTube, Spotify). Apka używa tego, gdy sama nie ma sesji Cast.

| metoda i ścieżka | opis |
|---|---|
| `GET /api/cast/devices` | `[{id, name, model, product, profile, label}]` (mDNS przez pychromecast; pierwsze wywołanie czeka do 3 s); `product` = `product_name` z `eureka_info`, `label` = nazwa modelu z profilu (`docs/urzadzenia.md`) |
| `GET /api/announce` | co grają inne piloty w sieci (rozgłoszenia multicast `239.255.77.77:47700` z telefonów i innych agentów, bez wpisów starszych niż 10 s): `[{target: {kind, id, name}, from: {kind, id, name}, title, state, pos, dur}]`; agent sam ogłasza mpv na swoim ekranie (`docs/urzadzenia.md`) |
| `GET /api/renderers` | rejestr Chromecastów widzianych w sieci (`renderers.json`): `id`, `name`, `model`, `product`, `profile`, `build`, `host`, `first_seen`, `last_seen` |
| `POST /api/cast/play` | `ref` + `device` (+ `start`) → odtwarza (wersja dla Chromecasta, jeśli jest; napisy VTT) |
| `GET /api/cast/status` | `{active, device, id, app, mine, title, state, pos, duration, volume, muted, volume_fixed, all}`: pierwszy grający Chromecast (najpierw puszczony przez agenta), `all` = wszystkie grające; `app` = aplikacja na Chromecaście („Default Media Receiver”, „YouTube”…), `mine`: puścił agent; `volume` w procentach, `volume_fixed`: stała głośność (przyciski znikają) |
| `POST /api/cast/cmd` | `{"cmd": "toggle\|play\|pause\|seek\|seek_rel\|volume\|mute\|stop", "value", "device"}`; bez `device` działa na Chromecaście ze statusu; `volume` w procentach 0–100, `seek_rel` w sekundach; `stop` zamyka aplikację na Chromecaście (także cudzą) |

Z konsoli: `magneticplayer_agent.py --pair` (kod + QR w terminalu), `--devices`, `--revoke ID`.

## Torrenty i zlecenia

| metoda i ścieżka | opis |
|---|---|
| `GET /api/torrents` | lista: `id`, `name`, `progress`, `state`, prędkości, `eta`, `size`, `sequential`, `peers`, `mine` (zlecony z apki), `after` |
| `POST /api/torrents` | `{"magnet", "sequential": true, "after": "none\|stop\|suspend"}` → `{"id"}` |
| `GET /api/torrents/<id>/files` | pliki: `index`, `path`, `size`, `progress`, `media`, `ready` (bajty ciągiem od początku), `duration`, `ready_sec` |
| `POST /api/torrents/<id>/action` | `{"action": "pause\|resume\|remove\|remove_data\|sequential_on\|sequential_off"}` (zależnie od backendu) |
| `POST /api/torrents/<id>/after` | `{"after": "none\|stop\|suspend"}` |
| `GET /api/jobs` | historia zleceń: `id`, `name`, `added`, `after`, `after_done`, `magnet`, `in_client`, `progress`, `state` |
| `GET /media/<id>/<index>[/nazwa]` | strumień pliku z torrenta (Range), tylko pobrane kawałki: przy brakującym czeka do 20 s, potem zrywa. Nagłówek `X-Ready-End` (też w `HEAD` i `/lib`): do którego bajtu da się czytać od początku zakresu |

## Biblioteka

| metoda i ścieżka | opis |
|---|---|
| `GET /api/library` | katalogi: `index`, `name`, `path`, `exists` |
| `POST /api/library` | `{"path", "name"?}` dodaje katalog, `{"op": "remove", "index"}` go usuwa; zwraca listę |
| `GET /api/library/browse?root=&path=` | `root`, `path`, `dirs`, `files` (`name`, `size`, `mtime`, `media`, `subs`) |
| `GET /api/fs?path=` | podkatalogi do wyboru nowego katalogu (`parent`, `dirs`) |
| `GET /lib/<root>/<ścieżka>` | strumień pliku z biblioteki (Range) |

## Przesyłanie z telefonu

Wznawialne przyjmowanie pliku (apka: filmy pobrane przeglądarką, [plan-przesylanie.md](plan-przesylanie.md)).
`<id>` wybiera telefon (8–80 znaków `A-Za-z0-9_-`, stały dla pliku). Działa bez Deluge i Transmission.

| metoda i ścieżka | opis |
|---|---|
| `GET /api/upload/<id>` | `{"received", "size", "done", "name"}`; `404` z `received: 0`, gdy serwer nic o nim nie wie |
| `PUT /api/upload/<id>?name=&size=&offset=` | ciało (Content-Length, może być 0 = samo sprawdzenie) dopisywane od `offset` → `{"received", "size", "done", "name"}`. Ostatni kawałek kończy plik, a `name` to wtedy nazwa końcowa (przy kolizji „film (2).mkv”). Błędy: `409` z `received`, gdy `offset` się nie zgadza (i gdy ten sam plik idzie innym połączeniem), `507` przy braku miejsca (zostaje 512 MB zapasu), `400`, gdy kawałek wychodzi poza `size` |

Kawałki trafiają do `<katalog>/.mp-upload/<id>.part` i są zapisywane strumieniowo (nie w pamięci). Przerwane
połączenie zostawia to, co doszło, a po 60 s ciszy agent je zamyka. `name` to sama nazwa pliku: katalogi, znaki
sterujące i kropka na początku są obcinane. Katalog docelowy: `upload_dir` w `config.json`, a bez niego
„Z telefonu” w pierwszym istniejącym katalogu biblioteki. Bez obu `features.upload` jest `false`.

## Prośba o dostęp (agent 1.3+, `features.access_requests`)

| metoda i ścieżka | opis |
|---|---|
| `POST /api/access/request` | bez tokenu, tylko z adresu prywatnego: `{"name"}` → `{"id", "secret", "expires_in"}` (300 s); kolejna prośba z tego adresu zastępuje poprzednią; `429` przy więcej niż 5 oczekujących albo 10 na minutę |
| `GET /api/access/request/<id>?secret=` | bez tokenu: `{"state": pending\|approved\|delivered\|denied, "expires_in"}`; przy `approved` raz `"code"` (kod parowania dla `POST /api/pair`) i `"role"`; `404` po wygaśnięciu albo przy złym sekrecie |
| `GET /api/access/requests` | domownik: `{"requests": [{id, name, ip, age, expires_in}]}` |
| `POST /api/access/requests/<id>` | domownik: `{"action": "approve"\|"deny", "role": "guest"\|"owner", "days"}` (gość: `days` ogranicza dostęp jak przy zaproszeniu); `409`, gdy już rozpatrzona albo wygasła |

Prośby są w pamięci agenta (`mp_access.py`), restart je kasuje.

## Instalacja na kolejnym komputerze (agent 1.3+)

| metoda i ścieżka | opis |
|---|---|
| `POST /api/install/code` | jednorazowy kod dołączenia (30 min) → `{"code", "expires_in", "url", "command", "command_full", "command_windows"}`; `command` to `curl -fsSL <url> \| bash` (instalacja lekka), `command_full` z `?full=1` (pełna, z hasłem administratora), `command_windows` to `irm <url>/ps \| iex` (PowerShell, lekka); `409`, gdy obok agenta nie ma skryptów instalacyjnych |
| `GET /api/install/status?code=` | tylko dla urządzenia, które utworzyło kod: `{"state": waiting\|downloading\|joined, "server": {host, port, id, name, pair_code, version} \| null, "downloads", "expires_in"}` |
| `GET /i/<kod>[?full=1]` | bez tokenu: skrypt bash, który pobiera paczkę i uruchamia `install.sh` z `JOIN=<url>` |
| `GET /i/<kod>/ps` | bez tokenu: skrypt PowerShell, który pobiera paczkę (`tar` z Windows 10+) i uruchamia `install.ps1` z `MP_JOIN=<url>`; `409`, gdy serwer nie ma `install.ps1` |
| `GET /i/<kod>/bundle.tar.gz` | bez tokenu: agent z tego serwera (`server/*.py`, `web/`, `install*.sh`, `uninstall.sh`, `install.ps1`, `uninstall.ps1`; bez venv i APK) |
| `POST /api/install/join` | bez tokenu, od nowego agenta (`--join`): `{"code", "id", "name", "port", "pair_code", "version"}`; adres bierze z połączenia. Zużywa kod |

Kody są w pamięci agenta (restart je kasuje). Zły, zużyty albo przeterminowany kod daje `403`, a po 10 złych
próbach w minutę `429`. Po zgłoszeniu kod żyje jeszcze co najmniej 10 min (tyle ważny jest kod parowania), żeby
telefon zdążył go odebrać.

## Odtwarzacz na komputerze (mpv)

| metoda i ścieżka | opis |
|---|---|
| `POST /api/player/open` | `ref` + opcjonalnie `start` (sekundy); albo film z innego serwera: `{"url", "subs": [url…], "title", "start"?}` |
| `GET /api/player/status` | `running`, `title`, `path`, `pos`, `duration`, `paused`, `volume`, `muted`, `fullscreen`, `sub`, `audio`, `tracks[]` (`id`, `type`, `title`, `lang`, `codec`, `selected`, `external`, `default`, `forced`, `demux-channel-count`, `demux-w`, `demux-h`) |
| `POST /api/player/cmd` | `{"cmd", "value"?}`, polecenia poniżej |

Polecenia:
- odtwarzanie: `toggle_pause`, `pause`, `play`, `seek` (s, względnie), `seek_to` (s), `stop`;
- obraz i dźwięk: `fullscreen` (bez `value` przełącza), `volume`, `volume_rel`, `mute`;
- ścieżki: `track` (`{"type": "video|audio|sub", "id": N|"no"}`), `sub_cycle`, `audio_cycle`, `sub_delay` (s).

## Napisy

| metoda i ścieżka | opis |
|---|---|
| `GET /api/subs/list?<ref>` | napisy leżące obok filmu: `name`, `lang`, `ext` |
| `GET /sub?<ref>&name=[&vtt=1]` | plik napisów. Z `vtt=1` agent zamienia SRT na WebVTT (dla `<track>` w przeglądarce) |
| `POST /api/subs/search` | `ref` + `languages: "en,pl"`, opcjonalnie `title` i `year` (ręczna poprawka) → `query`, `title`, `year`, `providers`, `results[]` (`id`, `provider`, `lang`, `release`, `score`, `hash_match`, `hi`, `title_match`), `hidden`. Z `"async": true` zwraca od razu `{"job"}` |
| `POST /api/subs/job/stop` | `{"id"}`: „Wystarczy”, nie czekaj na wolne źródła i pomiń drugą rundę |
| `GET /api/subs/job?id=` | postęp: `phase` (`file`/`search`/`match`/`done`), `round` (2 = tytuł z `[nawiasu]`), `query`, `matched`/`total`, `providers` (`state`: `pending`/`running`/`done`/`skip`/`error`, `count`, `error`, `secs`), `elapsed`, `done`, `result` albo `error` |
| `POST /api/subs/download` | `ref` + `id`, `provider`, `languages` (i te same `title`/`year` co przy szukaniu) → `{"name", "provider"}`. Jeśli film gra w mpv, napisy włączają się od razu |
| `GET /api/config/opensubtitles` | `api_key` (czy ustawiony), `username`, `password` (czy ustawione) |
| `POST /api/config/opensubtitles` | `{"api_key"?, "username"?, "password"?}`; puste pola nie nadpisują zapisanych |
