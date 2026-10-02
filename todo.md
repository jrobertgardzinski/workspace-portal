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

### ZROBIONE 2026-10-02 — moduł stoi, obie suity zielone

`portal/account-closure-2/` + `portal/specs-2/` (2 pliki `.feature`, 16 scenariuszy, **0 upadków**).
Moduł wszedł do reaktora `workspace-portal` jako ostatni, obok `portal-specs`, bez parenta.
Zależy **wyłącznie** od `*-domain` (+ ich `test-jar`y z fejkami) i `*-system` — plus `purge-rule`
z `portal-libs` i `email-domain`/`user-id`/`password-domain` z kernela. Zero `*-application`,
zero `offboarding`, zero `account-closure`, zero wire'u.

**Co stoi w `specs-2`** (`closing-an-account.feature` 230 linii, `deleting-a-meme.feature` 70):
łańcuch główny (ukryj ×3 → konto → zniszcz ×3) wraz z krokiem kaskady boba; odnośnik boba znika
z memem, nie z kontem; konto nie daje się usunąć → wszystko wraca; jeden serwis nie ukrył →
konto stoi, nic nie zniszczone; poddanie się → wraca; konto usunięte a jeden serwis nie zniszczył
→ konto NIE wraca, treść czeka ukryta; dwa klucze (adres nie prowadzi już nikąd, treść dalej
znajdowalna po id); admin zachowuje komentarz, który czytelnicy zachowali, i odnośnik boba do
niego się trzyma; to samo zażądane przez alice jest ignorowane; trzy race'y — mem wrzucony po
ukryciu zostaje, odnośnik zapisany po ukryciu zostaje i jest zliczony (`leftBehind`), autor
usuwa własny ukryty mem i słyszy „nie ma takiego mema", a po cofnięciu zamknięcia mem wraca.
Drugi plik: pełna kaskada mem → wątek → odnośniki (dwa osobne kroki collections), to samo dwa
razy nic nie zmienia i nie ogłasza drugi raz, mem bez wątku nie ogłasza nic o komentarzach,
mem którego nie ma nie dociera do nikogo.

**Czego NIE ma w specce, zgodnie z założeniem 3:** ani jednego słowa o mechanizmie. Trzy dziury
są nazwane wprost w plikach: kto usuwa konto po trzech ukryciach, co wreszcie niszczy treść
zostawioną po usunięciu konta, i czy treść powstała w trakcie zamknięcia ma je przeżyć.

**Montaż (punkt 4 planu, zrealizowany):** `world.Checklist` — trzy pola i jeden callback, z
komentarzem klasowym mówiącym wprost, że jest zamiennikiem tej suity, nie decyzją; trzecie
odhaczenie woła `DeleteAccount` (wszystkie **dziewięć** repozytoriów), odmowa wiersza → werdykt
„konto zostaje" i treść wraca. `world.Closures` to własny `ContentPurge`: mapuje `Email → UserId`
**raz**, na początku, i trzyma — niszczenie idzie po `DeleteAccount`, więc wiersza już nie ma.
`world.Accounts` to `FakeUserRepository` z jednym przełącznikiem (`refuseDeletion`), bo fejk jest
`final` i zawsze potrafi usunąć. `PurgeRule` budowane wprost z rekordów (`KeepPopularAnonymized`),
nigdy przez `parse`. `TagRepository` i `MemeContentIndex` na mockach, zgodnie z wyrokiem 28.09.

**Odstępstwo od planu, świadome:** race „bob zapisuje mema alice po tym, jak memes skończyło"
napisany jest jako **alice** zapisująca odnośnik po ukryciu swoich. `PurgeUserItems.Closure.leftBehind`
liczy aktywne odnośniki LEAVERA, nie obcego, więc tylko ta wersja trafia w mechanizm, o którym
plan mówił. Wersja z bobem nie miałaby czego zliczyć.

**Dziura znaleziona po drodze, naprawiona w dwóch zagnieżdżonych repo** (to był pierwszy czerwony
test i NIE był dziurą w specce): `FakeMemeRepository.deleteById` oraz `FakeCommentRepository.delete`
/`deleteByMeme` zostawiały po zniszczonym wierszu **sierocy znacznik** w mapie `marks`, więc
`isMarked(id)` odpowiadał `true` o treści, której już nic nie trzyma. W schemacie status jest
KOLUMNĄ wiersza, więc adapter nie potrafi osiągnąć tego stanu. Dodany `protected forgetMark(id)`
w obu `Fake*Erasure` i wołany przy każdym delete (`microservice-memes`, `microservice-comments`).
Bez tego kryterium z założenia 8 („gone = nie ma w mapie") jest niesprawdzalne. `portal-specs`
przechodzi bez zmian — jego `snapshot()` robi zdjęcie PRZED robotą, więc znacznik i tak w nim jest.

**Stan zieleni:** portal **1060 testów Java, 0 upadków** (`portal-specs` zielony, `memes-infrastructure`
dobity osobno: 238/0). `memes-ui` (npm) czerwony niezależnie od zmian.

**Co ta suita naprawdę dowodzi o produkcie** (reszta dowodzi własnego okablowania): `StartAccountDeletion`
tylko znaczy i gasi sesje; `DeleteAccount` czyści dziewięć repozytoriów; `Mark*`/`Purge*` działają
na `pendingOf`, więc „zniszczyć coś, czego nie ukryto" jest nienapisywalne; `Restore*` z trzech
`*-system` faktycznie przywraca to, co `Mark*` ukrył — **i to pierwszy raz, gdy ktokolwiek je woła
z drugiej strony**; kaskada memes → comments → collections działa na fejkach; a kolejność z
`.feature` jest tą, którą dzisiejszy orkiestrator łamie (niszczy przed `DeleteAccount`) — specka
tego nie naprawia, tylko nazywa.

### CO DALEJ (nic z tego nie jest zaczęte)

1. **Pokazać właścicielowi oba pliki `.feature`** i zapytać, czy to jest ten rozmiar i ten język.
   To było kryterium odbioru („osoba techniczna, która chce w pięć minut zobaczyć łańcuch").
2. **Trzy dziury nazwane w plikach** czekają na decyzje, nie na kod.
3. Hybryda AsciiDoc z założenia 5 — teraz pierwsza suita jest zielona, więc pytanie wraca.

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

- `collections-system` ma **zero testów** własnych (jedyny moduł `*-system` bez). `-2` wywołuje
  teraz wszystkie cztery jego use case'y, więc nie jest już niepokryty — ale pokrywa go SĄSIAD,
  z innego repozytorium. Dopisać mu własne u siebie, czy uznać to za wystarczające?
- ~~Czy `-2` ma ruszać `offboarding`?~~ **NIE** — rozstrzygnięte 2026-10-02 przy budowie:
  `world.Checklist` zastępuje go w całości i mówi o tym wprost we własnym javadocu. Cena jest
  taka, jak przewidziana: `-2` nie dotyka ani jednej linii prawdziwego kodu zbierającego
  werdykt, więc o nim nie dowodzi niczego i nie udaje, że dowodzi.
- Hybryda AsciiDoc z założenia nr 5 — pierwsza suita jest zielona, więc pytanie wraca.

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
