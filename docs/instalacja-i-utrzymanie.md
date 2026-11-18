# Instalacja i utrzymanie

## Komputer

Wymagania: Linux Mint lub Ubuntu z sesją graficzną (Cinnamon), Python 3.10+, kabel Ethernet (do WoL).

```bash
bash server/install.sh                 # przy komputerze: część rootowa przez pkexec (okno z hasłem)
BACKEND=none bash server/install.sh    # komputer tylko jako ekran (albo tylko biblioteka): bez Deluge
BACKEND=none PLAYER=0 POWER=0 PORT=8766 bash server/install.sh   # np. laptop: tylko pliki, bez usypiania z sieci
LITE=1 bash server/install.sh          # cudzy komputer: bez hasła administratora (niżej)
bash ~/.local/share/magneticplayer/uninstall.sh [--purge]   # odinstalowanie
```

Zmienne zapisują się w `config.json` (`backend`, `player`, `power`, `port`; `LITE=1` dokłada `wake: false`), a ponowne `install.sh` bez nich
niczego tam nie zmienia. `player: false`: serwer nie jest ekranem, nawet gdy ma mpv. `power: false`: z sieci nie
da się go uśpić ani wyłączyć (apka chowa „Uśpij”). Na laptopie użytkownika agent działa na 8766, bo 8765 zajmuje
tam inna usługa (`ekrand`).

Na końcu skrypt pokazuje kod parowania i QR. Zeskanuj go aparatem telefonu (otworzy apkę) albo w apce:
Ustawienia → Dodaj serwer → adres i kod. Nowy kod: `~/.local/share/magneticplayer/venv/bin/python
~/.local/share/magneticplayer/magneticplayer_agent.py --pair`, albo z już sparowanego telefonu.

### Instalacja lekka (`LITE=1`)

Na komputer, którego właściciel nie da hasła administratora albo nie chce zmian w systemie (znajomy, rodzice).
Nie ma części rootowej, torrentów, usypiania z sieci ani Wake-on-LAN (`backend: none`, `power: false`,
`wake: false`). Zostają Biblioteka, przesyłanie z telefonu, napisy i ekran, jeśli są już mpv i sesja graficzna.
Potrzebne są tylko `python3` i `systemd --user`.

- Bez pakietu `python3-venv` (świeże Ubuntu) venv powstaje z `--without-pip`, a pip przychodzi z
  `bootstrap.pypa.io/get-pip.py`. Z venv są napisy i QR w terminalu (moduł `qrcode` zamiast `qrencode`).
- Gdy nie ma `~/Wideo`, `~/Videos` ani `~/Filmy`, instalator zakłada katalog wideo z XDG (zwykle `~/Videos`).
  Bez niego nie byłoby Biblioteki, a przesyłanie nie miałoby dokąd zapisywać. To samo robi `BACKEND=none`.
- `loginctl enable-linger` (bez roota, gdy pozwala polkit): agent startuje z komputerem i działa po wylogowaniu.
  Znacznik `~/.local/share/magneticplayer/.linger` mówi `uninstall.sh`, że linger włączył instalator.
- Zapory nie da się otworzyć bez roota. Na Ubuntu i Mincie ufw jest domyślnie wyłączony, więc zwykle to nie
  przeszkadza.

Sprawdzone 17.11.2026 na czystym Ubuntu 24.04 (VirtualBox, sieć mostkowana): instalacja, parowanie,
przesyłanie z polskimi znakami w nazwie, start po restarcie bez logowania, ponowna instalacja i deinstalacja
z `--purge`.

`uninstall.sh` wyłącza i usuwa usługi (Deluge tylko wtedy, gdy założył je nasz instalator), rootowe dodatki pełnej
instalacji (polkit, udev WoL, reguły ufw z komentarzem MagneticPlayer; przez pkexec albo sudo) i
`~/.local/share/magneticplayer`. Z `--purge` usuwa też `~/.config/magneticplayer` (token, sparowane telefony).
Filmów i pakietów systemowych nie rusza.

### Z istniejącego serwera (agent 1.3+)

W apce: Ustawienia → „Zainstaluj na innym komputerze” → „Pokaż polecenie”. Na nowym komputerze (Linux, ta sama
sieć) wklejasz w terminalu np. `curl -fsSL http://192.168.100.100:8765/i/ABC123 | bash`. Skrypt pobiera agenta
z serwera (bez GitHuba i repozytorium), instaluje go lekko albo w pełni (przełącznik „Bez hasła administratora”),
a nowy agent zgłasza się z kodem parowania. Telefon, który pokazał polecenie, paruje się z nim sam. Kod działa raz,
przez 30 min. Bez telefonu pod ręką: `--pair` na nowym komputerze, jak zwykle. Szczegóły: `docs/api.md`.

Instalator kopiuje `install.sh`, `install-root.sh` i `uninstall.sh` obok agenta, bo z nich serwer składa paczkę.
Starszy serwer dostaje je po ponownym `install.sh` (albo `scp` jak przy szybkiej aktualizacji).

Sprawdzone 17.11.2026: jmpc → czyste Ubuntu 24.04 w VirtualBox (instalacja lekka, zgłoszenie, parowanie kodem
ze zgłoszenia, ponowne użycie kodu odrzucone).

### Z telefonu, bez serwera (apka 1.2611+)

Ten sam przycisk „Zainstaluj na innym komputerze” działa też bez żadnego serwera: źródłem jest „Ten telefon”.
`build-apk.sh` zaszywa w APK paczkę agenta (`assets/agent/agent.tgz`, ~80 KB: pliki z `server/` śledzone przez git;
nie `.tar.gz`, bo AAPT rozpakowuje takie assets) i szablony `join.sh`/`join.ps1`. Na czas otwartego okna telefon uruchamia w Wi-Fi mały serwer HTTP
(`InstallShare.kt`, losowy port) z tymi samymi trasami co agent: `/i/<kod>`, `/i/<kod>/ps`, `/i/<kod>/bundle.tar.gz`
i `POST /api/install/join`. Polecenie wygląda jak zwykle (`curl -fsSL http://<telefon>:<port>/i/<kod> | bash`),
a po zgłoszeniu nowego agenta telefon paruje się sam. Okno musi zostać otwarte do końca instalacji.

Po otwarciu okna apka sprawdza (najwyżej co 6 godzin) ostatnie wydanie na GitHubie (`AgentBundle.kt`): pobiera
manifest, sprawdza podpis kluczem z apki i, gdy paczka jest nowsza niż zaszyta w APK, pobiera ją i porównuje sha256.
Taką paczkę telefon serwuje dalej („agent … z wydania d.MM.rrrr” w oknie); bez sieci albo przy błędzie podpisu
zostaje paczka z APK. Wersję (czas commita) niesie podpisany manifest, więc starszej paczki nie da się podsunąć.

Ponowna instalacja na tym samym komputerze (np. po `uninstall.sh --purge`, z nowym id agenta) zastępuje w apce stary
wpis o tym samym adresie i porcie zamiast dodawać drugi (`Pairing.pair`); dotyczy każdego parowania, nie tylko z telefonu.

Telefon nie potrzebuje internetu, ale nowy komputer tak: paczka to sam kod agenta, a zależności dociąga instalator
(pip: napisy, Chromecast, QR; mpv; na Windows Python). Bez internetu na Linuksie zadziała biblioteka, przesyłanie
i oglądanie na telefonie; na Windows bez Pythona instalacja nie ruszy.

Python: Linux wymaga 3.8+ (sprawdzają `join.sh` i `install.sh`). Na Windows instalator szuka Pythona 3.9+, a gdy go
brak, instaluje 3.12 dla użytkownika przez winget, a bez wingeta (Windows 10 LTSC, stary Windows 10) oficjalnym
instalatorem z python.org (`/quiet InstallAllUsers=0`). Obie drogi bez administratora.

### Windows 10/11 (instalacja lekka)

Z apki: w tym samym oknie przełącznik „Windows”. Polecenie `irm http://<serwer>:8765/i/<kod>/ps | iex` wkleja się
**do otwartego okna PowerShell** (prawy klik na Start → Terminal). Podane wprost w wierszu poleceń (Win+R,
`powershell -c "irm … | iex"`) Defender blokuje jako `Trojan:Win32/Commando.B!ml`; wklejone do okna przechodzi.
Bez innego serwera: rozpakuj repozytorium i uruchom
`powershell -ExecutionPolicy Bypass -File server\install.ps1`.

`install.ps1` (bez administratora):
- Python 3.10+ z `py`/`python` (pomija atrapę ze Sklepu), a gdy go brak, `winget install Python.Python.3.12 --scope user`;
- agent w `%LOCALAPPDATA%\MagneticPlayer`, venv z `subliminal`, `pychromecast`, `qrcode`;
- mpv (ekran): wydanie `mpv-*-x86_64-w64-mingw32-full.zip` z GitHuba do `…\MagneticPlayer\mpv` (wersja MSVC bez
  VC++ runtime nie startuje, kod 0xC0000135);
- port: pierwszy wolny od 8765 (bind wyłączny: zwykły `SO_REUSEADDR` na Windows pozwala dzielić port z innym
  programem nasłuchującym na 127.0.0.1);
- autostart: `HKCU\…\Run` → `pythonw -X utf8 magneticplayer_agent.py` (log w `agent.log` obok agenta);
- zapora: uruchomiony jako administrator otwiera TCP portu i UDP 47700 dla sieci prywatnych i usuwa reguły
  „Blokuj”, które Windows sam zakłada po „Anuluj” w okienku zapory (dotyczą bazowego `pythonw.exe`, bo launcher
  z venv uruchamia bazowy interpreter). Bez administratora Windows zapyta o dostęp przy pierwszym starcie: trzeba
  zaznaczyć sieci prywatne. W sieci „publicznej” zapora i tak blokuje telefon.

Działa: Biblioteka, przesyłanie, napisy, ekran (mpv przez potok `\\.\pipe\magneticplayer-mpv`, jedno trwałe
połączenie, bo nowe mpv zakłada nawet 3 s), Chromecast/DLNA z serwera. Nie ma: torrentów, usypiania, budzenia,
odblokowania ekranu. Odinstalowanie: `uninstall.ps1 [-Purge]` z katalogu agenta.

Sprawdzone 17.11.2026 na Windows 11 w VirtualBox: instalacja i ponowna instalacja, port zajęty przez inny program,
parowanie, przesyłanie, odtwarzanie (open, pauza, przewijanie, stop), dołączenie z jmpc przez `irm … | iex`,
odinstalowanie z `-Purge`. Ekran mpv wymaga, żeby agent działał w sesji zalogowanego użytkownika (autostart to
zapewnia).

### Przez SSH

Przez SSH `pkexec` nie działa, bo nie ma sesji polkit. Część rootową robisz wtedy przez `sudo`:

```bash
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
ssh -t jmpc sudo bash .local/src/MagneticPlayer/server/install-root.sh jm
ssh jmpc 'SKIP_ROOT=1 bash .local/src/MagneticPlayer/server/install.sh'
```

Co robią skrypty:

| skrypt | co robi |
|---|---|
| `install-root.sh <user> [port] [backend]` | instaluje pakiety `mpv ethtool qrencode python3-venv`, a przy backendzie Deluge też `deluged deluge-web`<br>wyłącza systemowe usługi Deluge (chodzą jako usługi użytkownika)<br>dodaje regułę polkit `/etc/polkit-1/rules.d/49-magneticplayer.rules`, żeby usługa w tle mogła blokować usypianie i usypiać<br>włącza Wake-on-LAN: NetworkManager `802-3-ethernet.wake-on-lan magic`, udev `81-magneticplayer-wol.rules`, ethtool<br>otwiera w ufw dla sieci prywatnych port 8765 i UDP 47700 („co gra”, multicast) |
| `install.sh` | kopiuje agenta do `~/.local/share/magneticplayer/`<br>tworzy venv z `subliminal`<br>zakłada usługi `systemd --user`: `deluged`, `deluge-web` (127.0.0.1:8112) i `magneticplayer` (:8765)<br>przełącza Deluge GTK w tryb klienta, łączącego się z `deluged`<br>wypisuje kod parowania z QR (`--pair`)<br>kopiuje `uninstall.sh` obok agenta |

Ponowne uruchomienie `install.sh` aktualizuje agenta i nie rusza konfiguracji ani tokenu.

### Chromecast i wersje dla niego

`install-root.sh` instaluje `ffmpeg`, a `install.sh` instaluje `pychromecast` w venv. Koder dla „Przygotuj wersję
dla Chromecasta” agent wybiera sam przy starcie i pokazuje go w apce w szczegółach serwera. Na jmpc (GTX 970)
powinien to być NVENC, o ile działa sterownik NVIDIA z `libnvidia-encode`. Na Intelu VAAPI, na Macu VideoToolbox.
Wersje leżą w `.mp-cast/` obok filmów i można je kasować ręcznie.

### Szybka aktualizacja samego agenta

```bash
scp server/*.py server/*.sh jmpc:.local/share/magneticplayer/ && ssh jmpc systemctl --user restart magneticplayer
```

### Pliki na komputerze

| ścieżka | zawartość |
|---|---|
| `~/.config/magneticplayer/config.json` | `id` i `name` serwera, stary wspólny token, port, backend (`deluge`/`transmission`/`none`), hasło deluge-web, katalogi biblioteki, klucz OpenSubtitles i opcjonalny login, `mpv_args`, `auto_unlock`, `upload_dir` (katalog na filmy przesłane z telefonu; domyślnie „Z telefonu” w pierwszym katalogu biblioteki) |
| `~/.config/magneticplayer/devices.json` | sparowane urządzenia (skróty tokenów), aktywne kody parowania, klucz biletów |
| `~/.config/magneticplayer/jobs.json` | zlecenia z telefonu: magnet, akcja po pobraniu, czy wykonana |
| `~/.local/share/magneticplayer/` | agent (`magneticplayer_agent.py`, `mp_*.py`) i `venv/` z subliminal |
| `~/.local/share/magneticplayer/apk/` | opublikowana apka i `version.json`, z których korzystają aktualizacje telefonów |
| `$XDG_RUNTIME_DIR/magneticplayer-mpv.sock` | gniazdo JSON IPC mpv |

Logi:
```bash
journalctl --user -u magneticplayer -f
journalctl --user -u deluged
```

## Telefon

### Build

```bash
phone/tools/build-apk.sh        # w kontenerze ghcr.io/cirruslabs/android-sdk:36 → phone/out/MagneticPlayer.apk
adb install -r phone/out/MagneticPlayer.apk
```

- `versionCode` rośnie sam (liczba minut od 2025-01-01), a `versionName` ma postać `1.RRMMDD.GGMM`. Build zapisuje też
  `phone/out/version.json`.
- Gradle trzyma cache w `phone/.gradle-home` (ok. 1 GB, poza gitem).
- Kontener dostaje domyślnie 4 CPU i 6 GB pamięci. Zmienisz to przez `CPUS=` i `MEM=`.

### Klucz podpisu

Od listopada 2026 apka ma identyfikator `net.treetank.magneticplayer` (wcześniej `eu.jm.magneticplayer`), a wydania
podpisuje **klucz treetank** (ten sam co timeprotect, tylko w zmiennych CI, patrz „Wydania”). Stara apka nie
zaktualizuje się do nowej (inny pakiet i klucz): nową instaluje się obok („Wyślij apkę” albo z wydania na GitHubie),
paruje od nowa, a starą odinstalowuje.

`phone/magneticplayer.keystore` i `phone/keystore.properties` (**poza gitem**, generuje je pierwszy build) to od tej
pory klucz deweloperski: podpisuje buildy lokalne (`build-apk.sh`), czyli emulator i testy. Taki build nie
zainstaluje się na wersję z wydania bez odinstalowania. Kopię zapasową i tak trzymamy:

**Kopia zapasowa** jest na `ovh-vps` w `~/backup/MagneticPlayer/` (katalog 700, pliki 600). Obok leży `README.txt`
z instrukcją odtworzenia i sumami sha256. Odtworzenie:

```bash
scp 'ovh-vps:backup/MagneticPlayer/{magneticplayer.keystore,keystore.properties}' phone/
```

Kod jest publiczny (MIT): `origin` = gitlab.com/treetank/magneticplayer, lustro github.com/treetank-net/magneticplayer
(job `mirror-github`), prywatna kopia na `ovh-vps` w `~/git/MagneticPlayer.git` (remote `ovh`).

### Wydania (GitHub)

Wydanie robi GitLab CI, wzorem timeprotect: lokalnie tylko `phone/tools/tag-release.sh` (tag `v1.RRMMDD.GGMM` na
wypchniętym `main`). Job `release` na runnerze `vps2` (`phone/tools/release-ci.sh`):

1. buduje APK (2 CPU, 5 GB: na vps2 działa produkcja) podpisane kluczem treetank (alias `treetank`); odcisk
   certyfikatu jest sprawdzany (`EXPECTED_CERT`), bo każde wydanie musi mieć ten sam klucz, inaczej telefony nie
   przyjmą aktualizacji;
2. pakuje agenta (`phone/tools/agent-bundle.sh`) i pisze manifest `agent.json` (czas commita z `server/`, commit,
   wersja, sha256 paczki) podpisany ECDSA P-256 (`agent.json.sig`);
3. czeka, aż lustro przepchnie tag na GitHub, i publikuje wydanie (`phone/tools/github-release.py`): APK,
   `version.json`, `agent.tgz`, `agent.json`, `agent.json.sig`.

Klucze są tylko w chronionych zmiennych CI projektu ze środowiskiem `release` (`MP_KEYSTORE_B64`, `MP_KEYSTORE_PASS`: kopie
`TP_KEYSTORE_*` z `treetank/timeprotect-android`; `MP_AGENT_KEY_B64`), więc nie dostaje ich żaden inny job; tagi `v*` są chronione. Klucz paczek:
`~/.config/magneticplayer-release/agent-signing.pem` z kopią na `ovh-vps` w `~/backup/MagneticPlayer/`; publiczny
w repo (`phone/tools/agent-signing.pub`), a `build-apk.sh` kopiuje go do apki. Wydanie na GitHubie nie zastępuje
`publish.sh`: domownicy dostają aktualizacje z komputera jak dotąd.

### Kolejne telefony (domownicy)

1. U siebie: Ustawienia → **„Wyślij apkę (WhatsApp itp.)”** wysyła zainstalowany plik APK. W WhatsAppie wyślij go
   jako dokument.
2. Druga osoba instaluje APK, a Android zapyta o zgodę na instalowanie z komunikatora.
3. U siebie: Ustawienia → **„Sparuj kolejne urządzenie”**, zaznacz serwery → QR z jednorazowymi kodami (10 min).
   Druga osoba skanuje go aparatem i od razu ma wszystkie zaznaczone serwery. Jeśli link się nie otworzy:
   „Udostępnij link”, a w apce Ustawienia → Dodaj serwer → „Wklej link parowania”. Albo wpisuje adres i kod ręcznie.

### Kilka serwerów

- Każdy komputer z agentem to osobny serwer: „pliki” (torrenty i/lub biblioteka) i/lub „ekran” (mpv).
  Komputer tylko do oglądania: `BACKEND=none bash server/install.sh`.
- Nazwa serwera u góry apki; ▾ (gdy serwerów jest kilka) przełącza, z którego są Torrenty i Biblioteka.
- Przycisk ekranu przy filmie odtwarza na wybranym komputerze. Gdy film jest na innym serwerze, ekran dostaje
  adres z biletem i ciągnie film przez sieć. Pilot ma żetony celów: ekran (▾ gdy kilka), Chromecast i telewizor.
- Uśpiony serwer budzi telefon, a po kilku sekundach także inny działający serwer (gdy telefon go nie dosięga).
  Okno „Uśpij” ostrzega, gdy budzenie przez sieć jest niepewne albo niemożliwe (Wi-Fi, brak WoL).
- Stary serwer z jednego tokenu przechodzi do listy sam, z zachowaniem pozycji, bufora i pobranych filmów.

### Aktualizacje dla domowników

Najpierw przetestuj u siebie, potem:

```bash
phone/tools/publish.sh          # out/MagneticPlayer.apk + version.json → jmpc:~/.local/share/magneticplayer/apk/
phone/tools/publish.sh --release latest   # to samo z APK z wydania na GitHubie (klucz treetank, jak na telefonach)
```

- Apka po połączeniu z komputerem pyta `/api/app/version`. Gdy wersja jest nowsza od zainstalowanej, pokazuje pasek
  „Jest aktualizacja”.
- „Zaktualizuj” pobiera plik przez PackageInstaller i Android prosi o potwierdzenie. Za pierwszym razem prosi też
  o zgodę na instalowanie aplikacji z MagneticPlayera.
- Sam build niczego nie publikuje.

### Magnet linki

Gdy magnety otwiera inna apka (np. Flud), wejdź w Ustawienia Androida → Aplikacje → tamta apka →
„Otwieraj domyślnie” i wyczyść domyślne. Przy następnym magnecie wybierz MagneticPlayer → „Zawsze”.

## Klient webowy (laptop, tablet)

Link z kodem daje telefon: Ustawienia → „Sparuj kolejne urządzenie” → adres `http://192.168.100.100:8765/#p=<KOD>`.
Przeglądarka wymienia kod na własny token (urządzenie „Przeglądarka · system” na liście), kod znika z paska adresu,
więc później wystarczy samo `http://192.168.100.100:8765/`. Na stronie logowania można też wpisać kod ręcznie.
Stary sposób `#t=<token>` działa, dopóki stary wspólny token jest włączony. Dodatek `#tab=remote` (albo `library`, `torrents`)
otwiera od razu wybraną zakładkę.

| zakładka | co umie |
|---|---|
| Torrenty | dodawanie magnetu, „po pobraniu”, wstrzymaj i wznów, pliki z „ile da się obejrzeć”, wcześniejsze zlecenia |
| Biblioteka | przeglądanie katalogów (dodaje się je w apce) |
| Pilot | to samo co w apce, plus klawisze: spacja, ← →, ↑ ↓, `f`, `m` |

Przy każdym filmie: „Na komputerze”, „W przeglądarce”, „Link do VLC” i „Napisy…”.

Ograniczenia przeglądarki:
- **Odtwarzanie:** przeglądarka odtworzy H.264 z AAC, czasem AV1. Nie odtworzy HEVC/x265 ani dźwięku
  E-AC3/DTS. Wtedy zostaje „Na komputerze” albo VLC.
- **Napisy:** agent zamienia je w locie z SRT na WebVTT (`/sub?…&vtt=1`).
- **Budzenie:** przeglądarka nie wyśle magic packetu, więc uśpiony komputer budzisz z telefonu.

Strona to jeden plik `server/web/index.html` (bez zależności). `install.sh` kopiuje go do
`~/.local/share/magneticplayer/web/`.

## Typowe problemy

| objaw | przyczyna i rozwiązanie |
|---|---|
| „Komputer nie odpowiada” u nowego telefonu | brak tokenu, sparuj kodem QR; albo telefon nie jest w domowym Wi-Fi |
| WoL nie budzi | komputer musi być na kablu<br>sprawdź `nmcli -g 802-3-ethernet.wake-on-lan c show <połączenie>` (powinno być `magic`) i `cat /sys/class/net/<if>/device/power/wakeup` (`enabled`)<br>przy pełnym wyłączeniu (S5) potrzebne jest też WoL w BIOS-ie |
| nie widać mpv na komputerze | ekran zablokowany: „Na komputerze” i menu → „Włącz i odblokuj ekran” zdejmują blokadę (`auto_unlock`). Gdy nie, sprawdź `loginctl show-user $USER -p Display` (sesja graficzna) |
| „Na komputerze” wisi 5 s i zwraca błąd z `cinnamon-screensaver-command` | stara wersja agenta: `--deactivate` w Cinnamon 5.4 wywala wygaszacz (`grab_helper`). Zaktualizuj agenta |
| OpenSubtitles: „dzienny limit pobrań” | klucz „Under development” daje 100 pobrań na dobę, a anonimowe pobrania mają limit konta. Pomaga podanie loginu w apce albo inne źródło |
| `pkexec` przez SSH: „No session for cookie” | użyj wariantu z `sudo`, opisanego wyżej |
| telewizor Samsung „wczytuje…” i nic nie gra | na telewizorze czeka pytanie o zgodę dla nowego urządzenia (telefon albo PC). Zezwól pilotem i wyślij film jeszcze raz |
| pobrany film „zniknął z telefonu” | stare wersje trzymały pobrane w `cacheDir`, który Android czyści przy braku miejsca. Od 1.2.0 są w `noBackupFilesDir/media`, a lista pokazuje, ile zostało. Pobierz ponownie |
| torrent „wstrzymany”, choć nikt nie klikał | akcja „zatrzymaj po pobraniu” albo pauza z Deluge GTK, sprawdź w historii zleceń |

### Diagnostyka strumieniowania

`ssh jmpc touch ~/.config/magneticplayer/debug-stream` włącza w logu agenta (`journalctl --user -u magneticplayer`)
linię na każde żądanie filmu i napisów: zakres (`Range`), wysłane KB, czas. `rm` tego pliku wyłącza.
