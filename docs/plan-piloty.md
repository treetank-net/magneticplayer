# Plan: Pilot bez agenta, rozgłaszanie „co gra”, powiadomienia i kafelek

Decyzje użytkownika (17.11.2026): osobne powiadomienie na każdy grający ekran (A), poprawna nazwa urządzenia
w kafelku systemowym (a, bez przenoszenia filmu z listy systemowej), rozgłaszanie puszczonych materiałów do innych
pilotów, a telefon ma sam szukać Chromecastów, bo „u teścia” nie będzie agenta.

## 1. Telefon sam widzi Chromecasty (bez agenta) — zrobione

Sprawdzone 17.11.2026 na „Duży pokój”: film puszczony przez agenta laptopa, Pilot pokazał go ze stanu wziętego
prosto z Chromecasta; pauza, +10 s, wznowienie i „Zakończ” działają. Rozpoznawanie modelu na telefonie
(eureka_info, profile z agenta) jeszcze nie.

- **Wykrywanie:** `NsdManager`, `_googlecast._tcp`. TXT: `id`, `fn`, `md`; adres i port z rozwiązania usługi.
  Działa tylko, gdy Pilot jest na ekranie (oszczędność baterii).
- **Stan i sterowanie:** własny klient protokołu Cast v2, tego samego, którego używa pychromecast.
  - Połączenie: TLS na `:8009` (certyfikat samopodpisany), ramki to 4 bajty długości i protobuf `CastMessage`
    (kodowany ręcznie, bez biblioteki).
  - Kanały: `tp.connection` (CONNECT), `tp.heartbeat` (PING/PONG), `receiver` (GET_STATUS: aplikacja i głośność,
    SET_VOLUME, STOP), `media` (GET_STATUS, PLAY, PAUSE, SEEK).
- **Pilot:** chip Chromecasta pojawia się, gdy na którymś coś gra, niezależnie od nadawcy. Kolejność źródeł:
  1. sesja Cast SDK tego telefonu (pełne powiadomienie Google);
  2. własny klient Cast v2;
  3. agent (zostaje jako zapas, np. gdy telefon nie dostanie TLS).
- **Kim jest Chromecast:** telefon pyta `https://<ip>:8443/setup/eureka_info` tak jak agent i trzyma własny rejestr.
  Tabelę profili bierze z agenta (`/api/renderers/profiles`) i zapamiętuje ją, żeby działała też poza domem.

## 2. Rozgłaszanie „co gra” (multicast)

- UDP multicast `239.255.77.77:47700`, TTL 1 (tylko ta sieć), JSON:

  ```json
  {"mp": 1, "type": "playing", "from": {"kind": "phone", "id": "<losowe id instalacji>", "name": "Pixel 8a"},
   "target": {"kind": "dlna|cast|screen", "id": "...", "name": "[TV] UE43J5500", "ctl": {...}},
   "title": "...", "state": "PLAYING|PAUSED|BUFFERING", "pos": 123.4, "dur": 5400, "ts": 1794929824}
  ```

- `ctl` mówi, jak sterować bez pytania nadawcy:
  - DLNA: adresy AVTransport i RenderingControl;
  - Chromecast: host i port;
  - ekran: adres agenta i klucz serwera, a token każdy pilot ma swój.
- Nadawca wysyła co 3 s, dopóki gra, a na koniec raz `"type": "stopped"`. Słuchacz zapomina wpis po 10 s ciszy.
  `"type": "who"` prosi wszystkich o natychmiastową odpowiedź (świeżo otwarty Pilot nie czeka 3 s).
- Nadają: telefony (DLNA i Chromecast puszczone z nich) oraz agent (mpv i Chromecast puszczony przez agenta).
  Słuchają: telefony (gdy Pilot jest na ekranie albo gra powiadomienie) i agent, który przekazuje listę stronie
  (`GET /api/announce`).
- Bez sekretów w pakiecie: tokenu nie ma, sterowanie DLNA i Chromecastem w LAN i tak nie wymaga logowania.
- Android: uprawnienie `CHANGE_WIFI_MULTICAST_STATE` i `WifiManager.MulticastLock` na czas słuchania.

## 3. Osobne powiadomienie na każdy grający ekran (A)

- `RemoteSessionService` trzyma osobną `MediaSession` na każdy grający cel (`id` = rodzaj i urządzenie).
  Sesja powstaje, gdy cel zaczyna grać (ze stanu Pilota albo z rozgłoszenia), i znika po ~10 s ciszy.
- Przyciski głośności poza apką Android daje jednej sesji, zwykle ostatnio aktywnej.
- Chipy na Pilocie: tylko cele, na których coś gra, plus ekran domyślny z napisem „nic nie gra”.

## 4. Kafelek z nazwą urządzenia (a)

- Apka zgłasza się jako dostawca tras (`MediaRoute2ProviderService`) z trasą na każdy grający cel. Apka wybiera ją
  u siebie (`MediaRouter2.transferTo`), a identyfikator kontrolera trafia do sesji
  (`DeviceInfo.setRoutingControllerId`). Wtedy System UI pokazuje nazwę zamiast „Ten telefon”.
- Najpierw próba na Pixelu (Android 17): czy system pokaże naszą nazwę. Jeśli nie, zostaje opis, dlaczego.

## Kolejność

1. Klient Cast v2 i NSD w telefonie, Pilot bez agenta.
2. Rozgłaszanie (telefon i agent), Pilot pokazuje cudze odtwarzanie.
3. Osobne sesje i powiadomienia.
4. Kafelek.

Każdy krok: test na sprzęcie, commit i push.
