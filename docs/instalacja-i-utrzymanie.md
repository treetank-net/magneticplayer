# Instalacja i utrzymanie

## Komputer

Wymagania: Linux Mint lub Ubuntu z sesją graficzną (Cinnamon), Python 3.10+, kabel Ethernet (do WoL).

```bash
bash server/install.sh                 # przy komputerze: część rootowa przez pkexec (okno z hasłem)
BACKEND=none bash server/install.sh    # komputer tylko jako ekran (albo tylko biblioteka): bez Deluge
BACKEND=none PLAYER=0 POWER=0 PORT=8766 bash server/install.sh   # np. laptop: tylko pliki, bez usypiania z sieci
```

Zmienne zapisują się w `config.json` (`backend`, `player`, `power`, `port`), a ponowne `install.sh` bez nich
niczego tam nie zmienia. `player: false`: serwer nie jest ekranem, nawet gdy ma mpv. `power: false`: z sieci nie
da się go uśpić ani wyłączyć (apka chowa „Uśpij”). Na laptopie użytkownika agent działa na 8766, bo 8765 zajmuje
tam inna usługa (`ekrand`).

Na końcu skrypt pokazuje kod parowania i QR. Zeskanuj go aparatem telefonu (otworzy apkę) albo w apce:
Ustawienia → Dodaj serwer → adres i kod. Nowy kod: `~/.local/share/magneticplayer/venv/bin/python
~/.local/share/magneticplayer/magneticplayer_agent.py --pair`, albo z już sparowanego telefonu.

Przez SSH `pkexec` nie działa, bo nie ma sesji polkit. Część rootową robisz wtedy przez `sudo`:

```bash
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
ssh -t jmpc sudo bash .local/src/MagneticPlayer/server/install-root.sh jm
ssh jmpc 'SKIP_ROOT=1 bash .local/src/MagneticPlayer/server/install.sh'
```

Co robią skrypty:

| skrypt | co robi |
|---|---|
| `install-root.sh <user> [port] [backend]` | instaluje pakiety `mpv ethtool qrencode python3-venv`, a przy backendzie Deluge też `deluged deluge-web`<br>wyłącza systemowe usługi Deluge (chodzą jako usługi użytkownika)<br>dodaje regułę polkit `/etc/polkit-1/rules.d/49-magneticplayer.rules`, żeby usługa w tle mogła blokować usypianie i usypiać<br>włącza Wake-on-LAN: NetworkManager `802-3-ethernet.wake-on-lan magic`, udev `81-magneticplayer-wol.rules`, ethtool<br>otwiera port 8765 w ufw dla sieci prywatnych |
| `install.sh` | kopiuje agenta do `~/.local/share/magneticplayer/`<br>tworzy venv z `subliminal`<br>zakłada usługi `systemd --user`: `deluged`, `deluge-web` (127.0.0.1:8112) i `magneticplayer` (:8765)<br>przełącza Deluge GTK w tryb klienta, łączącego się z `deluged`<br>wypisuje kod parowania z QR (`--pair`) |

Ponowne uruchomienie `install.sh` aktualizuje agenta i nie rusza konfiguracji ani tokenu.

### Szybka aktualizacja samego agenta

```bash
scp server/*.py jmpc:.local/share/magneticplayer/ && ssh jmpc systemctl --user restart magneticplayer
```

### Pliki na komputerze

| ścieżka | zawartość |
|---|---|
| `~/.config/magneticplayer/config.json` | `id` i `name` serwera, stary wspólny token, port, backend (`deluge`/`transmission`/`none`), hasło deluge-web, katalogi biblioteki, klucz OpenSubtitles i opcjonalny login, `mpv_args`, `auto_unlock` |
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

`phone/magneticplayer.keystore` i `phone/keystore.properties` są **poza gitem**. Generuje je pierwszy build.
Trzymaj kopię zapasową: bez tego klucza żadna nowa wersja nie zainstaluje się jako aktualizacja.
Trzeba by wtedy odinstalować apkę na każdym telefonie i sparować go od nowa.

**Kopia zapasowa** jest na `ovh-vps` w `~/backup/MagneticPlayer/` (katalog 700, pliki 600). Obok leży `README.txt`
z instrukcją odtworzenia i sumami sha256. Odtworzenie:

```bash
scp 'ovh-vps:backup/MagneticPlayer/{magneticplayer.keystore,keystore.properties}' phone/
```

Kod repo jest też na `ovh-vps` w `~/git/MagneticPlayer.git` (remote `ovh`).

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
  adres z biletem i ciągnie film przez sieć. Pilot ma własny wybór ekranu.
- Uśpiony serwer budzi telefon, a po kilku sekundach także inny działający serwer (gdy telefon go nie dosięga).
  Okno „Uśpij” ostrzega, gdy budzenie przez sieć jest niepewne albo niemożliwe (Wi-Fi, brak WoL).
- Stary serwer z jednego tokenu przechodzi do listy sam, z zachowaniem pozycji, bufora i pobranych filmów.

### Aktualizacje dla domowników

Najpierw przetestuj u siebie, potem:

```bash
phone/tools/publish.sh          # out/MagneticPlayer.apk + version.json → jmpc:~/.local/share/magneticplayer/apk/
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
| torrent „wstrzymany”, choć nikt nie klikał | akcja „zatrzymaj po pobraniu” albo pauza z Deluge GTK, sprawdź w historii zleceń |

### Diagnostyka strumieniowania

`ssh jmpc touch ~/.config/magneticplayer/debug-stream` włącza w logu agenta (`journalctl --user -u magneticplayer`)
linię na każde żądanie filmu i napisów: zakres (`Range`), wysłane KB, czas. `rm` tego pliku wyłącza.
