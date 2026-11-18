# MagneticPlayer

Magnet link na telefonie → pobieranie na domowym komputerze (budzonym przez Wake-on-LAN) → oglądanie na telefonie,
w przeglądarce, na ekranie komputera z telefonem jako pilotem albo na telewizorze (Chromecast, DLNA). Wszystko
zostaje w sieci domowej (LAN): bez chmury i bez konta.

[English version](README.md)

```
server/   agent na komputer (Linux albo Windows 10/11; Python ze standardową biblioteką + dodatki w venv) i instalatory
phone/    apka na Androida 8+ (Kotlin, Jetpack Compose, Media3/ExoPlayer + dekoder FFmpeg do AC3/E-AC3/DTS)
docs/     dokumentacja
```

## Co umie

- **Magnety:** kliknięty `magnet:` albo „Udostępnij” z dowolnej apki trafia do Deluge (albo Transmission) na
  komputerze. Śpiący komputer jest najpierw budzony. Domyślnie pobieranie sekwencyjne, żeby dało się oglądać w trakcie.
- **Ile da się już obejrzeć:** dla każdego filmu część pobrana ciągiem od początku, w procentach i minutach.
- **Akcja po pobraniu** (przy zleceniu albo później): nic, zatrzymaj torrent albo uśpij komputer.
  Do tego historia zleceń wysłanych z telefonu z opcją „Pobierz znowu”.
- **Biblioteka:** wybrane katalogi komputera, tylko filmy i muzyka, z tagami (filmy, seriale, bajki, kopia),
  widocznością dla urządzeń, wyszukiwarką i okładkami. Magnet jest rozpoznawany i pobierany od razu do właściwego folderu.
- **Dyski i porządki:** zwolnienie pełnego dysku przez przeniesienie na inny (torrenty przez klienta, więc dalej się
  seedują), porządki według rozpoznania, duplikaty, kopia folderu na dysk zapasowy. Profile przechowywania zmniejszają
  wybrane foldery na życzenie (bezstratne porządki w audio albo HEVC 1080p/720p/480p), zawsze po podglądzie i z koszem.
- **Asystent AI (MCP):** np. Claude Code na domowym komputerze łączy się z `http://<komputer>:8765/mcp`; zatwierdzasz go
  na telefonie i może przeglądać oraz porządkować bibliotekę przez te same podglądy.
- **Odtwarzanie:**
  - na telefonie (ExoPlayer, napisy obok filmu i wbudowane), w VLC na telefonie albo w mpv na komputerze;
  - przekazanie filmu z telefonu na komputer od tego samego miejsca;
  - telewizory Chromecast i DLNA, z napisami; filmy, których telewizor nie odtworzy, można najpierw przekodować
    („Przygotuj”, sprzętowo, gdzie się da);
  - pobieranie filmów na telefon do oglądania offline i wysyłanie ich z telefonu na komputer.
- **Pilot** do mpv, Chromecasta i DLNA: pauza, przewijanie, głośność (też klawiszami telefonu), ścieżki audio,
  napisów i wideo, przesunięcie napisów, włączenie ekranu komputera.
- **Napisy:** wyszukiwanie po hashu pliku i po nazwie przez subliminal (OpenSubtitles.com, BSPlayer, Podnapisi,
  Gestdown/Addic7ed, NapiProjekt), zapisywane obok filmu jako `<film>.<język>.srt`; jeśli film właśnie gra w mpv,
  włączają się od razu.
- **Usypianie i budzenie komputera** przyciskami w apce. Komputer nie zaśnie sam, gdy coś się pobiera albo gra.
- **Klient webowy** dla laptopa albo tabletu pod adresem `http://<komputer>:8765/`: torrenty, biblioteka, pilot
  (także z klawiatury), napisy, odtwarzanie w przeglądarce, Chromecast.
- **Kilka komputerów:** serwer plików i ekran mogą być różnymi maszynami; film z jednego komputera można puścić na
  ekranie drugiego.
- **Więcej osób:**
  - sparowanie kolejnego telefonu albo przeglądarki kodem QR lub linkiem; domownik ma pełny dostęp, gość tylko
    ogląda (na dzień, tydzień albo bez limitu);
  - niesparowane urządzenie z sieci domowej może poprosić o dostęp, a telefon domownika dostaje powiadomienie
    z „Gość (dzień)” / „Domownik” / „Odrzuć”;
  - „Wyślij apkę” przez WhatsAppa itp.; komputer sam pobiera nowe wydania apki z GitHuba (albo tylko
    to, co opublikujesz ręcznie), a apki proponują aktualizację.
- **Instalacja na innym komputerze** z apki: telefon pokazuje jednolinijkowe polecenie (`curl … | bash` na Linuksie,
  `irm … | iex` w PowerShellu na Windowsie). Agent pochodzi z istniejącego serwera albo z samego telefonu, a nowy
  komputer sam paruje się z telefonem. Agenty same aktualizują się z podpisanych wydań, gdy nikt nie
  ogląda, i wracają do poprzedniej wersji, jeśli nowa się nie uruchomi.

## Szybki start

1. Zainstaluj apkę: APK jest w [wydaniach na GitHubie](https://github.com/treetank-net/magneticplayer/releases).
2. Zainstaluj agenta na komputerze, ze źródeł:

   ```bash
   bash server/install.sh            # Linux: pełna instalacja (Deluge, Wake-on-LAN, usypianie), część rootowa przez pkexec
   LITE=1 bash server/install.sh     # Linux: instalacja lekka, bez roota (biblioteka, ekran, napisy; bez torrentów)
   ```

   Na Windowsie 10/11 (instalacja lekka), w PowerShellu: `powershell -ExecutionPolicy Bypass -File server\install.ps1`.

   Albo, gdy apka już jest: Ustawienia → „Zainstaluj na innym komputerze” → „Ten telefon” i wklej na komputerze
   pokazane polecenie.
3. Sparuj: `install.sh` na końcu wypisuje link `magneticplayer://pair?...`. Otwórz go na telefonie albo wklej w apce
   (Ustawienia → „Dodaj serwer” → „Wklej link parowania”).

Własny build apki: `phone/tools/build-apk.sh` (Docker z obrazem Android SDK; podpis lokalnym kluczem deweloperskim)
i `adb install -r phone/out/MagneticPlayer.apk`.

## Bezpieczeństwo

Projekt zakłada zaufaną sieć domową: zwykłe HTTP w LAN, token per urządzenie po sparowaniu, goście ograniczeni do
listy dozwolonych endpointów, prośby o dostęp tylko z adresów prywatnych. Nie wystawiaj portu agenta do internetu.
Szczegóły: [docs/network-and-security.md](docs/network-and-security.md).

## Dokumentacja

Dokumentacja jest po angielsku:

- [docs/install-and-maintenance.md](docs/install-and-maintenance.md): instalacja (też przez SSH, z innego serwera,
  z telefonu, Windows), aktualizacje, wydania, klucze podpisu, logi, typowe problemy.
- [docs/architecture.md](docs/architecture.md): komponenty, przepływy (magnet, oglądanie, napisy, usypianie),
  pliki i konfiguracja.
- [docs/api.md](docs/api.md): HTTP API agenta.
- [docs/network-and-security.md](docs/network-and-security.md): założenie „tylko LAN”, tokeny, role.
- [docs/devices.md](docs/devices.md): Chromecast, DLNA i Samsung: wykrywanie i profile kodeków.
- [docs/plan-multi-server.md](docs/plan-multi-server.md): etap 1 (wdrożony): kilka serwerów, osobne
  serwery plików i ekranu, tokeny per urządzenie.

## Wydania

Tag `v1.RRMMDD.GGMM` na `main` (`phone/tools/tag-release.sh`) uruchamia GitLab CI: build i podpis APK, paczka agenta
z podpisanym manifestem i wydanie na GitHubie. Instalując agenta na innym komputerze, apka używa nowszej podpisanej
paczki z najnowszego wydania. Źródła: [gitlab.com/treetank/magneticplayer](https://gitlab.com/treetank/magneticplayer),
lustro: [github.com/treetank-net/magneticplayer](https://github.com/treetank-net/magneticplayer).

## Licencja

MIT, patrz [LICENSE](LICENSE). Projekt nie dostarcza żadnych treści: co pobierasz i komu udostępniasz przez
BitTorrent, jest Twoją odpowiedzialnością.
