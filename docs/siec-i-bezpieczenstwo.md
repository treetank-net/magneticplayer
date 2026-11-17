# Sieć i bezpieczeństwo

## Założenie: tylko LAN

MagneticPlayer jest pomyślany do domowej sieci:
- **HTTP bez TLS.** Token i strumienie idą otwartym tekstem, a apka ma `usesCleartextTraffic`. W domowym Wi-Fi to
  akceptowalne, w obcej sieci już nie.
- **Token na urządzenie.** Każdy telefon i przeglądarka dostaje przy parowaniu własny token. Agent trzyma tylko
  jego skrót SHA-256 (`~/.config/magneticplayer/devices.json`). Wszystkie urządzenia mają te same prawa: dodawanie
  torrentów, sterowanie mpv, usypianie i czytanie plików z katalogów biblioteki oraz pobierania (tylko filmy,
  muzykę i napisy; agent nie wychodzi poza te katalogi).
- **Parowanie.** Kod parowania (6 znaków, jednorazowy, 10 minut) tworzy tylko już sparowane urządzenie albo ktoś
  z dostępem do komputera (`--pair`, a po instalacji `install.sh`). Samo bycie w sieci nie wystarczy. Po 10
  złych próbach w minutę agent odmawia.
- **Zgubiony telefon.** Z dowolnego innego urządzenia: Ustawienia → serwer → urządzenie → „Odbierz”. Albo na
  komputerze `magneticplayer_agent.py --devices` i `--revoke ID`.
- **Stary wspólny token** z `config.json` działa dalej (urządzenie „legacy”), żeby starsze apki nie straciły
  dostępu. Gdy wszystkie urządzenia są sparowane na nowo, wyłącz go („Wyłącz” na liście urządzeń).
- **Bilety.** Ekran (mpv na innym komputerze) dostaje adres filmu z biletem HMAC na jeden plik, ważnym 12 h,
  zamiast tokenu. Bilet nie działa na API.
- **Odblokowanie ekranu.** Przy `auto_unlock: true` (domyślnie) każdy z tokenem zdejmie ekran blokady komputera,
  a wtedy osoba siedząca przy nim ma otwarty pulpit. Żeby tego nie było, ustaw `"auto_unlock": false` w
  `config.json` i zrestartuj usługę. Został wtedy tylko jawny `POST /api/system/unlock`, którego apka nie używa.
- **Kod parowania jest w kodzie QR.** QR „Sparuj kolejne urządzenie” działa raz i wygasa po 10 minutach, ale
  w tym czasie pokazuj go tylko domownikom.
- **Firewall.** `install-root.sh` otwiera port 8765 w ufw tylko dla `192.168.0.0/16` i `10.0.0.0/8`.
- **deluge-web** słucha tylko na `127.0.0.1:8112`, więc z sieci jest dostępny wyłącznie przez agenta.
- **Wake-on-LAN** to broadcast w lokalnej sieci, więc spoza niej nie zadziała.

## Goście

Gość dostaje zaproszenie z telefonu domownika: Ustawienia → „Sparuj kolejne urządzenie” → „Gość” → „Wyślij
zaproszenie” (komunikatorem). W treści jest adres strony z kodem (`http://<serwer>:8765/#p=KOD`) i link dla apki.
Kod działa raz, przez dobę, a sam dostęp przez dzień, tydzień albo bez limitu. Działa tylko w domowej sieci, jak
wszystko tutaj. Gość ogląda i włącza filmy, ale nie zmienia niczego na serwerze (lista w `docs/api.md`).
Domownik odbiera dostęp w szczegółach serwera (lista urządzeń).

### Prośba o dostęp

Bez zaproszenia: niesparowana przeglądarka w domowej sieci wpisuje nazwę i dotyka „Poproś o dostęp” (strona
`http://<serwer>:8765/`). Telefon domownika z otwartą apką sprawdza prośby co ~15 s i pokazuje powiadomienie
„X prosi o dostęp” z przyciskami „Gość (dzień)”, „Domownik”, „Odrzuć”. Po akceptacji przeglądarka dostaje zwykły
kod parowania z wybraną rolą i paruje się sama.

Zabezpieczenia: prośba tylko z adresu prywatnego, jedna na adres, najwyżej 5 oczekujących i 10 na minutę, wygasa po
5 min. Wynik odbiera tylko ten, kto ma sekret z odpowiedzi na prośbę, a kod oddajemy raz. Nazwa to tylko podpis od
proszącego: domownik decyduje, czy rozpoznaje urządzenie. Bez akceptacji nic nie daje dostępu, a gość nie widzi
próśb (`403`). Apka nie sprawdza próśb w tle, więc przy zamkniętej apce prośba czeka, aż ktoś ją otworzy.

## Zdalny dostęp: opcje na przyszłość

Nie wystawiaj portu 8765 na routerze. Bez TLS i przy jednym tokenie to proszenie się o kłopoty. Rozsądne
drogi, od najprostszej:

1. **Tailscale albo inny WireGuard mesh** na komputerze i telefonie. Apka działa bez zmian, w Ustawieniach podajesz
   adres z sieci Tailscale (`100.x.y.z` albo MagicDNS). Ruch jest szyfrowany, a nic nie jest wystawione na świat.
   Drobna zmiana w apce: przełączanie adresu „dom / zdalnie” albo próbowanie obu.
2. **WireGuard na routerze** (jeśli go obsługuje). Telefon łączy się do domowej sieci i wszystko działa jak
   w domu, łącznie z WoL, bo router jest w LAN-ie.
3. **Reverse proxy z TLS** (Caddy, Cloudflare Tunnel) przed agentem. Więcej konfiguracji, a do tego sensowne byłyby
   tokeny per urządzenie. Bez wyraźnej potrzeby nie warto.

### Budzenie zdalnie

Magic packet nie przejdzie przez internet. Uśpiony komputer nie odpowiada też w Tailscale, a na wybudzonym
tunel wstaje z opóźnieniem. Potrzebne jest coś, co zawsze działa w domowej sieci i wyśle pakiet za telefon:
- router z WoL (OpenWrt: `etherwake`, wiele routerów ma to w panelu);
- Raspberry Pi albo inne urządzenie 24/7 w Tailscale z małym przekaźnikiem WoL (do dopisania: endpoint
  `/wake`, który wysyła magic packet do MAC komputera);
- przy opcji 2 (VPN na routerze) apka może wysyłać magic packet sama, o ile router przepuszcza broadcast z VPN.
  Często nie przepuszcza.

### Oglądanie zdalnie

Strumień idzie w oryginalnej jakości bez transkodowania, a filmy 1080p x265 mają 3–10 Mb/s. Przez LTE zwykle
wystarczy, ale zależy to od uploadu domowego łącza.
