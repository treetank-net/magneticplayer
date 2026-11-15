# Architektura

```
 telefon (apka)                         komputer (Linux Mint)
 ┌───────────────────┐   HTTP :8765    ┌───────────────────────────────┐
 │ MainActivity      │ ──────────────▶ │ magneticplayer_agent.py       │
 │ MagnetActivity    │   Bearer token  │  ├─ backend: deluge-web JSON  │──▶ deluged (systemd --user)
 │ PlayerActivity    │ ◀────────────── │  │   (albo Transmission RPC)  │
 │  (ExoPlayer)      │  strumień Range │  ├─ mpv JSON IPC              │──▶ mpv na ekranie
 │ Updater           │                 │  ├─ mp_library (katalogi)     │
 └───────────────────┘                 │  ├─ mp_subs (subliminal/venv) │──▶ OpenSubtitles, BSPlayer…
          │  UDP :9/:7 magic packet    │  ├─ mp_jobs (zlecenia, akcje) │
          └──────────────────────────▶ │  └─ SleepGuard (inhibit)      │──▶ systemd-inhibit / suspend
                 Wake-on-LAN           └───────────────────────────────┘
```

## Agent (`server/`)

Jeden proces w Pythonie (`ThreadingHTTPServer`), poza subliminal nie ma zewnętrznych zależności. Działa jako usługa
`systemd --user` w sesji użytkownika, bo potrzebuje jego ekranu (mpv) i jego katalogów.

| plik | odpowiedzialność |
|---|---|
| `magneticplayer_agent.py` | HTTP API, autoryzacja, strumieniowanie z Range, backendy Deluge i Transmission, sterowanie mpv, `SleepGuard`, liczenie „ile da się obejrzeć”, długość filmów |
| `mp_jobs.py` | historia zleceń z telefonu i akcja po pobraniu (`none` / `stop` / `suspend`) |
| `mp_library.py` | katalogi biblioteki, listowanie wyłącznie plików wideo i audio, napisy leżące obok filmów, ochrona przed wyjściem poza katalog (`safe_join`) |
| `mp_subs.py` | wyszukiwanie i pobieranie napisów przez subliminal (leniwy import z venv) |

### Wskazanie pliku (`ref`)

Endpointy odtwarzania i napisów przyjmują jedną z form:
- `{"torrent_id": "<hash>", "file_index": N}`: plik z torrenta;
- `{"root": N, "path": "katalog/film.mkv"}`: plik z biblioteki;
- `{"playing": true}`: to, co gra teraz w mpv.

Odpowiada za to `App.resolve_ref`, który zawsze zwraca ścieżkę wewnątrz katalogu pobierania albo biblioteki.

## Przepływy

### Magnet → pobieranie
1. `MagnetActivity` (przezroczysta) dostaje `magnet:` z linka albo z „Udostępnij”.
2. `Api.ensureAwake`: najpierw `GET /api/ping`. Gdy komputer nie odpowiada, apka wysyła magic packet
   (`255.255.255.255` i broadcast podsieci, porty 9 i 7) i czeka, aż wstanie.
3. `POST /api/torrents` z opcjami `magnet`, `sequential` i `after`. Agent dodaje torrent do Deluge z opcjami
   `sequential_download` i `prioritize_first_last_pieces` i zapisuje zlecenie w `jobs.json`.
4. Apka pokazuje chipy „Gdy się pobierze” i po chwili sama się zamyka.

### Ile da się obejrzeć
Z `core.get_torrent_status` agent bierze `pieces`, `piece_length` i `files[].offset`. `contiguous_ready` liczy bajty
pobrane ciągiem od początku pliku. Długość filmu podaje mpv (`--term-playing-msg=${=duration}`). Liczy się to
w tle, najwyżej 2 naraz, a wynik trafia do cache. Z tych danych wychodzi `ready_sec`, czyli ile sekund da się
obejrzeć.

### Oglądanie
- **Telefon:** ExoPlayer strumieniuje `/media/<torrent>/<plik>` albo `/lib/<katalog>/<ścieżka>` (Range).
  - Napisy obok filmu dochodzą jako `SubtitleConfiguration` z `/sub`.
  - Dekoder FFmpeg (Jellyfin media3-ffmpeg) obsługuje dźwięk AC3, E-AC3 i DTS.
  - „Na komputer” przekazuje bieżącą pozycję.
- **VLC:** ten sam URL plus extra `subtitles_location`.
- **Komputer:** `POST /api/player/open` uruchamia mpv w sesji graficznej. Środowisko (`DISPLAY`, `WAYLAND_DISPLAY`,
  DBus) agent bierze z `/proc/<cinnamon-session>/environ`. Pilot steruje mpv przez JSON IPC.

### Napisy
`search` wywołuje kolejno `scan_video`, `refine` (hash i metadane) i `ProviderPool.list_subtitles`. Wyniki sortuje
po kolejności języków z ustawień, potem po score, a obiekty trzyma w cache przez godzinę. `download` pobiera
wybrane napisy i zapisuje je obok filmu. Jeśli ten film gra w mpv, dokłada je od razu poleceniem `sub-add`.

OpenSubtitles bez loginu: subliminal wymaga logowania (`@requires_auth`) przed pobraniem, a API pozwala pobierać
z samym kluczem. Dlatego `_oscom_anonymous` woła `download_subtitle.__wrapped__`.

### Usypianie
- `SleepGuard` co 20 s sprawdza, czy coś się pobiera albo gra. Jeśli tak, trzyma
  `systemd-inhibit --what=sleep --mode=block`. Przy okazji wywołuje `jobs.tick`.
- `jobs.tick` przy akcji `stop` wstrzymuje torrent od razu po pobraniu.
- Przy akcji `suspend` czeka 60 s po skończeniu i usypia komputer tylko wtedy, gdy nic się nie pobiera i nic nie
  gra. Każde zlecenie wykonuje raz.
- Przycisk „Uśpij” zamyka mpv i woła `systemctl suspend`. Polkit pozwala na to usłudze w tle.

### Aktualizacje apki
`publish.sh` kopiuje APK i `version.json` do `~/.local/share/magneticplayer/apk/` (najpierw APK, potem wersję).
Apka porównuje wersje i pobiera nowszą prosto do sesji `PackageInstaller`. `UpdateReceiver` uruchamia systemowe
okno potwierdzenia.

## Apka (`phone/`)

| plik | co jest w środku |
|---|---|
| `Api.kt` | `Settings` (SharedPreferences, link parowania), modele, `MediaRef`, `Wol`, klient HTTP |
| `MainActivity.kt` | zakładki Torrenty, Pilot i Ustawienia, górny pasek (Obudź/Uśpij), pasek aktualizacji, QR parowania |
| `LibraryTab.kt` | biblioteka i wybór katalogu na komputerze |
| `MediaUi.kt` | przyciski Telefon, Komputer i VLC, okno napisów |
| `PlayerActivity.kt` | odtwarzacz w telefonie |
| `MagnetActivity.kt` | obsługa magnetów |
| `Updater.kt` | aktualizacje z komputera, wysyłanie własnego APK |

Kod jest w Kotlinie 2.2 z Compose (BOM 2025.08) i Media3 1.8. Wymaga Androida 8 (minSdk 26), a celuje w SDK 36.
