# Plan naprawczy — memes portal

Na podstawie review z 2026-07-25. Kolejność etapów = kolejność wdrażania:
najpierw to, co pod awarią zostawia trwałe szkody (zablokowane konto, OOM,
zatruty dedup), potem semantyka sagi, na końcu higiena. Każdy etap jest
samodzielnie wdrażalny i domknięty testami.

---

## Etap 1 — Niezawodność pętli Kafki (offboarding + user-collections)

**Cel:** żaden pojedynczy wyjątek ani crash nie zostawia sagi w stanie
nieodtwarzalnym; health mówi prawdę.

1. **Nadzór pętli konsumenta i sweepera** — `microservice-offboarding`
   `infrastructure/KafkaLoop.java:69-71,84-86`
   - try/catch wokół pojedynczego batcha/rekordu, nie wokół `while`.
   - Błąd infrastrukturalny (SQL, commit) → backoff (np. 1s→30s) i ponowienie
     **bez** commitu offsetu; pętla nigdy nie umiera.
   - Logowanie z pełnym wyjątkiem (`LOG.warn("...", e)`), nie samym
     `getMessage()`.
2. **Poison pill → drop/DLQ zamiast crash-loopa** —
   `infrastructure/EventsRouter.java:94-96`
   - `UUID.fromString` i walidacja `email` (niepuste!) w try/catch wewnątrz
     `handle()`; zły rekord logowany i dropowany jak malformed JSON
     (`EventsRouter.java:63-67` już tak robi — ujednolicić).
   - Fakt bez `id`: odrzucić (pact i tak pinuje `uuid("id")`) zamiast
     `UUID.randomUUID()`.
3. **Flush przed commitem — koniec at-most-once dla outcome'ów** —
   `KafkaLoop.java:89-97,67`
   - `producer.flush()` (lub `send(...).get()`) **przed** `commitSync()`.
   - Shutdown hook: `producer.close()`, zatrzymanie pętli.
4. **Liveness w /health** — `infrastructure/Main.java:63-65`
   - Flagi „ostatni udany obieg pętli/sweepera"; `/health` → 503 gdy któraś
     pętla martwa dłużej niż próg. Compose healthcheck offboardingu
     przełączyć z `nc -z` na HTTP `/health` (`docker-compose.yml:252`).
5. **To samo w user-collections** —
   `infrastructure/PurgeCommandsConsumer.java:100-110` + `Main.java:43`
   - Identyczny wzorzec: retry z backoffem wokół poll/handle,
     `send().get()`/flush przed `commitSync()`, sygnał do health/metryk.

**Testy:** test EventsRoutera na zły UUID / brak emaila / pusty email;
test „redelivery po COMPLETED nie gubi outcome'u" (po Etapie 3 pkt 2 — że
JEST odtwarzany); rozważyć 1 test integracyjny (testcontainers-kafka)
pinujący „flush przed commit".

**Efekt:** saga z 6/10 → ~7.5/10. Największy zwrot z najmniejszego diffu.

---

## Etap 2 — Bariery na niezaufane obrazy (image + memes)

**Cel:** żaden pojedynczy upload nie kładzie procesu.

1. **microservice-image `server.py`:**
   - Cap na `Content-Length` (np. 12 MB, spójnie z limitem memes) → 413;
     zniekształcony nagłówek → 400/411 (dziś nieobsłużony `ValueError`).
   - `Image.MAX_IMAGE_PIXELS` obniżony jawnie (np. 25 mln px) **i** własny
     check `img.size` po `Image.open` przed `load()` (wymiary znane tanio).
   - try/except wokół `image.save` → 400 (potwierdzony bug: PNG w trybie
     LA/I/I;16 → JPEG rzuca `OSError` i zrywa połączenie).
   - `Image.open(..., formats=["PNG", "JPEG", "WEBP"])` — whitelist
     dekoderów.
   - Jawna walidacja `0 <= quality <= 100` → 400 (dziś kontrakt wisi na
     detalu implementacyjnym Pillow).
   - Przypiąć wersję Pillow w `requirements.txt` (dokładna wersja, nie `>=`).
2. **microservice-memes `memes-image/.../WebImageOptimizer.java:37`:**
   - Przed `ImageIO.read()` odczytać wymiary z nagłówka
     (`ImageReader.getWidth/getHeight` bez dekodowania) i odrzucić powyżej
     progu (np. 8000×8000) jako `IllegalArgumentException`.
3. **500 → 400 na śmieciowym uploadzie** — globalny `@ControllerAdvice` w
   `memes-infrastructure`: `IllegalArgumentException` → 400,
   `UncheckedIOException` z uploadu → 400/422.

**Testy:** HTTP-testy image (kody, limity, bomba z wymiarów, LA→JPEG,
quality); test WebImageOptimizer na PNG deklarujący absurdalne wymiary;
MockMvc na upload PDF-a → 400.

**Efekt:** image z 5/10 → ~7.5/10; znika główny wektor DoS w memes.

---

## Etap 3 — Semantyka sagi (offboarding — do 8+/10)

**Cel:** poprawność ponad happy path: właściwa saga, jedna saga, outcome
zawsze ogłoszony, retry przed kapitulacją.

1. **Potwierdzenia po `sagaId`** —
   - Pacty potwierdzeń (`MemesConfirmationContractTest` + bliźniaki
     collections/comments): dodać `sagaId` (przejściowo opcjonalny —
     ewolucja kontraktu, nie big bang).
   - Producenci potwierdzeń (memes `PurgeCommandsListener`, comments
     `PurgeCommandsListener`, collections `PurgeCommandsConsumer`):
     przewieźć `sagaId` z komendy do potwierdzenia.
   - `EventsRouter.onConfirmation` + `JdbcSagaStore.confirm`
     (`JdbcSagaStore.java:146-155`): szukać po `sagaId`, fallback po emailu
     tylko dla potwierdzeń bez pola.
2. **Mini-outbox na outcome** — kolumna `outcome_announced BOOLEAN` (V2)
   ustawiana w tej samej operacji co flip stanu; sweeper dodatkowo publikuje
   (i ponawia) outcome'y `COMPLETED`/`COMPENSATED` z
   `outcome_announced=false`. Wtedy zgubiony `PORTAL_CONTENT_PURGED` jest
   odtwarzalny ze stanu — domyka lukę, którą Etap 1 pkt 3 tylko zwęża.
3. **Jedna biegnąca saga na email** — `JdbcSagaStore.java:37-62` + migracja:
   częściowy unikalny indeks `(email) WHERE state='STARTED'` (H2: wariant
   z kolumną wyliczaną); w `start()` złapać 23505 na `fact_id` i przeczytać
   istniejącą sagę zamiast rzucać (dziś unique-violation zabija wątek).
4. **Retry przed kompensacją** — `SweepOverdue`/`compensateOverdue`:
   przed flipem na `COMPENSATED` N ponowień `PURGE_USER_CONTENT` do
   brakujących uczestników (są idempotentni — bezpieczne); do
   `PORTAL_PURGE_FAILED` dołączyć listę uczestników, którzy potwierdzili
   (materiał do ręcznej obsługi częściowego purge'a); licznik `COMPENSATED`
   w `/metrics`.
5. **Walidacja emaila u uczestników sagi** — memes
   `PurgeCommandsListener.java:82-91` (i analogicznie comments,
   collections): pusty email → log + brak potwierdzenia, zamiast no-op
   z fałszywym `USER_CONTENT_PURGED`.
6. **Retencja PII** — job w sweeperze: sagi `COMPLETED`/`COMPENSATED`
   starsze niż X dni → usunięcie/hash emaila; przyciąć logowanie emaila
   na INFO (offboarding, comments `PurgeCommandsListener.java:66-68`).

---

## Etap 4 — Spójność danych (memes + comments)

1. **memes: claim w transakcji z save** — `PublishMeme.java:30-34` +
   `JdbcMemeContentIndex`: claim i save w jednej transakcji **albo**
   kompensacja `contentIndex.remove(candidate)` w catch; FK
   `content_index.meme_id → memes(id)`. (Naprawia trwałe zatrucie dedupu.)
2. **memes: kasowanie wariantu WebP** — `JdbcMemeRepository.deleteById`
   (`:65-69`): dodatkowo `objects.delete(memeId + ".webp")` + test, że po
   delete/purge blob i wariant znikają. (To jest luka RODO — bajty zostają
   po czystce.)
3. **memes: szew transakcyjny dla wieloetapowych use case'ów** —
   `DeleteMeme.java:39-43`, `PurgeUserContent.java:45-49`: transakcyjny
   dekorator w infrastrukturze (use case'y zostają czyste).
4. **comments: transakcje** — dodać spring-tx; `@Transactional` (na
   adapterze/dekoratorze) wokół `DeleteComment`, `PurgeUserComments`,
   `DeleteThread`; FK `comment_votes → comments` z kaskadą (jak ma
   `comment_flags` w V2).
5. **comments: upserty zamiast delete+insert** —
   `JdbcCommentVotes.java:24-28`, `JdbcCommentModeration.java:24-30`:
   `INSERT ... ON CONFLICT` (H2 w trybie PG wspiera). Znika wyścig na PK
   przy double-clicku.
6. **memes: MEME_DELETED bez gubienia** — `DeleteMeme.java:43` /
   `KafkaMemeEvents.java:24`: minimalnie sprawdzić wynik `send()`
   i logować błąd; docelowo ten sam wzorzec mini-outboxu co w Etapie 3.

---

## Etap 5 — Higiena i decyzje produktowe

1. **Decyzja: e-maile w publicznym listingu comments**
   (`CommentController.java:106`) — pseudonim/maskowanie; zaktualizować
   test, który dziś utrwala wyciek (`CommentControllerTest.java:66`).
2. **Timeouty na RestClient** — comments `HttpMemeDirectory.java:19`,
   `HttpSecurityAuthenticationGate.java:28` (connect ~2s, read ~5s);
   przy okazji sprawdzić analogiczne klienty w memes.
3. **infra: fallback OTel** — `infra-up.sh:16` + `docker-compose.yml:28`:
   gdy agent niepobrany, nie ustawiać `-javaagent` (pusta zmienna
   w compose), żeby obietnica „start bez tracingu" była prawdziwa.
4. **compose:** `restart: unless-stopped` dla serwisów aplikacyjnych;
   healthchecki HTTP zamiast `nc -z` tam, gdzie jest `/health`
   (user-collections `:186-192`, offboarding `:252`).
5. **RateLimit (memes i comments):** sprzątanie mapy okien (eviction
   starych wpisów); świadoma decyzja czy fixed window wystarcza.
6. **Drobiazgi:** README memes — usunąć nieaktualną sekcję o komentarzach;
   correlation-id do microservice-image (`HttpImageEncoder`); pact dla
   `POST /encode`; `ServeMeme.java:44` — cache put best-effort; walidacja
   długości parametrów w collections `CollectionsApi` (400 zamiast 500);
   test 401 po HTTP w collections; `USER root` → nieuprzywilejowany
   w Dockerfile'ach (comments, image).

---

## Kolejność i zależności

- Etapy 1 i 2 są niezależne — można równolegle. Oba to małe diffy o dużym
  efekcie.
- Etap 3 pkt 1 (sagaId) dotyka 4 repozytoriów przez kontrakty Pact —
  robić ewolucyjnie: najpierw producenci potwierdzeń dokładają pole,
  potem konsument (offboarding) zaczyna po nim szukać.
- Etap 3 pkt 2 (outbox) buduje na Etapie 1 pkt 3 (flush) — flush zwęża
  okno, outbox je zamyka.
- Etap 4 i 5 niezależne od reszty.

## Oczekiwany efekt

| Obszar | Przed | Po etapach 1-4 |
|---|---|---|
| offboarding (saga) | 6/10 | ~8.5/10 |
| image | 5/10 | ~7.5/10 |
| memes | 8/10 | ~9/10 |
| comments | 7.5/10 | ~8.5/10 |
| user-collections | 8/10 | ~8.5/10 |
