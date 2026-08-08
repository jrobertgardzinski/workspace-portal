# Kompensacja sagi offboardingu — checkpoint roboczy

**Po co ten plik:** sesja 2026-08-08 urwała się z całą robotą w drzewie roboczym (nic
niezacommitowane, cztery repozytoria). Ten plik jest listą kontrolną, żeby następna sesja nie
odtwarzała stanu z pamięci. **Aktualizować po każdym ukończonym punkcie.**

Decyzje projektowe: `../shared/docs/adr/0007-soft-delete-by-status-for-a-compensatable-offboarding-saga.md`.

## Skrót mechanizmu (żeby nie czytać wszystkiego od nowa)

Saga usuwania konta jest dwufazowa. Uczestnik na `PURGE_USER_CONTENT` **oznacza** treści
(`status = PENDING_ERASURE` + `markedForErasureAt`) — nic nie ginie, a treść znika ze wszystkich
publicznych odczytów, bo te idą przez widok (`active_memes`, `active_comments`,
`active_collection_items`). Potwierdzenie
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
| 13 | `todo.md` w memes/comments/offboarding | ZROBIONE |
| 14 | Commit + push (shared, portal, memes, comments, offboarding) | ZROBIONE |
| 15 | **Runda 2:** collections dwufazowy + uzupełnione pokrycie testowe | patrz sekcja niżej |

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

## Runda 2 (ta sama sesja): collections przerobiony, pokrycie uzupełnione

`microservice-user-collections` **jest przerobiony** — dług z ADR 0007 zamknięty tego samego dnia.
`ItemStatus` + `SavedItem` (przejścia metodami, ten sam niezmiennik), port `ItemErasure`,
`JdbcItemErasure`, V3 z widokiem `active_collection_items`, trzy komendy w konsumencie,
`ErasureBacklogWatch` + gauge `collections_erasure_backlog`, pakt rozszerzony o `ERASE`/`RESTORE`
(i weryfikacja po stronie orkiestratora). **`CollectionStore.purgeUser` USUNIĘTY** — hurtowe
kasowanie było właśnie tym, przez co ten uczestnik był nieodwracalny.

Uzupełnione dziury w pokryciu (znalezione przy odpowiadaniu na pytanie „czy to jest przetestowane"):

- **Testy domenowe wszystkich trzech agregatów** (`MemeMetadataTest`, `CommentErasureStateTest`,
  `SavedItemTest`) — niezmiennik status⇔znacznik, „drugie oznaczenie zachowuje PIERWSZĄ chwilę",
  `restore()` na ACTIVE jako no-op. Reguła o pierwszej chwili była wcześniej opisana w javadocu,
  ADR-ze i CHECK-u, a **nieasertowana nigdzie**: testy przypadków użycia chodzą na zamrożonym
  zegarze, więc tam jest niewidoczna.
- **Alarm zaległości** (`StuckErasureWatchTest`, `ErasureBacklogWatchTest`) — wcześniej ZERO testów.
  W tym najważniejszy: nieczytelny rejestr **zachowuje ostatnią wartość** zamiast raportować zero.
- **Prawo idempotencji w collections** obejmuje teraz trzy komendy sagi, a odcisk stanu widzi też
  rezerwacje — bez tego każda komenda erasure przechodziłaby to prawo trywialnie, bo oznaczenie
  jest niewidoczne w listingu Z ZAŁOŻENIA.
- **Test JDBC na dwóch fazach** (collections): oznaczenie nie kasuje, kompensacja wraca z tą samą
  kolejnością, domknięcie nie rusza tego, co zapisano PO oznaczeniu.

## Świadomie NIE zrobione

- **Brak osobnego `.feature` dla comments** — scenariusz kompensacji w memes gra rolą
  „comments-service pada", czyli pokrywa wymaganie; osobny plik byłby tym samym zdaniem
  z drugiej strony.
- **Nazwa `PURGE_USER_CONTENT` na drucie bez zmian** — pakty uczestników ją pinują, a znaczenie
  („spraw, żeby treść zniknęła, i powiedz kiedy") się nie zmieniło. Nowe typy są addytywne
  w wersji 1 koperty (ADR 0004).


## Runda 3 (ta sama sesja): dowody, alarmy i długi z innych rewirów

- **E2E kompensacji PRZESZEDŁ na żywym stosie** — 1 scenariusz, 9 kroków, 8m17s. Zanim przeszedł,
  trzeba było poprawić jego arytmetykę: zakładał kapitulację po ~172 s (stan sprzed P18), a jest
  `120 s × 4 ≈ 8 minut`. Ścieżka szczęśliwa też zielona (4 scenariusze, 21 kroków, 11,7 s).
  Do nocnego biegu dołożony **niedzielny cron z `@outage`**.
- **Znalezisko przy okazji, warte rozmowy kwalifikacyjnej:** pięciominutowa siatka security odpala
  się teraz PRZED ośmiominutową kapitulacją portalu. Konto wraca ok. 5 minuty, treść ok. 8.
  Nikt tego nie zaprojektował — to suma dwóch niezależnie konfigurowanych timeoutów. Opisane
  w §10 podręcznika; podniesienie siatki security ponad `purgeTimeout × (retries + 1)` zostaje
  jako decyzja do podjęcia, nie zrobione po cichu.
- **Alarmy Prometheusa** na zaległość wymazywania (trzy serwisy jedną regułą), kapitulację sagi
  i porzucone rekordy — `shared/observability/alert-rules.yml`.
- **24 PR-y Dependabota** zamknięte: łatki wzięte z PR-ów, zaaplikowane lokalnie, po jednym
  buildzie na repo. Kafka 4 wymagała poprawki harnessu (`MockProducer` stracił konstruktor).
  Odrzucony świadomie: cucumber 7.34 w collections (JUnit Platform 6 wywraca harness).
- **`microservice-security` był CZERWONY na main od 30 lipca** — zmiana `LockoutSubject` ominęła
  dubler portu w `security-application`. Naprawione, CI zielone. Portal też przez to czerwieniał,
  bo jego reaktor buduje kernel.

### Domknięcie CI (ta sama sesja)

- **CI portalu ZIELONE** (reaktor + e2e). Po drodze dwie przyczyny, obie ciekawsze niż wyglądały:
  - `CommentControllerTest` padał tylko w CI, bo testy modułu dzielą jedną bazę H2, a testy purge
    kasują głosy odchodzącego; o kolejności decyduje domyślny `runOrder` surefire, czyli system
    plików. Test dostał własną bazę.
  - Harness galerii dostawał 403, a potem 429: kody odzyskiwania mają własną akcję step-upu,
    więc każdy zasiewany account kupuje teraz dwie elewacje — a limit liczy per źródło i cała
    suita jest jednym źródłem. Limit poluzowany w compose deweloperskim.
- **CI `shared`: reaktor zielony, browser e2e nadal czerwony** (4 scenariusze MFA). Przycisk
  „wygeneruj kody odzyskiwania" był martwy i dla testu, i dla użytkownika — UI nie pytał
  o step-up; to naprawione. Zostaje czynnikowa połowa step-upu w UI, z dokładnym śladem sieciowym
  w `shared/microservice-security/todo.md`.
