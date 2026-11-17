# Przesyłanie pobranych filmów z telefonu na serwer

## Po co

Film pobrany przeglądarką na telefonie (np. u znajomego, z linku) ma trafić do Biblioteki na komputerze bez
pamiętania o tym. Apka zauważa nowy plik, od razu pyta w powiadomieniu, a samo przesłanie robi dopiero wtedy, gdy
telefon widzi serwer w Wi-Fi, np. wieczorem po powrocie do domu.

## Jak to działa

1. **Wykrywanie** (`Uploads.kt`): zlecenie WorkManagera z wyzwalaczem `addContentUriTrigger(MediaStore.Video…, true)`.
   Działa przy zamkniętej apce. Odpala się raz, więc po każdym uruchomieniu zakłada następne (`APPEND_OR_REPLACE`),
   a start apki (`MainActivity.onCreate` → `Uploads.onAppStart`) zakłada je, gdyby zniknęło (`KEEP`). Gdy proces żyje,
   ten sam przegląd robi też `ContentObserver` (3 s po ostatniej zmianie).
2. **Co się liczy:** wideo z `RELATIVE_PATH` zaczynającym się od `Download/` (aparat w `DCIM/` jest pomijany),
   `IS_PENDING = 0` (przeglądarka skończyła), co najmniej 1 MB, `DATE_ADDED` od włączenia opcji (przy włączeniu
   apka nie pyta o stare pliki) i nie starsze niż 7 dni. Pytane pliki apka pamięta po `_ID`. Na Androidzie 8–9
   (bez `RELATIVE_PATH`) sprawdza `DATA LIKE '%/Download/%'`.
3. **Pytanie:** „Pobrano *nazwa*. *rozmiar*. Przesłać na *serwer*?” z przyciskami „Prześlij” i „Prześlij i usuń
   z telefonu”. W powiadomieniu nie da się zrobić checkboxa, dlatego są dwa przyciski.
4. **Zlecenie** (`UploadWorker`): unikalne na plik (`upload:<id z MediaStore>`), tylko w sieci bez limitu
   (`NetworkType.UNMETERED`). Najpierw `ping` serwera. Gdy nie odpowiada (telefon poza domem), powiadomienie
   „Czeka na *serwer* — prześle się, gdy telefon będzie w jego sieci” i kolejna próba za 15 min.
5. **Przesyłanie:** `GET /api/upload/<id>` mówi, ile serwer już ma, pusty `PUT` sprawdza miejsce (507) i zgodność,
   potem kawałki po 64 MB (`setFixedLengthStreamingMode`, plik czytany strumieniem z deskryptora, bez ładowania do
   pamięci). Zerwane połączenie zostawia na serwerze to, co doszło, a następna próba ciągnie od tego miejsca.
   W trakcie działa powiadomienie z postępem i „Anuluj” (usługa pierwszoplanowa `dataSync`, jeśli system pozwoli).
6. **Usuwanie:** plik należy do przeglądarki, więc apka może go tylko zaproponować do usunięcia. Po przesłaniu
   powiadomienie „Dotknij, aby usunąć z telefonu” otwiera przezroczystą `UploadDeleteActivity`, a ta systemowe okno
   `MediaStore.createDeleteRequest` (Android 11+). Na Androidzie 10 i starszych apka mówi, żeby usunąć ręcznie.
7. **Serwer** (`server/mp_upload.py`): kawałki do `<katalog>/.mp-upload/<id>.part`, na końcu rename do katalogu
   (przy kolizji „film (2).mkv”). Katalog docelowy to `upload_dir` z `config.json`, a domyślnie „Z telefonu”
   w pierwszym katalogu biblioteki, więc film od razu jest w Bibliotece. Nie potrzeba Deluge ani Transmission.

## Decyzje

- **Uprawnienia:** `READ_MEDIA_VIDEO` (Android 13+, przy okazji `POST_NOTIFICATIONS`), a na 12 i starszych
  `READ_EXTERNAL_STORAGE`. Apka prosi o nie dopiero przy włączaniu opcji, a domyślnie opcja jest wyłączona.
  Na Androidzie 14, przy wyborze „tylko wybrane filmy”, apka nie zobaczy nowych pobrań. Trzeba dać pełny dostęp.
- **Serwer docelowy:** wybrany w Ustawieniach, a bez wyboru serwer plików albo pierwszy z `features.upload`.
  Apka zna serwery z parowania (lista `Servers`), więc „styczność z serwerem X” oznacza, że X jest na tej liście
  i odpowiada na `ping` (ten sam `id`, więc token nie trafi do obcego komputera).
- **Czekanie co 15 min zamiast `Result.retry()`:** backoff WorkManagera rośnie z każdą próbą (liniowy: 15, 30,
  45 min… do 5 h), więc po dniu poza domem film czekałby godzinami. Zamiast tego worker kończy się sukcesem i dokłada
  nowe zlecenie z opóźnieniem 15 min. `retry()` zostaje dla zerwanego połączenia w trakcie przesyłania.
- **Identyfikator przesyłania:** SHA-1 z losowej soli instalacji, `_ID`, rozmiaru, daty i nazwy, więc jest stały
  dla pliku (wznawianie trafia w ten sam `.part`) i różny między telefonami.
- **Usługa pierwszoplanowa:** Android 12+ nie pozwala jej uruchomić z tła, więc `setForeground` może się nie udać.
  Wtedy przesyłanie idzie jako zwykłe zlecenie (system może je przerwać po ~10 min) i wznawia się w następnym.
- **Serwer pamięta zakończone przesyłania przez tydzień,** żeby powtórzone zapytanie (odpowiedź nie doszła) dostało
  „gotowe”, a nie stworzyło kopii. Porzucone `.part` znikają po miesiącu.

## Czego nie robimy

- **Przechwytywania pobierania w przeglądarce.** Android nie pozwala apce podejrzeć ani przejąć strumienia innej apki,
  a Chrome na telefonie nie ma rozszerzeń. Zostaje patrzenie w MediaStore po zakończonym pobraniu.
- Przesyłania danych komórkowych (tylko sieć bez limitu) i spoza LAN (to osobny temat: Tailscale/WireGuard).
- Plików z aparatu (`DCIM/`) i innych katalogów niż `Download/`.
- Pytania o pliki pobrane przed włączeniem opcji.

## Do sprawdzenia na telefonie

- Czy wyzwalacz MediaStore odpala się przy zamkniętej apce po pobraniu w Chrome i Firefoksie (i po restarcie telefonu).
- Czy `setForeground` przechodzi z tła na Pixelu (Android 16) i jak wygląda przerwanie po ~10 min, gdy nie.
- Okno usuwania (`createDeleteRequest`) dla pliku z Chrome.
