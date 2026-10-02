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
   wdrożymy później." `MARK`/`ERASE` jako **nazwy faz** zostają zakazane, ale to, co za nimi
   stoi, jest faktem, nie mechanizmem (zmiana z 2026-10-02, patrz założenie 4): treść jest
   najpierw **ukryta, nie zniszczona**, i może wrócić. Specka mówi `hidden` / `out of sight` /
   `back` / `destroyed` — słowami o stanie treści, nie o fazie protokołu. W jednej transakcji
   to samo zdanie jest prawdą za darmo: ukrycie to niezacommitowany delete, powrót to rollback.
   Javadoc `security-domain/.../port/ContentPurge.java`: z jedną bazą „every part of the saga
   above becomes unnecessary" — saga tylko odgrywa to, co transakcja dostaje gratis.
4. **Co zostaje, gdy się mechanizm zdejmie — i to jest cała treść specki:**
   kolejność faktów i co jest prawdą pomiędzy. Dwa zdania:
   **Nic nie jest zniszczone, dopóki konto nie zniknie. Jeśli konto nie może zniknąć,
   wszystko wraca.** (Decyzja właściciela z 2026-10-02: „usuwamy miękko memy alice, nie
   udaje nam się usunąć jej konta, więc bezproblemowo przywracamy memy alice".) Konto
   **przeżywa** swoją treść w galerii, ale **nie przeżywa jej w bazie** — kolejność jest:
   ukryj wszystko → usuń konto → dopiero teraz niszcz. To jest zgodne z transakcją
   (rollback = powrót) i to saga ma do tego dorosnąć, nie odwrotnie.
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
   **„Gone" znaczy „nie ma w mapie", nigdy „repozytorium nie widzi".** `FakeMemeRepository`
   i `FakeCommentRepository` nie widzą zaznaczonego rekordu (jak widok `active_*` w adapterze),
   więc asercja przez `find`/`findMetadata` przechodzi już po samym zaznaczeniu, gdy nic nie
   zniknęło. „Alice's memes are gone" = `!isMarked(id)` i `pendingOf(alice)` puste i
   `activeOf(alice)` puste — czyta `FakeMemeErasure`, nie `MemeRepository`.
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

**Jak w to wejść (zielone światło właściciela 2026-10-02):**
- Najpierw szkielet modułu i JEDEN scenariusz główny (ukryj ×3 → konto → zniszcz ×3) do
  zieleni. Rozgałęzienia i race'y dopiero potem — jeśli główny łańcuch nie przejdzie przez
  glue, reszta nie ma sensu.
- Przed glue przeczytać sygnatury `Restore*` w trzech `*-system` — nie były weryfikowane,
  wiadomo tylko, że istnieją. `Mark*`, `Purge*`, `DeleteAccount`, `StartAccountDeletion`
  i fejki są sprawdzone.
- Pytania otwarte (niżej) nie blokują i `-2` ich nie rozstrzyga po drodze; co z nich
  wypłynie, idzie do tego pliku, nie do kodu.
- **Pierwszy czerwony test będzie kusił, żeby naprawić feature pod kod** (dopisać fazę,
  zlać dwa kroki). Nie. Kolejność i słownictwo są decyzją właściciela — jeśli kod się nie
  zgadza, to kod jest dziurą do nazwania w feature'rze, nie specka do zmiany.


1. **Założyć moduł** `portal/account-closure-2/` + `portal/specs-2/` na `.feature`
   (wzorem `portal-specs` + `portal/specs`: `build-helper` dokłada `../specs-2` jako
   test-resource). Bez parenta — `workspace-portal` jest czystym agregatorem.
2. **Napisać dwie suity.** Szkic scenariusza głównego, zaakceptowany przez właściciela
   („podoba mi sie ostatni gherkin"), jest w historii sesji i brzmi tak:
   `Given alice has 2 memes, 3 comments and 4 saved references` /
   `And bob saved one of alice's memes` /
   `When the closure of alice's account is requested` → `Then alice is still in the user repository` /
   `When memes has hidden alice's content` → `Then alice's memes are out of sight`
   `But the meme repository still holds them` `And alice is still in the user repository` /
   (to samo dla comments) / (to samo dla collections) → `And only now is alice gone from
   the user repository` `And alice is gone from the session, factor and recovery-code
   repositories` `But the portal still holds 2 memes, 3 comments and 4 references of hers` /
   `When memes has destroyed alice's content` → `Then alice's memes are gone` / (comments,
   collections) → `And nothing of alice is left anywhere`.
   Szkic sprzed 2026-10-02 (`has finished` → od razu `gone`, konto na końcu) jest
   nieaktualny: niszczył przed usunięciem konta, więc nieudane usunięcie konta zastawało
   memy już bez bloba. `When <serwis> has hidden/destroyed` to „ten kawałek roboty się
   wykonał" — zero wiedzy o tym, czy to były eventy, komendy, czy wywołanie metody.
   **Odnośnik boba NIE znika przy comments** (szkic z sesji miał go tam i to był błąd).
   Łańcuch w kodzie: `PurgeUserContent` → `MemeEvents.memeDeleted(id)` → collections
   `PurgeDeletedItem("meme", ids)` (dziś `CascadeConsumer`). Comments nie mają z tym nic
   wspólnego. Collections mają więc w tej historii **dwa osobne kroki**: reakcja na mema
   alice (kaskada, `When collections has heard that alice's meme is gone` → `Then bob's
   reference to it is gone`) i własne zamknięcie (`PurgeUserItems`, zapisy alice). To jest
   dokładnie rozgałęzienie, które ma być widoczne — nie zlewać ich w jeden krok.
3. **Rozgałęzienia edge case'owe**, w tym samym języku. Co najmniej:
   - jeden serwis nie ukrywa → konto zostaje, nic nie jest zniszczone; gdy zamknięcie się
     poddaje, to co ukryte wraca: `Then alice's memes are back in the gallery`
   - **konto nie daje się usunąć** (`FakeUserRepository` odmawia `deleteByEmail`) → wszystko
     ukryte wraca, `Restore*` z trzech `*-system` wreszcie ma kto wołać. To jest scenariusz,
     dla którego właściciel odwrócił decyzję „nic nie wraca".
   - konto usunięte, jeden serwis nie niszczy → konto zostaje usunięte (nie wraca — hashy
     sekretów nie wolno przywracać), treść zostaje ukryta i czeka. Co ją wreszcie zniszczy,
     jest **dziurą nazwaną w feature'rze** (dziś: `WatchErasureBacklog` ją tylko liczy).
   - komentarz, który czytelnicy zachowali (zamknięcie przez admina) → zostaje w wątku,
     podpisany przez nikogo, a odnośnik boba do niego nadal się rozwiązuje
   - **konto ma dwa klucze, treść zna jeden.** `StartAccountDeletion` dostaje `Email`,
     `*-system` biorą `UserId`; mapuje `userRepository.findBy(email).map(User::id)` (tak robi
     dziś `AccountDeletionOrchestrator`). Jeśli użytkownik zniknie, zanim treść odczyta id,
     nie ma czym go odnaleźć — to jest fakt sprzed decyzji o architekturze i zasługuje na
     scenariusz, nie na komentarz.
3a. **Race'y — bo po to ten moduł jest.** Na sucho race to permutacja kolejności kroków, i
   lista z punktu 4 daje ją za darmo: każde `When <serwis> has finished` to osobny krok, więc
   wystarczy przestawić. Trzy kandydaty, które wynikają z kodu, nie z wyobraźni:
   - bob zapisuje mema alice **po** tym, jak memes skończyło, a collections jeszcze nie —
     `PurgeUserItems.Closure.leftBehind` istnieje dokładnie dla tego okna (gate offline,
     token w zakładce żyje do `exp`).
   - alice wrzuca nowego mema po ukryciu starych → nie należy do zamknięcia, zostaje
     (`PurgeUserContent` działa tylko na `pendingOf`). Czy to jest obietnica, czy dziura —
     zadać właścicielowi, ale najpierw pokazać w Gherkinie.
   - alice usuwa własnego mema w trakcie zamykania konta → `DeleteMeme.findMetadata` nie
     widzi ukrytego, odpowiada `NO_SUCH_MEME`; mem i tak zniknie z zamknięciem, ale autor
     dostał „nie ma takiego mema" o czymś, co jeszcze jest — a jeśli zamknięcie się cofnie,
     mem wraca, choć autor chciał go usunąć.
4. **Montaż bez mechanizmu.** `StartAccountDeletion` dostaje `FakeUserRepository`,
   `FakeSessionRepository` i **własną implementację `ContentPurge`** napisaną w tej suicie.
   Trzech słuchaczy napędza use case'y z `*-system` na fejkach z `*-domain`. Każdy melduje
   do **najprostszej możliwej listy z trzema polami do odhaczenia** (robocza nazwa
   `Checklist` — nazwa z angielskiego potocznego, nie z architektury) i dopiero
   odhaczenie wszystkich trzech wywołuje `DeleteAccount`; udane usunięcie konta każe trzem
   słuchaczom niszczyć, odmowa — przywracać. Ta lista MUSI nieść komentarz, że jest
   zamiennikiem tej suity, a nie decyzją o architekturze.
   **Jeden krok Gherkina = jeden use case z `*-system`**, i tu akurat kod pasuje do specki
   jak ulał: `has hidden` = `Mark*ForErasure`, `has destroyed` = `Purge*` (działa tylko na
   `pendingOf`, czyli na tym, co ukryto — więc „zniszczyć coś, czego nie ukryto" nie da się
   nawet napisać), `are back` = `Restore*`. Żadnego sklejania dwu use case'ów w jeden krok.
   Własny `ContentPurge` robi mapowanie `Email` → `UserId` przez `FakeUserRepository.findBy`
   **raz, na początku**, i trzyma je — bo niszczenie idzie PO `DeleteAccount`, a wtedy
   użytkownika już nie ma, czym odczytać id (patrz 3, kreska „dwa klucze"). `PurgeRule` do scenariusza admina budować wprost z
   `portal-libs/purge-rule` (rekordy są publiczne; `parse` jest pakietowe i niepotrzebne) —
   żadnego `PurgeChoices` → tekst → `parse`, bo ta droga wiedzie przez `*-application`.
5. **Rejestratory eventów** dla `MemeEvents` i `CommentEvents` — lokalne dla `-2`, bo to
   jest własne okablowanie specki, nie fejk portu. To one dają właścicielowi widzieć
   „rzucił event / usłyszał".
   **`PurgeUserComments` i `DeleteThread` nie emitują** — zwracają ids (`Purged.deletedByMeme`,
   `List<String>`), a publikuje wołający (dziś `CommentsDeletionParticipant`, bo outbox musi
   dzielić los delete'a). W `-2` publikuje więc glue słuchacza comments, zaraz po use casie.
   `MemeEvents.memeDeleted` dla odmiany woła sam `PurgeUserContent`/`DeleteMeme`. Ta
   asymetria jest w produkcie, nie w specce — nie wyrównywać.

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
- **Nieudane zamknięcie przywraca wszystko — ODWRÓCONE 2026-10-02.** Do tego dnia stało tu
  „nieudany hop nic nie cofa, `alice's memes stay gone`". Właściciel odwrócił: treść jest
  ukryta, nie zniszczona, dopóki konto stoi; nie da się usunąć konta → treść wraca. Powód:
  to jest to samo, co daje transakcja (rollback), więc specka sprzed decyzji o architekturze
  ma to powiedzieć. Jedyny wyjątek, nazwany w feature'rze: konto już usunięte, niszczenie
  się nie kończy → konto nie wraca (sekrety), treść czeka ukryta.

- **Co ta suita dowodzi o produkcie, a co o sobie.** `DeleteAccount` nie zna `ContentPurge`,
  więc „alice is still in the user repository" po każdym hopie dowodzi wyłącznie listy
  z punktu 4, czyli własnego okablowania specki. O produkcie dowodzi trzech rzeczy:
  `StartAccountDeletion` tylko znaczy (`markPendingDeletion`), `DeleteAccount` czyści
  dziewięć repozytoriów, kaskada treści (memes → comments → collections) działa na fejkach
  — i czwartej: `Restore*` z trzech `*-system` faktycznie przywraca to, co `Mark*` ukrył
  (dotąd nikt poza testami jednostkowymi tego nie wołał z drugiej strony).
  Kolejność hopów to obietnica specki do spełnienia przez przyszły mechanizm — i tak ma stać
  w `.feature`, obok dziury z punktu „ostatni hop". **Dzisiejszy kod łamie tę kolejność**:
  orkiestrator niszczy (ERASE) PRZED `DeleteAccount`, więc nieudane usunięcie konta zastaje
  memy bez bloba. `-2` tego nie naprawia, tylko nazywa — komentarzem przy scenariuszu „konto
  nie daje się usunąć".

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
