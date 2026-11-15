# Instalacja i utrzymanie

## Komputer

Wymagania: Linux Mint lub Ubuntu z sesją graficzną (Cinnamon), Python 3.10+, kabel Ethernet (do WoL).

```bash
bash server/install.sh                 # przy komputerze: część rootowa przez pkexec (okno z hasłem)
```

Przez SSH `pkexec` nie działa, bo nie ma sesji polkit. Część rootową robisz wtedy przez `sudo`:

```bash
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
ssh -t jmpc sudo bash .local/src/MagneticPlayer/server/install-root.sh jm
ssh jmpc 'SKIP_ROOT=1 bash .local/src/MagneticPlayer/server/install.sh'
```

Co robią skrypty:

| skrypt | co robi |
|---|---|
| `install-root.sh <user>` | instaluje pakiety `deluged deluge-web mpv ethtool`<br>wyłącza systemowe usługi Deluge (chodzą jako usługi użytkownika)<br>dodaje regułę polkit `/etc/polkit-1/rules.d/49-magneticplayer.rules`, żeby usługa w tle mogła blokować usypianie i usypiać<br>włącza Wake-on-LAN: NetworkManager `802-3-ethernet.wake-on-lan magic`, udev `81-magneticplayer-wol.rules`, ethtool<br>otwiera port 8765 w ufw dla sieci prywatnych |
| `install.sh` | kopiuje agenta do `~/.local/share/magneticplayer/`<br>tworzy venv z `subliminal`<br>zakłada usługi `systemd --user`: `deluged`, `deluge-web` (127.0.0.1:8112) i `magneticplayer` (:8765)<br>przełącza Deluge GTK w tryb klienta, łączącego się z `deluged`<br>wypisuje link parowania |

Ponowne uruchomienie `install.sh` aktualizuje agenta i nie rusza konfiguracji ani tokenu.

### Szybka aktualizacja samego agenta

```bash
scp server/*.py jmpc:.local/share/magneticplayer/ && ssh jmpc systemctl --user restart magneticplayer
```

### Pliki na komputerze

| ścieżka | zawartość |
|---|---|
| `~/.config/magneticplayer/config.json` | token, port, backend (`deluge`/`transmission`), hasło deluge-web, katalogi biblioteki, klucz OpenSubtitles i opcjonalny login, `mpv_args` |
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
3. U siebie: Ustawienia → **„Sparuj kolejne urządzenie”** pokazuje QR z linkiem parowania. Druga osoba skanuje
   go aparatem. Jeśli link się nie otworzy, wyślij go przez „Udostępnij link”, a druga osoba w apce wybiera
   „Wklej link parowania”.

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

Otwórz `http://192.168.100.100:8765/#t=<token>`. Token zapisze się w przeglądarce i zniknie z paska adresu, więc
później wystarczy samo `http://192.168.100.100:8765/`. Dodatek `#tab=remote` (albo `library`, `torrents`)
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
| nie widać mpv na komputerze | ekran zablokowany: w apce menu → „Włącz ekran komputera”, ale sesję trzeba odblokować |
| OpenSubtitles: „dzienny limit pobrań” | klucz „Under development” daje 100 pobrań na dobę, a anonimowe pobrania mają limit konta. Pomaga podanie loginu w apce albo inne źródło |
| `pkexec` przez SSH: „No session for cookie” | użyj wariantu z `sudo`, opisanego wyżej |
| torrent „wstrzymany”, choć nikt nie klikał | akcja „zatrzymaj po pobraniu” albo pauza z Deluge GTK, sprawdź w historii zleceń |
