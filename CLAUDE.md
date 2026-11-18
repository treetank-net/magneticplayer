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
| konfiguracja i token | `jmpc:~/.config/magneticplayer/config.json`; token do testów z terminala: `cli-token` obok (urządzenie „Testy z terminala (jmpc)”; stary wspólny token z `config.json` jest wyłączony). **Tokenów nie wypisuj ani nie wklejaj nigdzie** |
| repo | **publiczne** (MIT): `origin` = gitlab.com/treetank/magneticplayer, CI lustruje na github.com/treetank-net/magneticplayer; `ovh` = `ovh-vps:git/MagneticPlayer.git` (prywatna kopia). Prywatne notatki w `CLAUDE.local.md` (poza gitem) |
| kopia klucza podpisu | `ovh-vps:~/backup/MagneticPlayer/` (+ `README.txt`) |
| telefony | Pixel 8a użytkownika (adb po Wi-Fi, port zmienia się co sesję debugowania) i telefon żony (tylko aktualizacje przez agenta) |

## Typowa praca

```bash
# agent: wdrożenie po zmianie w server/
python3 -m py_compile server/*.py
rsync -a server/ jmpc:.local/src/MagneticPlayer/server/
scp -q server/*.py server/*.sh jmpc:.local/share/magneticplayer/ && scp -q server/web/index.html jmpc:.local/share/magneticplayer/web/
ssh jmpc systemctl --user restart magneticplayer
ssh jmpc journalctl --user -u magneticplayer -n 50

# apka: telefony mają klucz treetank, więc aktualizacja = wydanie z CI (lokalny build ma klucz deweloperski,
# do emulatora). Tag → CI buduje i wystawia na GitHubie → agenci sami je pobierają (mp_appupdate, co 6 h i minutę
# po restarcie agenta). Od razu, bez czekania (albo przy trybie „manual”):
phone/tools/tag-release.sh            # po commicie i pushu; CI ~10 min
phone/tools/publish.sh --release latest
```

Testy API na PC, bez wyciągania tokenu na zewnątrz:

```bash
ssh jmpc 'T=$(cat .config/magneticplayer/cli-token); curl -s -H "Authorization: Bearer $T" localhost:8765/api/player/status'
```

Polecenia w sesji graficznej przez ssh wymagają `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus DISPLAY=:0`.

Użytkownik prosi o commit i push po skończonej zmianie (`git push origin main && git push ovh main`). Repo jest publiczne: tytuły filmów z dysku użytkownika, tokeny i inne prywatne szczegóły nie trafiają do commitów (przykłady: filmy z domeny publicznej). Kod i dokumentacja zmieniają się
razem.

## Emulator (gdy telefon jest zajęty)

Testy apki domyślnie na telefonie użytkownika; emulator tylko, gdy użytkownik potrzebuje telefonu. SDK timeprotectu
`~/.cache/tp-emu-sdk` (tylko odczyt, android-33), własny AVD `~/.cache/mp-emu-avd/mp.avd`, skrypt timeprotectu:

```bash
TP_EMU_AVD=$HOME/.cache/mp-emu-avd TP_EMU_NAME=mp TP_EMU_PORT=5556 TP_EMU_MEM=2048 TP_EMU_MEMMAX=4G TP_EMU_CPUS=8,9 \
  TP_EMU_SYSTEM='system-images;android-33;google_apis;x86_64' TP_EMU_LOG=$HOME/.cache/mp-emu.log \
  bash ~/projects/personal/timeprotect/tools/emulator.sh start   # … adb <polecenie> / stop
```

Na laptopie tylko z tymi limitami i gaszony po testach (jeden emulator naraz, nie równolegle z buildem APK).
Emulator i VirtualBox nie działają naraz: przy uruchomionej VM emulator pada z „KVM: entry failed, hardware error 0x0”.
Przed startem `VBoxManage controlvm <vm> savestate` (win11 tylko przez LOCK), po testach można je wznowić.
Wyjście z emulatora do LAN przez NAT działa (192.168.100.100:8765); wejście z LAN: `adb forward` + `socat`.
Win11 w VirtualBox: protokół `~/.cache/winvm/LOCK` (wspólny z timeprotect), po teście snapshot `przed-magneticplayer`.

## Zasady

- Dwa języki: angielski domyślnie, polski dla `pl*`. Apka: `res/values/strings_*.xml` (EN) i `res/values-pl/` (PL),
  słowa jak w `docs/` i obecnym UI. Agent: `T("en", "pl")` z `mp_i18n` (język z `Accept-Language`), strona: `tr()`
  i `data-i18n`, skrypty: `say "EN" "PL"` / `Say`. Nowy tekst dla użytkownika zawsze w obu językach.
- Sekrety: `phone/magneticplayer.keystore`, `phone/keystore.properties` i token są poza gitem. Hooki blokują dostęp
  do `.keystore` i `~/.ssh`: nie obchodź ich, tylko daj użytkownikowi polecenie (przez schowek, `xclip`).
- Root na PC: `ssh -t jmpc sudo …` (pkexec przez ssh nie działa). Lokalnie `pkexec`.
- Żona używa apki: każde wydanie (tag) trafia do wszystkich telefonów, bo agenci pobierają je sami. Użytkownik uznał
  wydania z CI za przetestowane, więc tag tylko z działającym kodem.
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
- **GIL a stare dyski:** w Pythonie 3.10 `DirEntry.stat()` (i `is_dir()` bez `d_type`) robi `stat()` bez zwolnienia GIL.
  Rozkręcający się dysk USB (ntfs-3g) zatrzymywał wtedy na ~3 s cały agent i apka pokazywała „jmpc śpi”. W agencie
  używaj `os.stat(e.path)` / `os.path.isdir(e.path)`.
- `systemctl --user restart magneticplayer` zabijał mpv (ten sam cgroup); dlatego mpv startuje przez `systemd-run --scope`.
- **Windows:** `powershell -c "irm …/ps | iex"` (też przez ssh) Defender blokuje jako `Commando.B!ml`; wklejone do okna
  PowerShell działa. Testy przez ssh: polecenie w pliku `.ps1` i `-File`.
  - mpv: `os.path.exists` na `\\.\pipe\…` bywa False przy działającym mpv (lista potoków: `socket_exists()`); nowe
    połączenie z potokiem trwa do 3 s, więc jest jedno trwałe; mpv nie kończy się po `quit`, dopóki klient trzyma potok.
  - ssh do win11 to sesja 0: mpv startuje, ale nie widać okna. Agent w sesji użytkownika: `schtasks /IT`.
  - Windows sam zakłada reguły zapory „Blokuj” dla bazowego `pythonw.exe` po „Anuluj”; `install.ps1` jako admin je usuwa.

## Otwarte sprawy

- **Telewizory, agent 1.2.0, wspólny Pilot: sprawdzone na sprzęcie** (jmpc NVENC, laptop VAAPI, „Przygotuj”,
  Chromecast z serwera i z telefonu, Samsung DLNA z napisami i przewijaniem, Pilot dla mpv/Chromecasta/DLNA,
  powiadomienia, głośność poza apką, mpv przeżywa restart agenta, Chromecast ze strony (przez API agenta),
  telefon jako źródło dla ekranu jmpc, odzyskiwanie DLNA po ubiciu apki). Zostało:
  - strona webowa w przeglądarce (karta Chromecasta po zmianie `volume_fixed`) — sprawdzone tylko API i składnia JS.
    Stronę da się testować Playwrightem z cache npx (`~/.npm/_npx/*/node_modules/playwright`) z
    `executablePath: "/usr/bin/google-chrome"` (jego własne przeglądarki są w innej wersji);
  - aktualizacja po powrocie apki na ekran (budzenie oznacza się jako sprawdzone samo przy zwykłym budzeniu z apki;
    przycisk „Sprawdź budzenie” usunięty jako zbędny);
  - Pilot odświeża się 4×/s, więc `uiautomator dump` często nie działa („could not get idle state”): przy testach zrzuty ekranu.

- Pomysł: zdalny dostęp spoza LAN (Tailscale/WireGuard, opisane w `docs/siec-i-bezpieczenstwo.md`).
