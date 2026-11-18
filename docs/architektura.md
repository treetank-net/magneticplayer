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
`systemd --user` w sesji użytkownika, bo potrzebuje jego ekranu (mpv) i jego katalogów. Na Windows (instalacja lekka, `install.ps1`) zamiast
usługi jest autostart `HKCU\…\Run` przy logowaniu, a mpv słucha na nazwanym potoku zamiast gniazda unix.

| plik | odpowiedzialność |
|---|---|
| `magneticplayer_agent.py` | HTTP API, autoryzacja, strumieniowanie z Range, backendy Deluge i Transmission, sterowanie mpv, `SleepGuard`, liczenie „ile da się obejrzeć”, długość filmów |
| `mp_jobs.py` | historia zleceń z telefonu i akcja po pobraniu (`none` / `stop` / `suspend`) |
| `mp_library.py` | katalogi biblioteki, listowanie wyłącznie plików wideo i audio, napisy leżące obok filmów, ochrona przed wyjściem poza katalog (`safe_join`) |
| `mp_subs.py` | wyszukiwanie i pobieranie napisów przez subliminal (leniwy import z venv) |
| `mp_devices.py` | sparowane urządzenia (tokeny jako SHA-256), kody parowania, bilety HMAC na jeden plik; `devices.json` czytany ponownie po zmianie mtime (CLI to osobny proces) |
| `mp_cast.py` | Chromecast przez pychromecast: wykrywanie, nasłuch stanu wszystkich urządzeń (dowolny nadawca), odtwarzanie i sterowanie dla strony i Pilota |
| `mp_announce.py` | rozgłaszanie „co gra” między pilotami (UDP multicast `239.255.77.77:47700`): nasłuch dla strony (`/api/announce`), ogłaszanie mpv tego serwera, odpowiedź na „who” |
| `mp_renderers.py` | kim są Chromecasty (`product_name` z `eureka_info`), profile kodeków na model (`CAST_PROFILES`) i rejestr `renderers.json`; opis w `docs/urzadzenia.md` |
| `mp_access.py` | prośby o dostęp z sieci domowej: prośba bez tokenu, lista i decyzja domownika, wydanie kodu parowania proszącemu |
| `mp_install.py` | instalacja agenta na kolejnym komputerze: kod dołączenia, skrypt `/i/<kod>` (szablony `join.sh`, `join.ps1`, wspólne z telefonem), paczka, zgłoszenie nowego serwera z kodem parowania dla telefonu |
| `mp_upload.py` | przyjmowanie filmów z telefonu (`/api/upload/<id>`): wznawialne kawałki w `.mp-upload/`, wolne miejsce, kolizje nazw, katalog `upload_dir` albo „Z telefonu” w bibliotece |
| `mp_i18n.py` | język komunikatów dla użytkownika: `T("English", "Polski")` według `Accept-Language` bieżącego żądania (wartość na wątek, ustawiana na początku `dispatch`); `bind(fn)` przenosi język do wątków tła; CLI (`--pair`, `--join`) bierze `LC_ALL`/`LC_MESSAGES`/`LANG`. Skrypty instalacyjne mają własne `say`/`Say` (pl przy polskim locale / języku interfejsu Windows) |
| `mp_platform.py` | wszystko zależne od systemu: sesja graficzna, odblokowanie, usypianie, karta sieciowa, ocena budzenia, wysyłanie WoL, ścieżka do mpv (na Windows `mpv\mpv.exe` obok agenta). Inny system dopisuje się tutaj; czego nie umie, tego agent nie zgłasza w `features`/`power` |

### Wiele serwerów

Plan i decyzje: [plan-wiele-serwerow.md](plan-wiele-serwerow.md), makiety: `ux-wiele-serwerow.html`.
- Serwer ma `id` (losowe, w `config.json`) i `name`. Apka trzyma listę serwerów, a przy każdym zapytaniu `ping`
  sprawdza `id`, więc nie wyśle tokenu do innego komputera, który dostał stary adres.
- „Śpi” w nagłówku apki: dopiero po dwóch nieudanych `ping` z rzędu (druga próba po 1 s), także zaraz po otwarciu
  apki, kiedy do tego czasu widać „…”. Na połączenie apka czeka 3 s (uśpiony komputer nie odpowiada wcale; po
  dłuższej przerwie Wi-Fi telefonu bywa wolne), na odpowiedź 5 s. Agent nie robi `stat()` przez `DirEntry` z `os.scandir`, bo w Pythonie 3.10
  ten nie zwalnia GIL: budzący się stary dysk USB zatrzymywał na kilka sekund cały agent, łącznie z `/api/ping`.
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
  i telewizor, gdy coś na nich gra. Bezczynne Chromecasty i telewizory DLNA (SSDP co 30 s) wykryte z telefonu też mają żeton, z podpowiedzią, że film
  wysyła się z Biblioteki („Na ekran”). Wysłanie filmu przełącza Pilota na ten cel (`Pilot.started`). Czego cel nie umie,
  tego nie widać: ścieżki, napisy i pełny ekran są tylko dla mpv, a głośność nie pojawia się przy stałej głośności.
  Przyciski głośności telefonu sterują celem Pilota (mpv tylko na zakładce Pilot). Gdy coś gra, Pilot nie wygasza
  ekranu (ustawienie „Pilot nie wygasza ekranu”, domyślnie włączone).
  „Zakończ” przy cudzej aplikacji na Chromecaście (Spotify, YouTube) zamyka ją, więc najpierw pyta: w apce oknem,
  w powiadomieniu drugim dotknięciem w ciągu 5 s (ustawienie „Pytaj przed zamknięciem innych aplikacji na
  Chromecaście”, domyślnie włączone). Pilot pokazuje też Chromecasty z otwartą aplikacją, która nic nie gra (np. Spotify
  zostawione po słuchaniu dalej pokazuje okładkę), z przyciskiem „Zamknij” (`CastNet.openIdle`).
  - DLNA po ubiciu apki: co wysłano, leży w SharedPreferences `dlna`. Przy starcie apka pyta telewizor
    (GetTransportInfo, GetMediaInfo) i jeśli gra dalej ten sam adres, Pilot i powiadomienie wracają.
  - Start DLNA od zapamiętanej pozycji: Seek po wejściu w PLAYING, sprawdzenie pozycji i do 4 prób
    (tuż po starcie Samsung potrafi Seek zignorować). Log `MpDlna`.
  - Chromecast z apki (Cast SDK w telefonie) i ze strony (pychromecast w agencie) to osobni nadawcy: Pilot w apce
    nie widzi filmu puszczonego ze strony i odwrotnie.
- **Powiadomienie i głośność poza apką** (`RemoteSession.kt`): każdy grający cel (ekran z mpv, telewizor DLNA,
  Chromecast sterowany bez Cast SDK) ma własną sesję media3 (`RemotePlayer(kind)` na `SimpleBasePlayer`, DeviceInfo
  REMOTE 0–100) i własne powiadomienie (id 2001/2002/2003; 1001 ma odtwarzacz w telefonie) w `RemoteSessionService`.
  `RemoteSessionService.start(ctx, kind)` dodaje sesję od razu (`addSession`), bo bez tego powiadomienie pojawia się
  dopiero po podłączeniu kontrolera. Sesja znika po ~10 s ciszy, usługa razem z ostatnią. Chromecast z Cast SDK ma
  powiadomienie z SDK. Android 17 i tak pokazuje jedną kartę na Chromecasta: naszą scala z systemową kartą Cast.
- **Chromecast z przeglądarki**: klient webowy nie ma Cast SDK (wymaga HTTPS), więc steruje agent (`mp_cast.py`,
  pychromecast w venv), a film idzie z agenta pod adresem LAN. Agent słucha wszystkich Chromecastów, więc Pilot
  w apce (bez własnej sesji Cast) i strona widzą też to, co puścił ktoś inny.
- **Czy zagra na Chromecaście**: agent zna model każdego Chromecasta (`mp_renderers.py`) i ocenia plik dla każdego
  osobno (`cast_devices`). „Przygotuj” proponuje tylko wtedy, gdy któryś ma kłopot. Jak pytać urządzenia o model
  i możliwości: `docs/urzadzenia.md`.

### Wskazanie pliku (`ref`)

Endpointy odtwarzania i napisów przyjmują jedną z form:
- `{"torrent_id": "<hash>", "file_index": N}`: plik z torrenta;
- `{"root": N, "path": "katalog/film.mkv"}`: plik z biblioteki;
- `{"playing": true}`: to, co gra teraz w mpv.

Odpowiada za to `App.resolve_ref`, który zawsze zwraca ścieżkę wewnątrz katalogu pobierania albo biblioteki.

### Widoczność katalogów biblioteki

Katalog w `config.json` (`library`) ma oprócz `name` i `path` stałe `id`, `tags`, `visible` (`"all"`, `"owners"` albo
lista id urządzeń) i `exclude` (podkatalogi względem katalogu). Handler po sprawdzeniu tokenu ustawia w wątku, kto
pyta (`mp_library.set_viewer`), a `Library.root`/`resolve`/`browse` odmawiają ukrytego katalogu i wykluczonej ścieżki.
Bez ustawionego pytającego (bilet na jeden plik, wątki w tle) ograniczeń nie ma: bilet wydaje się dopiero po
sprawdzeniu. Indeksy katalogów zostają globalne, więc lista dla gościa może mieć dziury (np. 0 i 2).
Plan dalszych kroków (magnet → katalog po tagu, porządkowanie, okładki): `plan-biblioteka.md`.

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

### Przesyłanie pobranych filmów z telefonu
Opcja w Ustawieniach, domyślnie wyłączona (szczegóły i decyzje: [plan-przesylanie.md](plan-przesylanie.md)).
1. Przeglądarka kończy pobieranie do `Download/`. Wyzwalacz WorkManagera na MediaStore (także przy zamkniętej apce)
   albo ContentObserver zauważa nowy film (`IS_PENDING = 0`, dodany po włączeniu opcji, bez `DCIM/`).
2. Powiadomienie od razu: „Prześlij” albo „Prześlij i usuń z telefonu”.
3. `UploadWorker` (tylko Wi-Fi): gdy serwer nie odpowiada na `ping`, czeka i próbuje co 15 min. Potem
   `GET /api/upload/<id>` i `PUT` kawałkami po 64 MB od `received`.
4. Agent (`mp_upload.py`) składa plik w `.mp-upload/<id>.part` i przenosi go do „Z telefonu” w bibliotece.
5. Z usuwaniem: „Dotknij, aby usunąć” → systemowe potwierdzenie `MediaStore.createDeleteRequest` (Android 11+).

### Usypianie
- `SleepGuard` co 20 s sprawdza, czy coś się pobiera albo gra. Jeśli tak, trzyma
  `systemd-inhibit --what=sleep --mode=block`. Przy okazji wywołuje `jobs.tick`.
- `jobs.tick` przy akcji `stop` wstrzymuje torrent od razu po pobraniu.
- Przy akcji `suspend` czeka 60 s po skończeniu i usypia komputer tylko wtedy, gdy nic się nie pobiera i nic nie
  gra. Każde zlecenie wykonuje raz.
- Przycisk „Uśpij” zamyka mpv i woła `systemctl suspend`. Polkit pozwala na to usłudze w tle.

### Aktualizacje apki
Agent wystawia telefonom apkę z `~/.local/share/magneticplayer/apk/` (`MagneticPlayer.apk` i `version.json`).
Trafia tam na dwa sposoby, zawsze najpierw APK, potem wersja:
- **z wydań na GitHubie** (`mp_appupdate.py`, tryb `app_updates: "auto"`, domyślny): minutę po starcie agenta i potem
  co 6 h agent czyta `releases/latest/download/version.json`. Gdy `version_code` jest wyższy od wystawionego,
  pobiera APK i sprawdza odcisk certyfikatu z bloku podpisu APK v2/v3 (treetank `05f72b11…`). Podpis sprawdza Android
  przy instalacji. Nigdy nie cofa wersji. Tryb przełącza telefon w szczegółach serwera („Aktualizacje apki z GitHuba”);
- **ręcznie** `publish.sh` (w trybie `manual` jedyna droga).
Apka porównuje wersje i pobiera nowszą prosto do sesji `PackageInstaller`. `UpdateReceiver` uruchamia systemowe
okno potwierdzenia.

## Klient webowy (`server/web/index.html`)

Jeden plik bez zależności (vanilla JS), serwowany pod `/` bez tokenu. Dane i tak idą przez API z tokenem.
- Token przychodzi w `#t=`, trafia do `localStorage` i znika z paska adresu. `#tab=` wybiera zakładkę.
- Zakładki: Torrenty, Biblioteka i Pilot (odpytywanie `/api/player/status`, ścieżki, klawisze).
- Strona czyta raz `/api/info` i chowa to, czego serwer nie ma: „Uśpij” bez `power.suspend`, „Ekran” bez
  `features.player`/`unlock`, Torrenty bez `features.torrent`, a bez `features.player` także „▶ Na komputerze”
  i pilota mpv (Pilot pokazuje wtedy tylko Chromecasta). Status w nagłówku podaje `name` serwera.
- Język: `navigator.language` (`pl*` → polski, inaczej angielski), słownik `T = {en, pl}` i `tr(klucz, …)`; statyczne
  teksty w HTML przez `data-i18n`, `data-i18n-html`, `data-i18n-title`, `data-i18n-placeholder`. `<html lang>` i
  nagłówek `Accept-Language` w zapytaniach do API ustawiane na ten sam język.
- Okno napisów działa jak w apce (asynchroniczne zlecenie, postęp, „Wystarczy”).
- Odtwarzacz to `<video>` z napisami WebVTT (`/sub?…&vtt=1`).
- DOM buduje helper `put(el, ...dzieci)`, który spłaszcza tablice i pomija `null`. Gołe `replaceChildren` z tablicą
  wypisywało „null[object HTMLDivElement]”.

## Apka (`phone/`)

Teksty w zasobach: angielski domyślnie (`res/values/strings_*.xml`), polski w `res/values-pl/`. Język apki idzie za
językiem telefonu; na Androidzie 13+ można go zmienić osobno w Ustawieniach systemu → „Języki aplikacji”
(`res/xml/locales_config.xml`). Zapytania do agenta niosą ten język w `Accept-Language`, więc błędy agenta przychodzą
w tym samym języku.

| plik | co jest w środku |
|---|---|
| `Api.kt` | `Settings` (SharedPreferences), modele, `MediaRef` (z `srv`), `Wol`, klient HTTP jednego serwera, budzenie (też przez inny serwer) |
| `Servers.kt` | lista serwerów, wybrany serwer plików i ekran, migracja starych ustawień (serwer `legacy`), parowanie (`Pairing`) |
| `ServersUi.kt` | przełącznik serwera, panel „śpi”, okno usypiania, wybór ekranu, Ustawienia → Serwery, dodawanie, urządzenia, „Sparuj kolejne” |
| `MainActivity.kt` | zakładki Torrenty, Pilot i Ustawienia, górny pasek (serwer, Obudź/Uśpij), pasek aktualizacji |
| `Announce.kt` | rozgłaszanie „co gra” (multicast, `MulticastLock`): telefon ogłasza telewizor DLNA, na którym puścił film, i słucha innych (Pilot przejmuje cudzy telewizor przez `Dlna.adopt`) |
| `CastNet.kt` | Chromecasty z telefonu bez agenta i bez Cast SDK: wykrywanie mDNS (NsdManager, gdy Pilot jest na ekranie) i własny klient protokołu Cast v2 (TLS :8009, ręczny protobuf): stan dowolnego nadawcy, pauza, przewijanie, głośność, zamknięcie |
| `Remote.kt` | wspólny „zdalny odtwarzacz” (mpv, Chromecast, DLNA) i wybór celu Pilota (`Pilot`) |
| `RemoteRoutes.kt` | dostawca tras MediaRouter2: nazwa grającego celu (np. „jmpc”, telewizor) na karcie multimediów w systemie zamiast „Ten telefon” |
| `RemoteSession.kt` | sesje media3 dla każdego grającego celu (mpv, DLNA, Chromecast bez SDK): powiadomienia, ekran blokady, głośność przyciskami poza apką |
| `Cast.kt`, `Dlna.kt` | Chromecast (Cast SDK, MediaRouter) i telewizory DLNA (SSDP, SOAP) |
| `PhoneShare.kt` | telefon jako serwer plików dla ekranu, Chromecasta i DLNA |
| `LibraryTab.kt` | biblioteka i wybór katalogu na komputerze |
| `MediaUi.kt` | przyciski Telefon, ekran i VLC, okno napisów |
| `PlayerActivity.kt` | odtwarzacz w telefonie |
| `MagnetActivity.kt` | obsługa magnetów |
| `Updater.kt` | aktualizacje z komputera, wysyłanie własnego APK |
| `Access.kt` | prośby o dostęp: sprawdzanie przy połączeniu (pętla w `MainActivity`), powiadomienie z przyciskami Gość/Domownik/Odrzuć, `AccessReceiver` |
| `InstallShare.kt` | telefon jako źródło instalatora: serwer HTTP na czas okna instalacji z agentem zaszytym w APK (te same trasy co `mp_install.py`) |
| `AgentBundle.kt` | paczka agenta dla `InstallShare`: z APK albo nowsza z wydania na GitHubie (manifest podpisany ECDSA P-256, sha256 paczki, minimalny czytnik tar) |
| `InstallUi.kt` | „Zainstaluj na innym komputerze”: polecenie z kodem dołączenia, czekanie na nowy serwer i parowanie z nim |
| `UploadOverlay.kt` | okienko „Przesłać na serwer?” nad przeglądarką (uprawnienie „Wyświetlanie nad innymi aplikacjami”) |
| `Uploads.kt` | przesyłanie filmów pobranych przeglądarką na serwer: wykrywanie (WorkManager z wyzwalaczem MediaStore, ContentObserver), pytanie w powiadomieniu, `UploadWorker` (czeka na Wi-Fi serwera, wznawia), `UploadDeleteActivity`, ustawienia |

Klucze pozycji, bufora i pobranych filmów mają przedrostek serwera (`<key>:`), poza serwerem `legacy` (migracja
z jednego serwera), żeby stare dane zostały.

Kod jest w Kotlinie 2.2 z Compose (BOM 2025.08) i Media3 1.8. Wymaga Androida 8 (minSdk 26), a celuje w SDK 36.
