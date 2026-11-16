# Plan: wiele serwerów (etap 1)

Stan: plan, nic z tego nie jest jeszcze zrobione. Po wdrożeniu kawałka opis przechodzi do `architektura.md`
i `api.md`, a stąd znika.

Makiety ekranów apki: [ux-wiele-serwerow.html](ux-wiele-serwerow.html) (otwórz w przeglądarce).

## Cel

Dziś apka zna jeden serwer (jmpc), który robi wszystko: pobiera torrenty, serwuje pliki i wyświetla w mpv.
Chcemy kilku serwerów w LAN (np. jmpc, drugi Mint, laptop, komputer w garażu, później cudzy telefon jako serwer
plików) i wybierania w apce, z którego oglądamy i na którym ekranie.

Poza etapem 1: wykrywanie w sieci (mDNS), Docker/Synology, Chromecast, Android jako serwer, instalatory
Windows/Mac. Etap 1 ma je umożliwić, a nie zrobić.

## Role zamiast „serwera”

Każdy agent zgłasza, co umie. Ten sam kod agenta, różnią się tylko włączone funkcje.

| rola | co robi | dziś |
|---|---|---|
| **pliki** | torrenty i/lub biblioteka, strumień HTTP z Range, napisy, X-Ready-End | jmpc |
| **ekran** | odtwarza w mpv URL podany z zewnątrz, pilot przez `/api/player/*` | jmpc |
| **pilot** | steruje ekranem | telefon, przeglądarka |

Serwer może być tylko ekranem (laptop pod telewizorem bez dysku z filmami), tylko plikami (komputer w garażu bez
monitora) albo jednym i drugim (jmpc). Apka łączy dowolny serwer plików z dowolnym ekranem.

## Uprawnienia

Wszystkie sparowane urządzenia mają te same uprawnienia (decyzja: domownik = admin). Zmienia się tylko to, że
**każde urządzenie ma własny token** zamiast jednego wspólnego, żeby dało się odciąć jedno (zgubiony telefon) bez
przeparowania reszty.

## Agent: `GET /api/info` (rozszerzenie istniejącego)

Dziś zwraca `hostname`, `version`, `backend`, `keep_awake`, `iface`, `ip`, `mac`, `wake_on`, `wireless`.
Dochodzi:

- `id`: stały UUID z `config.json` (generowany przy pierwszym starcie). Apka rozpoznaje serwer po nim, nie po IP.
- `name`: nazwa do wyświetlenia (domyślnie hostname, zmienialna w configu).
- `api`: numer wersji API, żeby apka wiedziała, czego się spodziewać.
- `features`:
  - `torrent`: nazwa klienta (`deluge`, `transmission`) albo brak;
  - `library`: czy są katalogi biblioteki;
  - `player`: czy jest mpv i sesja graficzna (bez tego serwer nie jest ekranem);
  - `subs_search`: czy działa subliminal;
  - `unlock`: czy umie zdjąć ekran blokady;
  - `apk`: czy serwuje aktualizacje apki;
  - `wake_others`: czy wyśle magic packet w imieniu apki (`POST /api/wake`).
- `power`: `suspend`, `poweroff` (true/false) i `wake`:
  `{"method": "wol", "mac", "confidence": "yes" | "maybe" | "no", "reason"}`.
  - Linux: kabel + `ethtool` `Wake-on: g` → `maybe` (BIOS może mieć WoL wyłączony, tego nie widać);
    Wi-Fi → `no` („Wi-Fi”); brak `ethtool`/`nmcli` → `maybe` z powodem.
  - Inne systemy później: Windows `powercfg /devicequery wake_armed`, Mac `pmset -g` (`womp`).
  - `yes` agent nie zgłasza nigdy. „Potwierdzone” wie tylko apka, gdy raz udało się obudzić.

Agent bez `/api/info` w nowym formacie (stara wersja) apka traktuje jak dzisiejszy jmpc: wszystkie funkcje.

### Adaptery platformy

Linuksowe kawałki agenta (`systemd-inhibit`, `systemctl suspend/poweroff`, `loginctl`, `/proc`, `xset`, `nmcli`,
`ethtool`) idą za jedną warstwę: „blokuj usypianie”, „uśpij”, „wyłącz”, „odblokuj ekran”, „stan WoL”.
Gdy czegoś nie ma, funkcja wypada z `features`/`power` zamiast sypać błędem. W etapie 1 tylko Linux, ale z
podziałem, który pozwoli dopisać Windows/Mac.

## Ekran bez plików: jak ekran dostaje film z innego serwera

Dziś `POST /api/player/open` z `ref` otwiera plik lokalny. Dochodzi wariant z URL:

1. Apka prosi serwer plików o **bilet**: `POST /api/media/ticket` z `ref` → URL strumienia i URL-e napisów
   z biletem w parametrze (`?ticket=…`), ważne np. 12 h i tylko dla tego jednego pliku.
2. Apka wysyła ekranowi `POST /api/player/open` z `url`, `subs[]`, `title`, `start`.
3. mpv na ekranie czyta po HTTP z serwera plików (X-Ready-End i czekanie na kawałki działają tak samo jak dla
   telefonu).

Dzięki biletowi ekran nie dostaje tokenu serwera plików i nie musi być z nim sparowany. Gdy plik i ekran to ten
sam serwer, zostaje dzisiejsza ścieżka (plik lokalny).

Pozycję oglądania zapisuje apka (już dziś `Settings.position` na telefonie), czytając `/api/player/status`
ekranu, więc nie zależy od tego, kto serwuje plik.

## Apka: przypadki

1. **Lista serwerów** w ustawieniach: dodaj (QR albo adres + kod), zmień nazwę, usuń. Przy każdym stan:
   działa / śpi (da się obudzić) / niedostępny.
2. **Wybór serwera plików**: przełącznik u góry (jak konta). Torrenty i Biblioteka pokazują wybrany serwer.
   Wspólna biblioteka ze wszystkich serwerów: później.
3. **Wybór ekranu**: „Na komputerze” zamienia się w „Odtwórz na…” z listą serwerów z `player` (plus „ten
   telefon”). Pilot steruje wybranym ekranem. Ostatni wybór jest zapamiętany.
4. **Serwer śpi**: zamiast błędu „Garaż śpi. Obudzić?”, potem czekanie, aż odpowie.
5. **Budzenie**: najpierw magic packet z telefonu (działa tylko w tej samej podsieci), potem przez dowolny
   działający serwer z `wake_others` (np. gdy garaż jest za powerline/innym routerem).
6. **Przyciski zasilania** wg `power`:

   | stan | przycisk |
   |---|---|
   | `suspend` + budzenie potwierdzone | „Uśpij” |
   | `suspend` + `maybe` | „Uśpij” + jednorazowe ostrzeżenie „nie sprawdzono, czy da się go obudzić” |
   | `suspend` + `no` | „Uśpij” z ⚠ i potwierdzeniem „nie obudzisz go zdalnie (powód), trzeba podejść” |
   | brak `suspend` | brak przycisku |
   | „Wyłącz” | zawsze z potwierdzeniem; przy braku budzenia to samo ostrzeżenie |

7. **Magnet z innej apki**: jeden serwer z `torrent` → od razu tam; kilka → pytanie z „zapamiętaj”.
8. **„Na telefonie”**: wpis zna swój serwer. Bez sieci gra jak dziś; „Wznów” wymaga tamtego serwera.
9. **Zmiana IP**: apka sprawdza `id` przy połączeniu. Inny `id` pod starym adresem → nie wysyła tokenu, prosi
   o nowy adres albo ponowne zeskanowanie QR.
10. **Aktualizacje apki**: z dowolnego serwera z `apk`, najwyższa wersja. `publish.sh` dalej publikuje na jmpc.
11. **Migracja**: dzisiejszy serwer staje się serwerem nr 1 i **zachowuje klucze bez prefiksu** (SimpleCache nie
    zmienia nazw kluczy, więc inaczej przepadłby bufor i pobrane filmy; to samo pozycje `pos:`, `cw:`, `cu:`).
    Kolejne serwery mają prefiks `<id>:` w `MediaRef.key()`.

## Parowanie

Zasada: **serwer nikogo nie wpuszcza sam z siebie.** Zaufanie wychodzi od kogoś, kto ma dostęp do maszyny
(shell, SSH) albo od już sparowanego urządzenia. Brak „okna parowania” dla każdego w sieci, więc serwer bez
ekranu nie musi niczego zatwierdzać.

- **Pierwsze urządzenie**: `install.sh` na końcu drukuje w terminalu QR (`qrencode -t ANSI`) i krótki kod;
  później to samo daje `magneticplayer_agent.py --pair`. Kod jest jednorazowy i ważny kilka minut.
- **Kolejne urządzenia**: „Sparuj kolejne urządzenie” w apce albo w przeglądarce generuje taki sam jednorazowy kod
  (dziś ten QR zawiera wspólny token).
- **Wymiana**: `POST /api/pair` z kodem i nazwą urządzenia → własny token urządzenia. Serwer trzyma listę:
  nazwa, data sparowania, ostatnie połączenie.
- **Odcięcie urządzenia** (zgubiony telefon): lista urządzeń w apce/przeglądarce z przyciskiem „Odbierz dostęp”
  i `--devices` / `--revoke` w agencie. Dopóki serwery są tylko w LAN, telefon spoza domu i tak się nie połączy;
  przy dostępie zdalnym odcinanie staje się kluczowe.
- **Stary wspólny token** działa dalej jako „stare urządzenie”, dopóki telefony nie zostaną przeparowane;
  potem wyłączany jednym poleceniem.
- **Później**: parowanie nowego telefonu ze wszystkimi serwerami naraz. Apka, która jest sparowana z kilkoma
  serwerami, prosi każdy o jednorazowy kod i pokazuje jeden QR ze wszystkimi.

## Na czym testujemy

- jmpc: pliki + ekran (jak dziś).
- drugi Mint: osobno jako tylko ekran (bez Deluge i biblioteki) i jako tylko pliki.
- telefon jako serwer plików: później (Android jako serwer), ale format biletów i `/api/info` ma to od razu
  przewidywać.

## Kolejność prac

1. Agent: `id`/`name`/`api`/`features`/`power` w `/api/info`, adaptery platformy (Linux).
2. Agent: tokeny per urządzenie, `/api/pair`, `--pair`, `--devices`, `--revoke`; zgodność ze starym tokenem.
3. Apka: lista serwerów, przełącznik, stan i budzenie, przyciski zasilania wg `power`, migracja kluczy.
4. Agent + apka: bilety, `player/open` z URL, „Odtwórz na…”.
5. Web: to samo co apka w zakresie, który ma sens w przeglądarce.
