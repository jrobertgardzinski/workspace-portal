# Kompensacja sagi offboardingu — checkpoint roboczy

**Po co ten plik:** sesja 2026-08-08 urwała się z całą robotą w drzewie roboczym (nic
niezacommitowane, cztery repozytoria). Ten plik jest listą kontrolną, żeby następna sesja nie
odtwarzała stanu z pamięci. **Aktualizować po każdym ukończonym punkcie.**

Decyzje projektowe: `../shared/docs/adr/0007-soft-delete-by-status-for-a-compensatable-offboarding-saga.md`.

## Skrót mechanizmu (żeby nie czytać wszystkiego od nowa)

Saga usuwania konta jest dwufazowa. Uczestnik na `PURGE_USER_CONTENT` **oznacza** treści
(`status = PENDING_ERASURE` + `markedForErasureAt`) — nic nie ginie, a treść znika ze wszystkich
publicznych odczytów, bo te idą przez widok (`active_memes`, `active_comments`). Potwierdzenie
znaczy „zarezerwowane". Gdy potwierdzą wszyscy, orkiestrator wysyła **`ERASE_USER_CONTENT`**
(domknięcie) — dopiero to kasuje. Gdy się poddaje, wysyła **`RESTORE_USER_CONTENT`** (kompensacja)
do WSZYSTKICH uczestników, także tych bez potwierdzenia. **Pivot:** usunięcie bloba z MinIO/S3
w `PurgeUserContent` (memes). Za nim tylko ponawianie.

## Checkpointy

| # | Punkt | Stan |
|---|-------|------|
| 1 | Domena memes: `MemeStatus`, `MemeMetadata.markForErasure/restore`, V10 | ZROBIONE (w drzewie) |
| 2 | Domena comments: `CommentStatus`, `Comment.markForErasure/restore`, V6 | ZROBIONE (w drzewie) |
| 3 | Odczyty przez widok + strażnik (`MemeReadFilterTest`, `CommentReadFilterTest`) | ZROBIONE (w drzewie) |
| 4 | Test brudnego odczytu (`MarkedMemeIsInvisibleTest`, `MarkedCommentIsInvisibleTest`) | ZROBIONE (w drzewie) |
| 5 | Orkiestrator: `MARK`/`ERASE`/`RESTORE`, domknięcie na ostatnim potwierdzeniu, kompensacja w zamiataczu, wstrzymanie znacznika outboxu dla całej sagi | ZROBIONE (w drzewie) |
| 6 | Reaper = zapytanie po statusie (`pendingOf`, `pendingSince`) + alarm `StuckErasureWatch` | ZROBIONE (w drzewie) |
| 7 | ADR 0007 (3 akapity: status/nie byt, domknięcie/nie czas, pivot) | ZROBIONE (`shared`, untracked) |
| 8 | Gherkin kompensacji i ścieżki szczęśliwej (`account-erasure.feature`, memes) | ZROBIONE (w drzewie) |
| 9 | Zielony build całego reaktora portalu | ZROBIONE — `./mvnw -o verify` BUILD SUCCESS, 4 moduły (patrz „Co poprawiłem" niżej) |
| 10 | Diagram: dwie fazy + pivot + droga kompensacji w `microservice-memes/docs/account-deletion-across-services.md` | ZROBIONE |
| 11 | e2e `participant-outage`: „their meme stays gone" → „their meme is back in the gallery" (+ krok w `.steps.mjs`) | ZROBIONE |
| 12 | `PODRECZNIK.md`: §2 opisuje dwie fazy i pivot, §15 dostaje ADR 0007, §18 zdanie nr 1 poprawione | ZROBIONE |
| 13 | `todo.md` w memes/comments/offboarding | **DO ZROBIENIA** |
| 14 | Commit + push w czterech repach (shared, memes, comments, offboarding) | **DO ZROBIENIA** |

## Co poprawiłem w tej sesji (poza checkpointami)

Build zawisł, nie po prostu spadł — **1,4 GB logu w kilka minut**. Trzy testy z poprzedniej sesji
zostały przerobione tylko w połowie: konstruktor listenera dostał nowe zależności, ale zatruwany
był nadal `PurgeUserContent`/`PurgeUserComments`, podczas gdy komenda `PURGE_USER_CONTENT` wywołuje
teraz **oznaczenie**. Skutek: nic nie rzucało wyjątku, a `while (!deliverOnce())` w
`PurgeRetriesTest` (memes i comments) kręciło się bez końca, logując jedną linię w kółko.

- `PurgeRetriesTest` × 2 — zatruwany jest `markForErasure`, a pętla „spend the budget" dostała
  ogranicznik (500 dostaw), żeby następny taki błąd **spadał**, a nie wisiał.
- `PurgeConfirmationOutboxTest` (memes) — dwa testy sprawdzały wywołanie kasowania na komendzie
  oznaczenia; teraz sprawdzają oznaczenie i JAWNIE, że kasowanie się nie odbyło.
- `ErasureSagaSteps` — `@But` i `@And` z tym samym tekstem na jednej metodzie to dla cucumbera
  **duplikat definicji**, a duplikat wywala CAŁY glue: przez jeden krok sypało się 14 scenariuszy
  w module, także tych niezwiązanych z sagą.
- **Dwa scenariusze po stronie orkiestratora** (`offboarding.feature`), których nie było:
  ostatnie potwierdzenie wysyła domknięcie z polityką i robi to PRZED ogłoszeniem wyniku;
  kapitulacja wysyła kompensację i też przed ogłoszeniem. Kolejność, nie sama obecność — bo to
  ona decyduje, czy świat dowiaduje się o zamknięciu sprawy przed jej zamknięciem.

## Ścieżki odczytu — co sprawdzone

Punkt „przejrzyj wszystkie ścieżki odczytu" pokryty tak: **lista memów, wyszukiwanie po tagu,
ranking `hot`, `/meta`, obrazek, miniatura, głosy i indeks dedupu** — każda z nich to inna droga do
bazy i każda jest odpytana z osobna w `MarkedMemeIsInvisibleTest` (odpowiednio
`MarkedCommentIsInvisibleTest`). **Osobnego indeksu wyszukiwania nie ma** — szukanie po tagu to
zapytanie SQL, więc idzie przez ten sam widok. **Cache'a też nie ma** — ani Redisa w compose, ani
`@Cacheable`/Caffeine w kodzie — więc nie ma trzeciej kopii prawdy do unieważnienia. Gdyby kiedyś
doszła, to jest miejsce, w którym filtr przestaje wystarczać.

## Świadomie NIE zrobione

- **`microservice-user-collections` nie jest przerobiony.** Nadal kasuje ulubione na komendę
  oznaczenia, więc skompensowana saga przywraca memy i komentarze, ale nie zapisaną listę.
  Zapisane w ADR 0007 jako jawny dług, nie przeoczenie. Kształt zmiany jest ten sam
  (kolumna statusu, widok filtrujący, trzy komendy). Uczestnik ignoruje `ERASE`/`RESTORE`
  po `type`, więc nowe komendy go nie wywracają.
- **Brak osobnego `.feature` dla comments** — scenariusz kompensacji w memes gra rolą
  „comments-service pada", czyli pokrywa wymaganie; osobny plik byłby tym samym zdaniem
  z drugiej strony.
- **Nazwa `PURGE_USER_CONTENT` na drucie bez zmian** — pakty uczestników ją pinują, a znaczenie
  („spraw, żeby treść zniknęła, i powiedz kiedy") się nie zmieniło. Nowe typy są addytywne
  w wersji 1 koperty (ADR 0004).
