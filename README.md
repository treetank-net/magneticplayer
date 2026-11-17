# MagneticPlayer

Magnet link na telefonie → pobieranie w Deluge na domowym komputerze (budzonym przez Wake-on-LAN) → oglądanie
na telefonie albo na komputerze, z telefonem jako pilotem. Działa w sieci domowej (LAN).

```
server/   agent na komputer z Linux Mint (Python ze standardową biblioteką + subliminal w venv) i instalatory
phone/    apka na Androida 8+ (Kotlin, Jetpack Compose, Media3/ExoPlayer + dekoder FFmpeg do AC3/E-AC3/DTS)
docs/     dokumentacja
```

## Co umie

- **Magnety:** kliknięty `magnet:` albo „Udostępnij” z dowolnej apki trafia do Deluge. Śpiący komputer jest
  najpierw budzony (WoL). Domyślnie pobieranie sekwencyjne, żeby dało się oglądać w trakcie.
- **Ile da się już obejrzeć:** dla każdego filmu część pobrana ciągiem od początku, w procentach i minutach.
- **Akcja po pobraniu** (przy zleceniu albo później): nic, zatrzymaj torrent albo uśpij komputer.
  Do tego historia zleceń wysłanych z telefonu z opcją „Pobierz znowu”.
- **Biblioteka:** wybrane katalogi komputera, tylko filmy i muzyka.
- **Odtwarzanie:**
  - na telefonie (ExoPlayer, napisy obok filmu i wbudowane), w VLC na telefonie albo w mpv na komputerze;
  - przekazanie filmu z telefonu na komputer od tego samego miejsca.
- **Pilot do mpv:**
  - pauza, przewijanie, pełny ekran, głośność (też klawiszami telefonu), stop;
  - wybór ścieżek audio, napisów i wideo, przesunięcie napisów, włączenie ekranu komputera.
- **Napisy:** wyszukiwanie po hashu pliku i po nazwie przez subliminal:
  - źródła: OpenSubtitles.com (sam klucz API wystarcza), BSPlayer, Podnapisi, Gestdown/Addic7ed, NapiProjekt;
  - zapisywane obok filmu jako `<film>.<język>.srt`;
  - jeśli film właśnie gra w mpv, napisy włączają się od razu.
- **Usypianie i budzenie komputera** przyciskami w apce. Komputer nie zaśnie sam, gdy coś się pobiera albo gra.
- **Klient webowy** dla laptopa albo tabletu bez Androida, pod adresem `http://<komputer>:8765/`: torrenty, biblioteka,
  pilot (także z klawiatury), napisy, odtwarzanie w przeglądarce i link do VLC.
- **Kolejne telefony:**
  - QR „Sparuj kolejne urządzenie” i „Wyślij apkę” (WhatsApp itp.);
  - aktualizacje publikowane na komputerze, które apki same proponują.

## Szybki start

```bash
# komputer (lokalnie; część rootowa przez pkexec)
bash server/install.sh
# telefon
phone/tools/build-apk.sh && adb install -r phone/out/MagneticPlayer.apk
```

`install.sh` na końcu wypisuje link parowania `magneticplayer://pair?...`. Otwórz go na telefonie albo wklej
w apce: Ustawienia → „Wklej link parowania”.

## Dokumentacja

- [docs/instalacja-i-utrzymanie.md](docs/instalacja-i-utrzymanie.md): instalacja przez SSH, aktualizacje
  agenta i apki, kolejne telefony, klucz podpisu, logi, typowe problemy.
- [docs/architektura.md](docs/architektura.md): komponenty, przepływy (magnet, oglądanie, napisy, usypianie),
  pliki i konfiguracja.
- [docs/api.md](docs/api.md): HTTP API agenta.
- [docs/siec-i-bezpieczenstwo.md](docs/siec-i-bezpieczenstwo.md): założenie „tylko LAN”, token oraz opcje
  na zdalny dostęp w przyszłości.
- [docs/plan-wiele-serwerow.md](docs/plan-wiele-serwerow.md): etap 1 (wdrożony): kilka serwerów, osobne
  serwery plików i ekranu, tokeny per urządzenie.

## Licencja

MIT, patrz [LICENSE](LICENSE). Projekt nie dostarcza żadnych treści: co pobierasz i komu udostępniasz przez
BitTorrent, jest Twoją odpowiedzialnością.
