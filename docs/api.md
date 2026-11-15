# HTTP API agenta

Adres: `http://<komputer>:8765`. Wszystko poza `/api/ping` wymaga tokenu w nagłówku `Authorization: Bearer <token>`
albo w parametrze `?token=` (to drugie dla odtwarzaczy, które nie ustawiają nagłówków). Odpowiedzi są w JSON,
a błędy mają postać `{"error": "..."}` z kodem 4xx lub 5xx.

`ref` oznacza wskazanie pliku: `torrent_id` + `file_index`, `root` + `path` albo `playing: true`
(zob. [architektura](architektura.md#wskazanie-pliku-ref)). W GET przekazuje się je jako parametry zapytania,
w POST w treści JSON.

## System

| metoda i ścieżka | opis |
|---|---|
| `GET /api/ping` | `{"ok": true, "app": "magneticplayer", "version"}`, bez tokenu |
| `GET /api/info` | `hostname`, `version`, `backend`, `keep_awake` (powód blokady usypiania), `iface`, `ip`, `mac`, `wake_on`, `wireless` |
| `POST /api/system/suspend` | zamyka mpv i usypia komputer |
| `POST /api/system/screen_on` | budzi ekran komputera |
| `GET /api/app/version` | `{"version_code", "version_name"}` opublikowanej apki albo `{"version_code": 0}` |
| `GET /app/MagneticPlayer.apk` | plik opublikowanej apki |

## Torrenty i zlecenia

| metoda i ścieżka | opis |
|---|---|
| `GET /api/torrents` | lista: `id`, `name`, `progress`, `state`, prędkości, `eta`, `size`, `sequential`, `peers`, `mine` (zlecony z apki), `after` |
| `POST /api/torrents` | `{"magnet", "sequential": true, "after": "none\|stop\|suspend"}` → `{"id"}` |
| `GET /api/torrents/<id>/files` | pliki: `index`, `path`, `size`, `progress`, `media`, `ready` (bajty ciągiem od początku), `duration`, `ready_sec` |
| `POST /api/torrents/<id>/action` | `{"action": "pause\|resume\|remove\|remove_data\|sequential_on\|sequential_off"}` (zależnie od backendu) |
| `POST /api/torrents/<id>/after` | `{"after": "none\|stop\|suspend"}` |
| `GET /api/jobs` | historia zleceń: `id`, `name`, `added`, `after`, `after_done`, `magnet`, `in_client`, `progress`, `state` |
| `GET /media/<id>/<index>[/nazwa]` | strumień pliku z torrenta (Range) |

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
| `POST /api/player/open` | `ref` + opcjonalnie `start` (sekundy) |
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
| `GET /sub?<ref>&name=` | plik napisów |
| `POST /api/subs/search` | `ref` + `languages: "en,pl"` → `query`, `providers`, `results[]` (`id`, `provider`, `lang`, `release`, `score`, `hash_match`, `hi`) |
| `POST /api/subs/download` | `ref` + `id`, `provider`, `languages` → `{"name", "provider"}`. Jeśli film gra w mpv, napisy włączają się od razu |
| `GET /api/config/opensubtitles` | `api_key` (czy ustawiony), `username`, `password` (czy ustawione) |
| `POST /api/config/opensubtitles` | `{"api_key"?, "username"?, "password"?}`; puste pola nie nadpisują zapisanych |
