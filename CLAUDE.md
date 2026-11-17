# MagneticPlayer: notatki dla agenta

Domowy projekt: magnet z telefonu → Deluge na PC → oglądanie na telefonie, w przeglądarce albo w mpv na PC z telefonem
jako pilotem. Opis dla ludzi jest w `README.md` i `docs/`. Przeczytaj je przed większą zmianą:
- `docs/architektura.md`: komponenty, przepływy, napisy, ekran blokady, klient webowy;
- `docs/api.md`: każdy endpoint agenta;
- `docs/instalacja-i-utrzymanie.md`: deploy, aktualizacje apki, klucz podpisu, typowe problemy;
- `docs/siec-i-bezpieczenstwo.md`: założenie „tylko LAN”, token, `auto_unlock`;
- `docs/urzadzenia.md`: jak zapytać Chromecasta/DLNA/Samsunga, kim jest i co umie; profile Chromecastów i źródła;
- `docs/plan-wiele-serwerow.md`: decyzje etapu 1 (wiele serwerów, pliki vs ekran, parowanie, tokeny per urządzenie), wdrożony.

Odpowiadaj po polsku.

## Gdzie co jest

| co | gdzie |
|---|---|
| PC z agentem | `ssh jmpc` (Linux Mint 21, Cinnamon 5.4, 192.168.100.100), użytkownik `jm`, sesja graficzna `c1` (X11) |
| agent na PC | `~/.local/share/magneticplayer/` (`*.py`, `web/`, `apk/`, `venv/`), usługa `systemctl --user … magneticplayer`, port 8765 |
| kopia źródeł na PC | `~/.local/src/MagneticPlayer/server` (z niej działa `install.sh`) |
| konfiguracja i token | `jmpc:~/.config/magneticplayer/config.json`. **Tokenu nie wypisuj ani nie wklejaj nigdzie** |
| repo | git, gałąź `main`, remote `ovh` = `ovh-vps:git/MagneticPlayer.git` |
| kopia klucza podpisu | `ovh-vps:~/backup/MagneticPlayer/` (+ `README.txt`) |
| telefony | Pixel 8a użytkownika (adb po Wi-Fi, port zmienia się co sesję debugowania) i telefon żony (tylko aktualizacje przez `publish.sh`) |

## Typowa praca

```bash
# agent: wdrożenie po zmianie w server/
python3 -m py_compile server/*.py
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
scp -q server/*.py jmpc:.local/share/magneticplayer/ && scp -q server/web/index.html jmpc:.local/share/magneticplayer/web/
ssh jmpc systemctl --user restart magneticplayer
ssh jmpc journalctl --user -u magneticplayer -n 50

# apka: build w kontenerze (kilka minut), potem publikacja jako aktualizacja dla wszystkich telefonów
phone/tools/build-apk.sh && phone/tools/publish.sh
```

Testy API na PC, bez wyciągania tokenu na zewnątrz:

```bash
ssh jmpc 'T=$(python3 -c "import json;print(json.load(open(\".config/magneticplayer/config.json\"))[\"token\"])");
  curl -s -H "Authorization: Bearer $T" localhost:8765/api/player/status'
```

Polecenia w sesji graficznej przez ssh wymagają `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus DISPLAY=:0`.

Użytkownik prosi o commit i push po skończonej zmianie (`git push ovh main`). Kod i dokumentacja zmieniają się
razem.

## Zasady

- Sekrety: `phone/magneticplayer.keystore`, `phone/keystore.properties` i token są poza gitem. Hooki blokują dostęp
  do `.keystore` i `~/.ssh`: nie obchodź ich, tylko daj użytkownikowi polecenie (przez schowek, `xclip`).
- Root na PC: `ssh -t jmpc sudo …` (pkexec przez ssh nie działa). Lokalnie `pkexec`.
- Żona używa apki: `publish.sh` wypycha aktualizację wszystkim telefonom. Użytkownik zwykle testuje najpierw sam,
  ale gdy prosi, publikuj od razu.
- Nie blokuj ekranu i nie usypiaj PC bez potrzeby, bo ktoś może przy nim siedzieć albo oglądać.

## Pułapki, na które już się nadziałem

- **Cinnamon 5.4:** `cinnamon-screensaver-command --deactivate` wywala wygaszacz (`grab_helper`) i wisi. Przez to
  „Na komputerze” zwracało 500. Do odblokowania służy tylko `loginctl unlock-session <Display>` (patrz
  `unlock_screen()`). Wyjątki `grab_helper` w logu wygaszacza po unlock niezablokowanego ekranu są nieszkodliwe.
- `pgrep -x cinnamon-screensaver` nie znajduje procesu (nazwa jest ucięta), więc używaj `pgrep -af '^cinnamon-screensaver'`.
- **subliminal 2.8:** pobieranie z OpenSubtitles bez loginu idzie przez `download_subtitle.__wrapped__`, a wyjątek
  limitu nazywa się `DownloadLimitExceeded`.
  - Podnapisi.net nie działa (DNS), a tvsubtitles zwraca 403, więc go usunięto.
  - BSPlayer potrafi myśleć ~45 s i od tego jest „Wystarczy”.
- Bez filtra tytułu OpenSubtitles oddaje „podobne” filmy (Der Golem → zupełnie inny film). Filtr i druga runda
  z tytułem w `[nawiasie]` są w `mp_subs.search`.
- Web: nie podawaj tablic do `replaceChildren`, tylko używaj `put()`.
- Zrzut ekranu telefonu przez adb jest czarny, gdy ekran śpi. Wtedy `uiautomator dump`. Telefonu nie odblokowuj.
- Samsung (UE43J5500, DLNA) pyta na ekranie o zgodę dla każdego nowego adresu sterującego; bez `DLNA.ORG_OP=01` nie przewija.
- Chromecast „Duży pokój” ma stałą głośność (głośność tylko pilotem telewizora).
- Android czyści `cacheDir`, gdy apka przekroczy limit cache: pobrane filmy są w `noBackupFilesDir/media`.
- media3 `MediaSessionService`: sesję trzeba dodać `addSession()`, inaczej brak powiadomienia do czasu podłączenia kontrolera.
- `systemctl --user restart magneticplayer` zabijał mpv (ten sam cgroup); dlatego mpv startuje przez `systemd-run --scope`.

## Otwarte sprawy

- **Telewizory, agent 1.2.0, wspólny Pilot: sprawdzone na sprzęcie** (jmpc NVENC, laptop VAAPI, „Przygotuj”,
  Chromecast z serwera i z telefonu, Samsung DLNA z napisami i przewijaniem, Pilot dla mpv/Chromecasta/DLNA,
  powiadomienia, głośność poza apką, mpv przeżywa restart agenta, Chromecast ze strony (przez API agenta),
  telefon jako źródło dla ekranu jmpc, odzyskiwanie DLNA po ubiciu apki). Zostało:
  - strona webowa w przeglądarce (karta Chromecasta po zmianie `volume_fixed`) — sprawdzone tylko API i składnia JS;
  - aktualizacja po powrocie apki na ekran („Sprawdź budzenie” użytkownik sprawdza sam, usypiając i budząc);
  - kafelek w panelu systemowym pisze „Ten telefon” przy mpv/DLNA (brak trasy MediaRouter2);
  - Pilot odświeża się 4×/s, więc `uiautomator dump` często nie działa („could not get idle state”): przy testach zrzuty ekranu.

- Pomysł: sprawdzanie aktualizacji, gdy apka wraca na pierwszy plan (dziś tylko gdy apka złapie połączenie z PC albo zmienią się ustawienia).
- Pomysł: zdalny dostęp spoza LAN (Tailscale/WireGuard, opisane w `docs/siec-i-bezpieczenstwo.md`).
