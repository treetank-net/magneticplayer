# HTTP API agenta

Adres: `http://<komputer>:8765`. Wszystko poza `/api/ping` i `/api/pair` wymaga tokenu urządzenia w nagłówku
`Authorization: Bearer <token>` albo w parametrze `?token=` (to drugie dla odtwarzaczy, które nie ustawiają
nagłówków). Token urządzenia daje parowanie (niżej); stary wspólny `token` z `config.json` działa dalej jako
urządzenie „legacy”, dopóki ktoś go nie wyłączy. Odpowiedzi są w JSON, a błędy mają postać `{"error": "..."}`
z kodem 4xx lub 5xx.

`/media`, `/lib` i `/sub` przyjmują zamiast tokenu `?ticket=` z `POST /api/media/ticket`: podpis na jeden plik,
ważny 12 h. Tak inny serwer (ekran) dostaje film, nie będąc sparowany z serwerem plików. Bilet na API nie działa.

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
| `POST /api/wake` | `{"mac", "ip"?}`: wysyła magic packet z tego serwera (budzenie innego serwera, gdy telefon go nie dosięga) |
| `POST /api/system/suspend` | zamyka mpv i usypia komputer |
| `POST /api/system/screen_on` | budzi ekran komputera, a przy `auto_unlock` zdejmuje też ekran blokady → `{"unlocked"}` |
| `POST /api/system/unlock` | budzi ekran i zdejmuje blokadę niezależnie od `auto_unlock` |
| `GET /api/app/version` | `{"version_code", "version_name"}` opublikowanej apki albo `{"version_code": 0}` |
| `GET /app/MagneticPlayer.apk` | plik opublikowanej apki |

`features`: `torrent` (`"deluge"`, `"transmission"` albo `null`), `library`, `player` (mpv + sesja graficzna),
`subs_search`, `unlock`, `apk`, `wake_others`. `power`: `suspend`, `poweroff`, `wake`
(`{"method": "wol"|null, "mac", "confidence": "maybe"|"no", "reason"}`). `yes` w `confidence` wie tylko apka,
gdy budzenie raz się udało.

## Parowanie i urządzenia

| metoda i ścieżka | opis |
|---|---|
| `POST /api/pair` | bez tokenu: `{"code", "name"}` → `{"token", "device_id"}`. Kod działa raz, 10 min; po 10 złych próbach w minutę odmowa |
| `POST /api/pair/code` | nowy kod dla kolejnego urządzenia → `{"code", "expires_in", "host", "port", "id", "name"}` |
| `GET /api/devices` | `[{id, name, created, last_seen, current, legacy?}]` |
| `POST /api/devices/revoke` | `{"id"}`; `"legacy"` wyłącza stary wspólny token |
| `POST /api/media/ticket` | `ref` → `{"url", "subs": [{name, lang, url}], "title", "expires"}`; adresy z biletem, host z nagłówka `Host` |

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
