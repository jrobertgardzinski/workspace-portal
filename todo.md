# Portal TODO

Backlog tego workspace'u. Cross-project rzeczy są w `../shared/todo.md`.

## TODO NR 1 — `account-closure-2` (stan na 2026-10-02)

Drugie podejście do `shared/account-closure`, tym razem pod pieczą właściciela. Nie jest
następcą tamtego modułu i go nie zastępuje — stoi obok i odpowiada na inne pytanie.

### Po co to jest

`account-closure` + `portal-specs` dowodzą obietnic portalu **po podjęciu decyzji
architektonicznej**: jest orkiestrator, są potwierdzenia, jest kworum, jest kompensacja.
Mierzalny koszt tego: **4963 linie Javy pod 312 liniami `.feature`** (`closure/ClosureInOneProcess`
597, `races/Seeds` 463, `closure/AccountClosureSteps` 440, `races/Invariant` 393,
`races/Explorer` 380, `world/Portal` 282). Właściciel tego kodu nie czyta i nie chce czytać.

`account-closure-2` ma odpowiedzieć na pytanie **sprzed tej decyzji**: co musi być prawdą
o usuwaniu konta i mema, niezależnie od tego, czy to będzie monolit czy mikroserwisy.
Odbiorca: osoba techniczna, która chce w pięć minut zobaczyć łańcuch i jego rozgałęzienia.

### ZAŁOŻENIA (ustalone, nie zgaduj od nowa)

1. **Lokalizacja: `portal/`**, obok `portal-specs`, jako osobny moduł. Nie w `shared/` —
   słownictwo portalu nie mieszka w kernelu (patrz `../shared/CLAUDE.md`).
2. **Napędza TYLKO `*-domain` + `*-system`.** Żadnej infrastruktury, żadnego
   `*-application`, żadnego Kafki, żadnego JDBC. To jest twarde ograniczenie i to ono
   wymusiło cały refaktor opisany niżej.
3. **ZERO słownictwa mechanizmu w specce.** Nie wolno użyć: `saga`, `orchestrator`,
   `pivot`, `transactional`, `2PC`, `kworum`, `kompensacja`, `MARK`/`ERASE` jako faz.
   Powód (słowa właściciela): „Skoro w account-closure-2 nie znamy architektury to po co
   piszesz saga? Robimy na sucho, a saga, transactional, 2pc czy cokolwiek innego
   wdrożymy później." Dwufazowa rezerwacja **też jest mechanizmem** — istnieje tylko
   dlatego, że robota idzie przez kilka procesów; w jednej transakcji nie ma żadnego MARK-a.
   Javadoc `security-domain/.../port/ContentPurge.java` mówi to wprost: z jedną bazą
   „every part of the saga above becomes unnecessary".
4. **Co zostaje, gdy się mechanizm zdejmie — i to jest cała treść specki:**
   kolejność faktów i co jest prawdą pomiędzy. Konto **przeżywa** swoją treść.
5. **Gherkin, nie proza.** Rozważaliśmy hybrydę AsciiDoc + `include::...feature[tags=...]`
   (znaczniki include siedzą w komentarzach Gherkina, więc rozdział wciąga pojedyncze
   scenariusze między akapity prozy). Właściciel: „kusi, ale narazie zostawmy gherkin."
   Decyzja odłożona, nie odrzucona.
6. **2 suity na start.** Zamknięcie konta i usunięcie mema.
7. **Kształt testu (słowa właściciela, to jest kryterium odbioru):** „ten serwis rzuca
   taki event, te go sluchaja, potem usuwaja cos w repozytoriach (czy aby na pewno?
   Zróbmy tu asercje), jak juz usuna to emituja eventy, ktos je zbiera i jak zbierze
   wszystkie to dopiero wtedy usuwa uzytkownika (asercja na repo)."
8. **Reguła glue:** jeden krok Gherkina = jeden hop ALBO jedna asercja na repozytorium,
   nigdy oba naraz. Żadnego „drenowania busa" w jednym kroku. Asercje czytają fejki
   bezpośrednio (`FakeMemeErasure.isMarked(...)`, `FakeCommentErasure.activeOf(...)`,
   `FakeUserRepository.findBy(...)`), nie przez warstwę pomocniczą.
9. **Słownictwo fejków (wyrok z 2026-09-28, `portal-specs/README.md` + `ANALIZA-2026-09-28-...md`):**
   `mock` = Mockito; `Fake*` = działający zamiennik w `src/test`; `InMemory*` = **prawdziwy**
   adapter w `src/main`; stub = osobny serwis w dockerze.

### ZROBIONE (wszystko zbudowane, zacommitowane i wypchnięte)

Refaktor, bez którego założenie nr 2 było niewykonalne — porty i ich fejki wylądowały
w modułach, które te porty deklarują, a use case'y usuwania w `*-system`:

- **12 portów treści → `*-domain`**, każdy z fejkiem obok i `test-jar`, który go wywozi
  (`e99a28d`, `f52a215`, `4744cc3`, `efe5147`): `MemeRepository`, `VoteRepository`,
  `TagRepository`, `MemeContentIndex`, `MemeErasure`, `PurgePolicyOverride`, `MemeEvents`;
  `CommentRepository`, `CommentVotes`, `CommentErasure`, `CommentEvents`;
  `CollectionRepository`, `ItemReferences`, `ItemErasure`.
- **`SagaStore` → `offboarding-domain`** (`8ab9ae9`, `9091519`) razem z `FakeSagaStore`
  i `SagaStoreContractTest`.
- **11 dubli → `security-domain` / `security-system`** (`a60e935`, `5454227`); zniknęły
  dwie prywatne kopie `PendingEnrolments` (−57/+3 linie).
- **Trzy nowe moduły `*-system`** przed `*-application` (`ccbafa6`, `9d39550`, `4710e17`,
  `249b93f`): `memes-system`, `comments-system`, `collections-system`. Tam siedzą
  `MarkUserContentForErasure`, `PurgeUserContent`, `RestoreUserContent`,
  `WatchErasureBacklog`, `DeleteMeme` i odpowiedniki w dwóch pozostałych serwisach.
- **Trzy brakujące fejki repozytoriów security** (`shared/microservice-security` `45e39d8`):
  `FakeEmailChangeRepository`, `FakePasswordResetRepository`,
  `FakeFederatedIdentityRepository`. `DeleteAccount` wymienia **dziewięć** repozytoriów
  z nazwy (żadna z tych tabel nie ma klucza obcego, nic nie kaskaduje) — fejki były do
  sześciu, więc use case'u nie dało się w ogóle zbudować. Przy okazji zniknęła czwarta
  kopia mapy stringów, którą `FederatedSignInSteps` nosił jako klasę anonimową.
- **Fejki repozytoriów treści** (`microservice-memes` `7713f67`, `microservice-comments`
  `8aa0bb8`, `portal` `b18bbb5`): `FakeMemeRepository` i `FakeCommentRepository` stanęły
  obok portów; `portal-specs`'owe `FakeMemes`/`FakeComments` są teraz ich cienkimi
  podklasami i trzymają wyłącznie to, co jest sprawą tego runnera (zasiewanie przez
  `ContentIds`, czytelniki po imieniu, `snapshot()` dla `UnitsOfWork`).

Stan zieleni po tym wszystkim: **portal 1047 testów Java, 0 upadków**; security domain 13,
config 38, system 101, application 36 zielone, infrastructure 338 z dwoma upadkami, które
tam były wcześniej (`CorsPreflightTest`, `TrustedProxyHttpTest`). `memes-ui` (npm) czerwony
niezależnie od zmian — patrz `../shared/todo.md`.

### ZOSTAŁO

1. **Założyć moduł** `portal/account-closure-2/` + `portal/specs-2/` na `.feature`
   (wzorem `portal-specs` + `portal/specs`: `build-helper` dokłada `../specs-2` jako
   test-resource). Bez parenta — `workspace-portal` jest czystym agregatorem.
2. **Napisać dwie suity.** Szkic scenariusza głównego, zaakceptowany przez właściciela
   („podoba mi sie ostatni gherkin"), jest w historii sesji i brzmi tak:
   `Given alice has 2 memes, 3 comments and 4 saved references` /
   `And bob saved one of alice's memes` /
   `When the closure of alice's account is requested` → `Then alice is still in the user repository` /
   `When memes has finished with alice's content` → `Then alice's memes are gone from the meme repository`
   `And alice is still in the user repository` / (to samo dla comments: + `And bob's reference
   to alice's meme is gone`) / (to samo dla collections) → `And only now is alice gone from
   the user repository` `And alice is gone from the session, factor and recovery-code repositories`.
   `When <serwis> has finished` to po prostu „ten kawałek roboty się wykonał" — zero wiedzy
   o tym, czy to były eventy, komendy, czy wywołanie metody.
3. **Rozgałęzienia edge case'owe**, w tym samym języku. Co najmniej:
   - jeden serwis nie kończy → konto zostaje; `Then alice's memes stay gone`
   - komentarz, który czytelnicy zachowali (zamknięcie przez admina) → zostaje w wątku,
     podpisany przez nikogo, a odnośnik boba do niego nadal się rozwiązuje
4. **Montaż bez mechanizmu.** `StartAccountDeletion` dostaje `FakeUserRepository`,
   `FakeSessionRepository` i **własną implementację `ContentPurge`** napisaną w tej suicie.
   Trzech słuchaczy napędza use case'y z `*-system` na fejkach z `*-domain`. Każdy melduje
   do **najprostszej możliwej listy z trzema polami do odhaczenia** (robocza nazwa
   `Checklist` — nazwa z angielskiego potocznego, nie z architektury) i dopiero
   odhaczenie wszystkich trzech wywołuje `DeleteAccount`. Ta lista MUSI nieść komentarz,
   że jest zamiennikiem tej suity, a nie decyzją o architekturze.
5. **Rejestratory eventów** dla `MemeEvents` i `CommentEvents` — lokalne dla `-2`, bo to
   jest własne okablowanie specki, nie fejk portu. To one dają właścicielowi widzieć
   „rzucił event / usłyszał".

### DECYZJE JUŻ PODJĘTE (nie otwierać od nowa)

- **Ostatni hop.** `AccountDeletionOrchestrator` (245 linii) siedzi w
  `security-infrastructure/src/main` i jest jedyną rzeczą, która dziś zbiera werdykt
  portalu i woła `DeleteAccount`. Proponowałem przeniesienie go do `security-system`
  i **sam to wycofałem**: ten orkiestrator JEST mechanizmem (kworum, zatrzask, dedup
  potwierdzeń, retry), a specka ma go nie dotykać, bo właśnie o nim nie zdecydowano.
  `-2` woła `DeleteAccount` wprost z punktu 4, a to, kto go woła na produkcji, jest
  **dziurą nazwaną wprost w pliku `.feature`**, nie przeoczeniem.
- **`TagRepository` i `MemeContentIndex` zostają na mockach.** Tak zapadło 28.09
  (`ANALIZA-2026-09-28-MOCKI-I-GRANICE-SEMANTYKI.md` §4: to, co ukrywają, jest obietnicą
  jednego serwisu należącą o poziom niżej), a `-2` nic o tagach ani o indeksie nie
  twierdzi. Odwrócenie tego wymaga zgody właściciela.
- **Nieudany hop nic nie cofa.** `Then alice's memes stay gone`. Na sucho nic nie wraca,
  a to, że cofanie jest nierozstrzygnięte, stoi komentarzem w feature'rze — milczenie
  w tym miejscu byłoby gorsze niż zły wybór.

### PYTANIA OTWARTE (dla właściciela)

- `collections-system` ma **zero testów** własnych (jedyny moduł `*-system` bez). Dopisać
  mu własne, czy niech go pokrywa dopiero `-2`?
- Czy `-2` ma w ogóle ruszać `offboarding`? Przy punkcie 4 nie jest potrzebny — lista
  z trzema polami zastępuje go w całości. To upraszcza, ale oznacza, że `-2` nie dotyka
  ani jednej linii prawdziwego kodu zbierającego.
- Hybryda AsciiDoc z założenia nr 5 — wrócić do niej, gdy pierwsza suita będzie zielona?

### PUŁAPKI (wpadliśmy, nie wpadaj drugi raz)

- **`grep -r` nie widzi zagnieżdżonych repo** (ugrep + `.gitignore`). Szukaj przez
  `find . -name "*.java" -print0 | xargs -0 grep`.
- **`git stash` w `portal/` nie rusza zagnieżdżonych repo** — `microservice-*` to osobne
  repozytoria. Zmianę trzymaną w trzech repo trzeba stashować w trzech.
- **Stare raporty surefire kłamią.** Zliczanie `**/target/surefire-reports/*.txt` złapało
  czerwony raport z 28.09 po klasie, która od tego czasu została przemianowana. Przed
  liczeniem: `find . -type d -name surefire-reports -prune -exec rm -rf {} +`.
- **`memes-ui` zatrzymuje reaktor** i zabiera ze sobą `memes-infrastructure` (potrzebuje
  jego webjara). Buduj z `-fae`, a `memes-infrastructure` dobij osobno
  (`mvn -o install -pl memes-infrastructure` w `microservice-memes`).
- **Wrapper 3.9.16 nie jest pobrany** i chce sieci. Używaj
  `/home/robert/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn`.
- **Artefakty idą przez `~/.m2`** — `install` w `shared/`, potem `install` w `portal/`,
  dopiero potem suity, które czytają `test-jar`y.
