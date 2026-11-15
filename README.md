# MagneticPlayer

Magnet link na telefonie → pobieranie w Deluge na komputerze (z budzeniem przez Wake-on-LAN) → oglądanie
na telefonie albo na komputerze, z telefonem jako pilotem.

```
server/   agent na komputer (Linux Mint, Python bez zależności) + instalator
phone/    apka na Androida (Kotlin, Compose, Media3/ExoPlayer + FFmpeg do AC3/DTS)
```

## Komputer (`server/`)

```
bash server/install.sh          # lokalnie: część rootowa przez pkexec
# przez SSH część rootowa przez sudo (pkexec nie ma tam sesji polkit):
ssh -t jmpc sudo bash .local/src/MagneticPlayer/server/install-root.sh jm
ssh jmpc 'SKIP_ROOT=1 bash .local/src/MagneticPlayer/server/install.sh'
```

- `install-root.sh`: deluged, deluge-web, mpv; reguła polkit (blokada usypiania, uśpienie, wyłączenie z usługi
  w tle); Wake-on-LAN (NetworkManager `802-3-ethernet.wake-on-lan magic` + udev dla `power/wakeup`).
- `install.sh`: usługi `systemd --user` (deluged, deluge-web na 127.0.0.1:8112, magneticplayer na :8765),
  Deluge GTK w trybie klienta (łączy się z deluged), na końcu link parowania dla telefonu.
- Konfiguracja: `~/.config/magneticplayer/config.json` (token, backend deluge/transmission, katalogi biblioteki,
  konto OpenSubtitles); zlecenia z telefonu: `~/.config/magneticplayer/jobs.json`.
- Logi: `journalctl --user -u magneticplayer -f`.

Agent:
- dodaje magnety (domyślnie pobieranie sekwencyjne + pierwszy/ostatni kawałek pierwsze),
- strumieniuje pliki z obsługą Range (`/media/<torrent>/<plik>`, `/lib/<katalog>/<ścieżka>`),
- liczy, ile filmu da się obejrzeć (kawałki pobrane ciągiem od początku pliku; długość z mpv),
- steruje mpv przez JSON IPC (fullscreen, przewijanie, głośność, napisy, ścieżki),
- trzyma `systemd-inhibit` podczas pobierania i odtwarzania; po pobraniu może zatrzymać torrent albo uśpić
  komputer,
- szuka i pobiera napisy przez subliminal (venv w `~/.local/share/magneticplayer/venv`): BSPlayer, Podnapisi,
  Gestdown/Addic7ed, NapiProjekt bez kont; OpenSubtitles (opensubtitles.com, nie .org) po podaniu
  klucza API w apce (login opcjonalny). Napisy zapisuje obok filmu jako `<film>.<język>.srt` (mpv łapie je sam).

## Telefon (`phone/`)

```
phone/tools/build-apk.sh     # build w kontenerze Android SDK → phone/out/MagneticPlayer.apk
adb install -r phone/out/MagneticPlayer.apk
```

Kolejny telefon: w apce Ustawienia → „Sparuj kolejne urządzenie” pokazuje QR z linkiem parowania (i „Udostępnij link”).
„Wyślij apkę” wysyła zainstalowany plik APK dowolnym komunikatorem.

Aktualizacje dla innych telefonów (np. domowników): przetestuj u siebie, potem

```
phone/tools/publish.sh       # wrzuca out/MagneticPlayer.apk + version.json do ~/.local/share/magneticplayer/apk/ na jmpc
```

Apki połączone z tym komputerem pokażą pasek „Jest aktualizacja” (`/api/app/version`, plik `/app/MagneticPlayer.apk`,
oba za tokenem). Android poprosi o potwierdzenie, a za pierwszym razem o zgodę na instalowanie z MagneticPlayera.
`versionCode` rośnie przy każdym buildzie (minuty od 2025-01-01).

Klucz podpisu: `phone/magneticplayer.keystore` + `keystore.properties` (poza gitem). Nie gub go, bo aktualizacje
apki muszą być podpisane tym samym kluczem.

Magnet linki: gdy inna apka (np. Flud) jest domyślna, w Ustawieniach Androida → Aplikacje → ta apka →
„Otwieraj domyślnie” → wyczyść, potem przy magnecie wybierz MagneticPlayer → „Zawsze”.
