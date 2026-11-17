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
| `mp_devices.py` | sparowane urządzenia (tokeny jako SHA-256), kody parowania, bilety HMAC na jeden plik; `devices.json` czytany ponownie po zmianie mtime (CLI to osobny proces) |
| `mp_platform.py` | wszystko zależne od systemu: sesja graficzna, odblokowanie, usypianie, karta sieciowa, ocena budzenia, wysyłanie WoL. Inny system dopisuje się tutaj; czego nie umie, tego agent nie zgłasza w `features`/`power` |

### Wiele serwerów

Plan i decyzje: [plan-wiele-serwerow.md](plan-wiele-serwerow.md), makiety: `ux-wiele-serwerow.html`.
- Serwer ma `id` (losowe, w `config.json`) i `name`. Apka trzyma listę serwerów, a przy każdym zapytaniu `ping`
  sprawdza `id`, więc nie wyśle tokenu do innego komputera, który dostał stary adres.
- Role: serwer plików (`features.torrent`/`library`) i ekran (`features.player`). Jeden komputer może mieć obie.
- Film z serwera A na ekranie B: apka bierze z A bilet (`/api/media/ticket`) i wysyła do B `player/open` z URL.
  mpv na B ciągnie film i napisy przez HTTP. Pozycję takiego filmu zapisuje Pilot w apce.

### Chromecast i telefon jako źródło

- **Chromecast** (Google Cast, odbiornik domyślny Google'a, bez własnej aplikacji odbiornika): apka wykrywa urządzenia
  przez MediaRouter (`Cast.kt`), pokazuje je w „Gdzie odtworzyć?” obok ekranów mpv i wysyła URL z biletu
  z napisami jako ścieżkami WebVTT (`/sub?…&vtt=1`, CORS na agencie). MKV idzie jako `video/mp4`, a odbiornik
  rozpoznaje kontener po zawartości. To, co zagra, zależy od kodeków Chromecasta (H.264/HEVC, AAC; z AC3/DTS bywa
  różnie). Sterowanie: Pilot (patrz niżej), powiadomienie i pełny ekran z Cast SDK (`CastControlsActivity`).
  Przyciski głośności telefonu zmieniają głośność Chromecasta. Chromecast ze stałą głośnością (np. sterowaną przez
  telewizor po HDMI-CEC, `volumeHandling == FIXED`) pokazuje podpowiedź zamiast przycisków. Pozycję zapisuje apka co 5 s.
- **Telefon jako serwer plików** (`PhoneShare.kt`): gdy serwer z filmem nie odpowiada (np. u kogoś w gościach),
  a cały film jest na telefonie („Pobierz na telefon”), apka wystawia go po HTTP w Wi-Fi z losowym sekretem
  w ścieżce: Range, CORS, napisy w oryginale (dla mpv) i jako WebVTT (dla Chromecasta). Czyta z MediaCache.
  Działa w usłudze pierwszoplanowej `ShareService` (powiadomienie z „Zatrzymaj”, WifiLock + WakeLock).
  Kończy się po 20 min bez zapytań albo z końcem sesji Chromecasta. Ten sam adres dostaje też ekran mpv,
  który nie ma pliku (`player/open` z URL).
- Wybór źródła (`Screens.source`): serwer odpowiada albo telefon nie ma całego filmu → bilet z serwera (z budzeniem);
  inaczej telefon.
- **Wersja dla Chromecasta** (`server/mp_media.py`): `ffprobe` ocenia plik (ok / może / nie), a „Przygotuj” robi
  `.mp-cast/<nazwa>.cast.mp4`. Obraz H.264 jest kopiowany, inny kodowany najlepszym działającym koderem sprzętowym,
  wykrytym próbnym kodowaniem przy starcie (NVENC, VideoToolbox, VAAPI, QSV, V4L2, a na końcu x264). Dźwięk
  idzie do AAC. Bilet z `variant: "cast"` wskazuje tę wersję. Kasuje się razem z torrentem („usuń z danymi”).
- **Telewizory DLNA** (`Dlna.kt`): SSDP M-SEARCH (MediaRenderer), potem SOAP AVTransport: SetAVTransportURI z DIDL,
  Play, Seek, Pause, Stop. Głośność przez RenderingControl. Napisy dla Samsunga: `sec:CaptionInfoEx` w DIDL plus
  nagłówek `CaptionInfo.sec` w odpowiedzi z filmem (agent przy `&cap=`, telefon sam). Telewizor dostaje oryginał,
  bez konwersji.
  - Samsung przewija tylko wtedy, gdy `protocolInfo` w DIDL i nagłówek `contentFeatures.dlna.org` mają `DLNA.ORG_OP=01`
    (inaczej daje tylko Pause/Stop i odpowiada 501 na Seek). MKV musi mieć typ `video/x-mkv`. Agent i telefon
    rozpoznają klienta DLNA po nagłówku `getcontentFeatures.dlna.org` albo „DLNA” w User-Agent.
  - Przy pierwszym połączeniu z nowego adresu (telefon, PC) Samsung pyta na ekranie o zgodę. Do tego czasu stoi
    w TRANSITIONING.
- **Pilot** (`Remote.kt`, `MainActivity.PilotTab`): jedna warstwa `Remote` z trzema wariantami (`MpvRemote`,
  `CastRemote`, `DlnaRemote`) i jeden układ przycisków. Na górze żetony celów: ekran (▾ wybiera inny), Chromecast
  i telewizor, gdy coś na nich gra. Wysłanie filmu przełącza Pilota na ten cel (`Pilot.started`). Czego cel nie umie,
  tego nie widać: ścieżki, napisy i pełny ekran są tylko dla mpv, a głośność nie pojawia się przy stałej głośności.
  Przyciski głośności telefonu sterują celem Pilota (mpv tylko na zakładce Pilot). Gdy coś gra, Pilot nie wygasza
  ekranu (ustawienie „Pilot nie wygasza ekranu”, domyślnie włączone).
  - DLNA po ubiciu apki: co wysłano, leży w SharedPreferences `dlna`. Przy starcie apka pyta telewizor
    (GetTransportInfo, GetMediaInfo) i jeśli gra dalej ten sam adres, Pilot i powiadomienie wracają.
  - Start DLNA od zapamiętanej pozycji: Seek po wejściu w PLAYING, sprawdzenie pozycji i do 4 prób
    (tuż po starcie Samsung potrafi Seek zignorować). Log `MpDlna`.
  - Chromecast z apki (Cast SDK w telefonie) i ze strony (pychromecast w agencie) to osobni nadawcy: Pilot w apce
    nie widzi filmu puszczonego ze strony i odwrotnie.
- **Powiadomienie i głośność poza apką** (`RemoteSession.kt`): mpv i DLNA mają sesję media3 (`RemotePlayer` na
  `SimpleBasePlayer`, DeviceInfo REMOTE 0–100) w `RemoteSessionService`. Sesja jest dodana do usługi od razu
  (`addSession`), bo bez tego powiadomienie pojawia się dopiero po podłączeniu kontrolera. Usługa kończy się po ~10 s
  bez niczego grającego. Chromecast ma to samo z Cast SDK.
- **Chromecast z przeglądarki**: klient webowy nie ma Cast SDK (wymaga HTTPS), więc steruje agent (`mp_cast.py`,
  pychromecast w venv), a film idzie z agenta pod adresem LAN.

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
- **Telefon:** ExoPlayer strumieniuje `/media/<torrent>/<plik>` albo `/lib/<katalog>/<ścieżka>` (Range). W pamięci
  ExoPlayer trzyma domyślne ~50 s. Dalszy zapas jest na dysku telefonu (`MediaCache.kt`, `SimpleCache` w cacheDir):
  `PlayerActivity.prefetch` dociąga N minut od bieżącej pozycji (Ustawienia, 1–999, domyślnie 5; 999 = cały film na drogę), ale tylko do
  `X-Ready-End` z agenta, więc tylko pobrane bajty. Odtwarzacz z bufora tylko czyta, nie zapisuje.
  - Sprzątanie: koniec filmu albo wyjście z odtwarzacza po ≥80% kasuje plik od razu. Przy starcie apki i odtwarzacza
    `MediaCache.sweep` usuwa obejrzane ≥80% (np. gdy apkę ubito) i nieoglądane od 48 h. Limit LRU: 60% wolnego
    miejsca, 1–64 GB (`MediaCache.maxBytes`).
    Zablokowanie ekranu niczego nie kasuje.
  - Zapas na dysku widać na pasku przewijania jako niebieski odcinek (`DiskAheadOverlay`, nakładka na `DefaultTimeBar`);
    szary od ExoPlayera to bufor w pamięci.
  - Agent dla torrentów oddaje tylko pobrane kawałki (`ready_end`). Gdy dojdzie do niepobranego, czeka do 20 s na
    Deluge, potem zrywa połączenie, a ExoPlayer ponawia (do 60 razy). Zer z niepobranego pliku nikt nie dostaje.
  - Napisy obok filmu dochodzą jako `SubtitleConfiguration`. Apka pobiera je z `/sub` na telefon (cacheDir/subs) przy
    otwarciu filmu, bo media3 czyta plik napisów od nowa przy przewijaniu i przełączaniu ścieżek. Z sieci, bez Wi-Fi,
    blokowałoby to dociąganie filmu z bufora na dysku i obraz by się ciął.
  - Dekoder FFmpeg (Jellyfin media3-ffmpeg) obsługuje dźwięk AC3, E-AC3 i DTS.
  - „Na komputer” przekazuje bieżącą pozycję.
- **Na telefonie (offline):** „Pobierz na telefon” w menu ⋮ filmu → `DownloadService` (usługa pierwszoplanowa
  `dataSync`, blokada Wi-Fi i CPU) pobiera cały plik do tego samego `MediaCache`, tylko do `X-Ready-End` (przy torrencie
  w trakcie czeka na komputer, po ~5 min bez połączenia „Przerwane”). Napisy zapisuje do `filesDir/offline/<sha1>/`.
  - Lista w zakładce „Na telefonie” (`OfflineTab.kt`, `OfflineStore` w `Offline.kt`): pobrane i bufor z oglądania,
    odtwarzanie bez sieci, wznawianie, usuwanie.
  - Pobrane (`pinned`) kasują się wg zasady wybranej przy filmie: po obejrzeniu (domyślnie), po 2/7/30 dniach od
    pobrania albo zmiany zasady, albo tylko ręcznie. `PinnedAwareLruEvictor` nie wyrzuca ich przy braku miejsca.
  - Długość pliku trafia do metadanych cache (`MediaCache.setLength`), żeby odtwarzacz bez sieci znał koniec pliku.
  - Wersja webowa tego nie ma: gra prosto z PC; oglądanie spoza domu to osobny temat (Tailscale/WireGuard).
- **VLC:** ten sam URL plus extra `subtitles_location`.
- **Komputer:** `POST /api/player/open` uruchamia mpv w sesji graficznej. Środowisko (`DISPLAY`, `WAYLAND_DISPLAY`,
  DBus) agent bierze z `/proc/<cinnamon-session>/environ`. Pilot steruje mpv przez JSON IPC. mpv startuje przez
  `systemd-run --user --scope`, więc restart albo aktualizacja agenta nie zamyka filmu. Nowy agent rozpoznaje
  działające mpv po gnieździe IPC. Napisy z podkatalogu (`Subs/…`) agent dokłada sam (`sub-add`), bo mpv szuka
  tylko obok filmu. Telefon podaje pozycję startu z własnej pamięci, bo film mógł być ostatnio oglądany gdzie indziej.
- **Ekran blokady:** przed odtworzeniem agent budzi monitor (`xset dpms force on`) i, gdy `auto_unlock` jest włączone
  (domyślnie), woła `loginctl unlock-session <sesja Display>`. Logind wysyła sygnał Unlock, na który
  cinnamon-screensaver (i GNOME, KDE) zdejmuje blokadę. Polkit pozwala na to właścicielowi sesji bez hasła.
  Nie używamy `cinnamon-screensaver-command --deactivate`, bo w Cinnamon 5.4 wywala wygaszacz i wisi.

### Napisy
`search` wywołuje `scan_video` i `refine` (hash i metadane), potem odpytuje każde źródło w osobnym wątku
(`list_subtitles_provider`) i zapisuje postęp, który apka czyta z `/api/subs/job`.
- **Filtr złych tytułów:** wyniki bez zgodnego tytułu (albo serialu) i bez zgodnego hasha są ukryte. Apka podaje
  tylko ich liczbę. Bez tego OpenSubtitles przy braku trafień oddaje „podobne” filmy, np. dla Der Golem
  podsuwało zupełnie inny film.
- **Druga runda:** gdy nic nie pasuje, a nazwa pliku ma tytuł w `[nawiasie]` (np. `Der Golem [The Golem]`),
  agent szuka jeszcze raz pod tym tytułem. Źródła szukające tylko po hashu (BSPlayer, NapiProjekt) są wtedy pomijane.
- **Ręczna poprawka:** apka może podać `title` i `year`.
- OpenSubtitles jest ograniczone do 3 stron wyników.

Wyniki sortuje po kolejności języków z ustawień, potem po score, a obiekty trzyma w cache przez godzinę. `download` pobiera
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

## Klient webowy (`server/web/index.html`)

Jeden plik bez zależności (vanilla JS), serwowany pod `/` bez tokenu. Dane i tak idą przez API z tokenem.
- Token przychodzi w `#t=`, trafia do `localStorage` i znika z paska adresu. `#tab=` wybiera zakładkę.
- Zakładki: Torrenty, Biblioteka i Pilot (odpytywanie `/api/player/status`, ścieżki, klawisze).
- Okno napisów działa jak w apce (asynchroniczne zlecenie, postęp, „Wystarczy”).
- Odtwarzacz to `<video>` z napisami WebVTT (`/sub?…&vtt=1`).
- DOM buduje helper `put(el, ...dzieci)`, który spłaszcza tablice i pomija `null`. Gołe `replaceChildren` z tablicą
  wypisywało „null[object HTMLDivElement]”.

## Apka (`phone/`)

| plik | co jest w środku |
|---|---|
| `Api.kt` | `Settings` (SharedPreferences), modele, `MediaRef` (z `srv`), `Wol`, klient HTTP jednego serwera, budzenie (też przez inny serwer) |
| `Servers.kt` | lista serwerów, wybrany serwer plików i ekran, migracja starych ustawień (serwer `legacy`), parowanie (`Pairing`) |
| `ServersUi.kt` | przełącznik serwera, panel „śpi”, okno usypiania, wybór ekranu, Ustawienia → Serwery, dodawanie, urządzenia, „Sparuj kolejne” |
| `MainActivity.kt` | zakładki Torrenty, Pilot i Ustawienia, górny pasek (serwer, Obudź/Uśpij), pasek aktualizacji |
| `Remote.kt` | wspólny „zdalny odtwarzacz” (mpv, Chromecast, DLNA) i wybór celu Pilota (`Pilot`) |
| `RemoteSession.kt` | sesja media3 dla mpv i DLNA: powiadomienie, ekran blokady, głośność przyciskami poza apką |
| `Cast.kt`, `Dlna.kt` | Chromecast (Cast SDK, MediaRouter) i telewizory DLNA (SSDP, SOAP) |
| `PhoneShare.kt` | telefon jako serwer plików dla ekranu, Chromecasta i DLNA |
| `LibraryTab.kt` | biblioteka i wybór katalogu na komputerze |
| `MediaUi.kt` | przyciski Telefon, ekran i VLC, okno napisów |
| `PlayerActivity.kt` | odtwarzacz w telefonie |
| `MagnetActivity.kt` | obsługa magnetów |
| `Updater.kt` | aktualizacje z komputera, wysyłanie własnego APK |

Klucze pozycji, bufora i pobranych filmów mają przedrostek serwera (`<key>:`), poza serwerem `legacy` (migracja
z jednego serwera), żeby stare dane zostały.

Kod jest w Kotlinie 2.2 z Compose (BOM 2025.08) i Media3 1.8. Wymaga Androida 8 (minSdk 26), a celuje w SDK 36.
