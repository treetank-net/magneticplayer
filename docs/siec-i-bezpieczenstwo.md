# Sieć i bezpieczeństwo

## Założenie: tylko LAN

MagneticPlayer jest pomyślany do domowej sieci:
- **HTTP bez TLS.** Token i strumienie idą otwartym tekstem, a apka ma `usesCleartextTraffic`. W domowym Wi-Fi to
  akceptowalne, w obcej sieci już nie.
- **Jeden wspólny token.** Kto go ma, może dodawać torrenty, sterować mpv, usypiać komputer i czytać pliki z
  katalogów biblioteki oraz pobierania (tylko filmy, muzykę i napisy; agent nie wychodzi poza te katalogi).
- **Token jest w kodzie QR.** QR „Sparuj kolejne urządzenie” i link parowania pokazuj tylko domownikom.
  Zmiana tokenu: nowa wartość `token` w `~/.config/magneticplayer/config.json`, restart usługi i ponowne
  sparowanie telefonów.
- **Firewall.** `install-root.sh` otwiera port 8765 w ufw tylko dla `192.168.0.0/16` i `10.0.0.0/8`.
- **deluge-web** słucha tylko na `127.0.0.1:8112`, więc z sieci jest dostępny wyłącznie przez agenta.
- **Wake-on-LAN** to broadcast w lokalnej sieci, więc spoza niej nie zadziała.

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
