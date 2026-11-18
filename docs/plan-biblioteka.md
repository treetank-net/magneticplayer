# Plan: biblioteka z tagami, porządkowanie dysków, okładki

Stan (2026-11-18): etap 1 (tagi, widoczność, wykluczenia) w agencie 1.4.0 i w apce, bez ekranu przy setupie serwera
(na razie ołówek przy katalogu w Bibliotece). API dalej wskazuje katalog indeksem; `id` jest w konfiguracji na
potrzeby replikacji. Etapy idą po kolei, bo każdy stoi na poprzednim.

## Dziś

- Katalogi biblioteki to lista `{name, path}` w `config.json` (`library`). Przy pierwszym starcie agent wpisuje tam
  katalogi pobierania z klienta torrentów i `~/Wideo`/`~/Videos`/`~/Filmy`; potem dodaje się je w apce
  (Biblioteka → „Dodaj katalog”) albo na stronie.
- Każde urządzenie widzi wszystkie katalogi z całą zawartością; gość też (tylko bez dodawania i usuwania). Jedyne
  ukrywanie: nazwa zaczynająca się kropką.
- Katalog wskazuje się **indeksem** na liście (`root=2`), więc usunięcie katalogu przesuwa pozostałe.
- Torrent pobiera się tam, gdzie Deluge ma ustawione domyślnie.

## Decyzje

1. **Tagi na katalogach** (`filmy`, `seriale`, `bajki`, `kopia` i własne). Tag mówi, co ma tam trafiać, i jest
   filtrem w bibliotece. `kopia` nie pokazuje się w bibliotece.
2. **Widoczność per katalog:** wszyscy / tylko właściciele / wybrane urządzenia, plus lista podkatalogów
   wykluczonych z biblioteki. Ukryty katalog jest niedostępny także przez strumień, bilet, Chromecast i napisy, nie
   tylko niewidoczny na liście.
3. **Setup serwera:** po sparowaniu (i potem w szczegółach serwera) apka pokazuje dyski i katalogi; użytkownik wybiera,
   które podpiąć, z jakimi tagami i kto je widzi. `~/Downloads` przestaje być domyślnie w bibliotece.
4. **Load balancing:** kilka katalogów z tym samym tagiem → nowy plik idzie tam, gdzie najwięcej wolnego miejsca.
   Wyjątek: kolejne odcinki serialu idą tam, gdzie już leży ten serial.
5. **Replikacja:** opcja katalogu „kopiuj też na …”. Po pobraniu agent kopiuje plik na drugi dysk w tle. Kopia nie
   jest duplikatem i nie pokazuje się w bibliotece.
6. **Wybór przy magnecie na telefonie:** apka wysyła nazwę z magnetu (`dn=`), agent rozpoznaje ją `guessit`
   (już jest w venv jako zależność subliminal) i podpowiada katalog. Jeden ekran: „Film: Tytuł (rok) → Filmy na
   TOSHIBA (1,2 TB wolne)”, z możliwością zmiany. Deluge pobiera od razu tam (`download_location`), więc potem nic
   się nie przenosi. Magnet bez nazwy: pytanie, gdy klient pozna metadane.
7. **Porządkowanie istniejących plików:** podgląd „przeniosę X → Y”, przenosiny dopiero po „OK”. Na początek tylko
   przenoszenie do właściwego katalogu, bez zmiany nazw (zmiana nazw na `Tytuł (rok)/…` jak w Jellyfinie — może
   później, w torrentach przez `rename_files` klienta).
8. **Torrent dalej w kliencie:** plik z torrenta, który jest jeszcze w Deluge/Transmission, przenosi klient
   (`core.move_storage` / `torrent-set-location` z `move`), żeby miał nową ścieżkę i dalej seedował. Przenosi się
   cały katalog torrenta.
9. **Powiadomienia oszczędnie:** jedna operacja (porządkowanie, replikacja, przenosiny) = jedno zadanie w tle
   z postępem w apce (jak konwersja) i co najwyżej jedno powiadomienie na koniec („Przeniesiono 14 plików,
   2 błędy”). Replikacja po pobraniu jest cicha; błąd zbiera się w stanie katalogu, nie w osobnym powiadomieniu.
10. **Duplikaty i zajętość:** widok dysków (wolne/zajęte, ile w bibliotece), duplikaty po rozmiarze, potem skrócie
    początku i końca pliku, a na koniec pełnym skrócie dla pewności. Usuwanie tylko ręcznie.
11. **Wspólna biblioteka w telefonie:** jedna lista ze wszystkich serwerów i katalogów, które dane urządzenie widzi,
    z filtrem po tagu.
12. **Okładki jak w Jellyfinie/Pleksie:**
    - plik obok filmu (`poster.jpg`, `folder.jpg`, `<nazwa>.jpg`) ma pierwszeństwo;
    - bez niego klatka z filmu (ffmpeg, ~10% długości), w pamięci podręcznej agenta, bez kont i sieci;
    - opcjonalnie klucz TMDB (jak klucz OpenSubtitles): plakat, opis, rok; wymaga podpisu „dane z TMDB”.
13. **Porządkowanie „na zlecenie” jak u nas 18.11:** użytkownik zleca, agent przechodzi katalogi `guessit`em
    i proponuje strukturę (`Filmy/`, `Seriale/<tytuł>/`, `Bajki/`, `Muzyka/`, `Inne/` per dysk, każdy jako katalog
    biblioteki z tagiem), z listą do akceptacji. Ręcznie zrobione na jmpc: 105 pozycji, dziennik do cofnięcia
    w `~/.config/magneticplayer/porzadki-2026-11-18.json`. Rozpoznanie wymagało poprawek ręcznych (np. film z liczbą
    w tytule jako odcinek, anime i program kulinarny bez S01E01 jako film, komiksy i muzyka), więc podgląd przed
    przeniesieniem jest konieczny. Tego samego oczekujemy od użytkowników z własnym agentem AI.
14. Pobierany torrent nie zmienia nazw plików (protokół), ale pobiera się od razu do katalogu wybranego regułami
    (punkt 6), więc kolekcja zostaje uporządkowana bez przenosin.
15. **Rozpoznawanie (`server/mp_ident.py`, jeszcze niepodpięte do API):** sam `guessit` nie wystarcza (nie zna
    gatunku, myli liczby w tytułach z odcinkami). Magnet ma tylko nazwę, torrent nazwę i listę plików, czasem `.nfo`
    z identyfikatorem IMDb. Dlatego agent trzyma mały indeks z darmowych zrzutów IMDb (datasets.imdbws.com, użytek
    osobisty): filmy i seriale z ≥300 głosami, gatunki jako maska bitów, znormalizowane tytuły; ~125 tys. tytułów,
    12 MB SQLite, budowa ~20 s. Kolejność: IMDb z `.nfo` → `guessit` + indeks (rok, rodzaj, popularność) → zawartość
    (odcinki S01E01, muzyka, programy/komiksy, katalog bez filmów zostaje na miejscu). Serial tylko po oznaczeniach
    odcinków, nie po liczbie plików (kolekcja filmów to nie serial). Bajki: animacja + `Family` pewnie; sama animacja
    albo sam `Family` z niską pewnością, bo IMDb nie odróżnia anime dla dorosłych od bajek. Test na uporządkowanej
    ręcznie kolekcji jmpc: 87/92 zgodnie, przy wysokiej pewności 61/62; chybienia to tytuły tylko po polsku
    (później Wikidata), anime dla dorosłych, aktorskie remaki i serial animowany dla dzieci bez `Family`. Niska
    pewność = pytanie na telefonie, a wybór użytkownika warto zapamiętać.
16. Zdjęcia i dokumenty (reguły typu `rok/miesiąc/nazwa.pdf`) — poza apką (Immich, PhotoPrism, Paperless-ngx).

## Model katalogu (config.json)

```json
{"id": "k7f3", "name": "Filmy (TOSHIBA)", "path": "/media/jm/TOSHIBA EXT/Filmy",
 "tags": ["filmy"], "visible": "all", "exclude": ["prywatne"], "replicate_to": "p2c1"}
```

- `id` stały (krótki losowy); API przyjmuje `root=<id>`, a stary indeks jeszcze przez jakiś czas (stare apki).
- `visible`: `"all"`, `"owners"` albo lista id urządzeń.
- Brak `tags` = katalog jak dziś (bez tagu), więc istniejąca konfiguracja działa bez migracji poza nadaniem `id`.

## Etapy

1. **Tagi, widoczność, `id` katalogów, ekran katalogów w ustawieniach serwera.** Wymuszenie widoczności we wszystkich
   miejscach, które przyjmują `root`.
2. **Magnet → katalog:** `POST /api/library/guess` (nazwa → typ, tytuł, rok, sezon, podpowiedź katalogu z load
   balancingiem i „serial już tu jest”), wybór na telefonie, `download_location` w Deluge/Transmission.
3. **Zadania plikowe w tle:** przenosiny (przez klienta, gdy torrent w nim jest), replikacja, jedno powiadomienie
   na koniec.
4. **Dyski i porządkowanie:** zajętość, duplikaty, podgląd porządkowania.
5. **Wspólna biblioteka i okładki** w apce.
