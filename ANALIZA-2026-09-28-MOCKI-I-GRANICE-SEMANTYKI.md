# Mocki i granice semantyki — 28 września 2026

> **Czym ten plik jest:** odpowiedzią na trzy pytania zadane po domknięciu semantyki usuwania konta
> i usuwania mema — co jeszcze zostało do sprawdzenia przed decyzją architektoniczną, czy DRY jest
> zachowane, i czy te testy przeżyją decyzję „mikroserwisy / saga / Kafka".
>
> **Czym nie jest:** planem naprawczym z numerowanymi znaleziskami. Nic tu nie jest przypisane do
> paczki. To jest zapis tego, co wiadomo na dziś, żeby decyzja o architekturze zapadała przy
> otwartych oczach.
>
> **Teza, która okazała się spinać wszystkie trzy pytania:** każdy mock w `portal-specs` stoi
> dokładnie tam, gdzie kończy się sprawdzalność decyzji portalu. Nie są rozsiane losowo — leżą na
> granicach. To nie było widać, dopóki dwóch z nich się nie usunęło.

---

## 0. Co się wydarzyło tego dnia i dlaczego to zmienia lekturę

`Portal.java` budował prawdziwych uczestników obu protokołów i podawał im cztery grupy mocków.
Tego dnia dwie z nich zostały zastąpione fake'ami (`FakeVoteRepository`,
`FakeCommentVotes`, `FakePurgePolicyOverride`, wszystkie z test-jarów serwisów).

Efekt był większy niż sprzątanie. **`KEEP_POPULAR_ANONYMIZED` — jedna z trzech reguł `PurgeRule`
i jedyna, która czyta wynik głosowania — nie występowała w żadnym pliku `.feature` w całym
portalu.** Nie dlatego, że ktoś zapomniał. Dlatego, że przy `mock(VoteRepository)` metoda `scoreOf`
zwraca 0 na zawsze i nie było czym ustawić memowi popularności.

To jest wzorzec, nie incydent. Mock nie „upraszcza testu" — **usuwa z niego jedno wejście, a razem
z nim wszystkie decyzje, które od tego wejścia zależą**. Nikt nie odnotowuje, że coś zniknęło,
bo test dalej jest zielony.

Dlatego hipoteza „jak zrezygnujemy z mocków, część rzeczy stanie się zrozumiała" jest trafna, i
niżej jest napisane dokładnie **które** rzeczy i **dlaczego akurat te**.

---

## 1. Co jest nad semantyką, a wciąż przed decyzją o architekturze

Semantyka każdego z dwóch protokołów jest opisana i przetestowana osobno. Piętro wyżej — i wciąż
bez potrzeby wiedzy, czy portal to sześć serwisów, czy jeden proces — leży **semantyka portalu,
kiedy oba protokoły działają na tych samych wierszach**.

### 1.1 Styk: zamknięcie konta uruchamia kaskadę usuwania mema

`PurgeUserContent.java:92` — w fazie ERASE, dla **każdego** kasowanego mema, woła
`memeEvents.memeDeleted(...)`. Jedno zamknięcie konta startuje N kaskad.

W specach tego nie widać, bo `Portal.java:104` wstawia tam `mock(MemeEvents.class)`, i to samo robi
`MemesClosureParticipantTest.java:47`. Do tego „jeden świat" jest jedną *klasą*, ale dwiema
*instancjami*: `ClosureInOneProcess.java:76` i `DeletionInOneProcess.java:35` każdy robi własne
`new Portal()`.

Zdania, których nikt nie wypowiedział ani nie sprawdził:

- Zamknięcie konta kasuje wątki komentarzy **innych ludzi** — tych pod memami odchodzącego.
  `account-closure.feature` nigdzie tego nie mówi. Tak działa.
- Kaskada zdejmuje zapisane referencje z kolekcji **osób, które nie odchodzą**. Pole `reserved`
  w `ClosureConfirmation` tego nie liczy: potwierdzenie mówi „schowałem 40", a realnie znika
  znacznie więcej.
- Kaskada rusza **za piwotem**, wewnątrz ERASE. Ostatni odcinek zamknięcia konta jest więc
  choreografią bez kompensacji i bez potwierdzeń, a orchestrator i tak wysyła do identity
  `PORTAL_CONTENT_PURGED`. Saga obiecuje kompletność, której jej ostatni hop nie umie dowieźć.
- Mem, którego reguła **nie** kasuje (`anonymise`), nie wysyła nic — wątek zostaje. Kontrast
  z poprzednim punktem wart osobnego scenariusza.
- Przeplot MARK → (ktoś kasuje mema odchodzącego) → ERASE: hop komentarzy zdejmuje wątek
  zawierający komentarze już zarezerwowane przez sagę. `pendingOf(leaver)` znajduje wtedy wiersze,
  których nie ma, a potwierdzenie z liczbą poszło dawno temu.
- RESTORE po tym, jak autor sam skasował swojego mema w trakcie czekania: saga oddaje konto
  z memem bez wątku i bez zapisanych referencji. To decyzja, nie bug — ale nigdzie jej nie ma.

**Ten punkt był wprost zablokowany przez `mock(MemeEvents.class)`.** Drut jest już zszyty
(patrz §6): `ClosureInOneProcess` buduje `DeletionInOneProcess` nad tym samym `Portal`em i podaje
uczestnikowi memów prawdziwy port. Sześć powyższych zdań jest teraz wypowiedzianych w
`account-closure.feature` — z dwoma poprawkami, które wyszły dopiero przy pisaniu, opisanymi
w §6.2.

### 1.2 Asymetria, która jest dziurą w danych, nie w stylu

`PurgeUserComments` nie ma żadnego portu zdarzeń. Kasowanie mema ma hop
`COMMENTS_DELETED → kolekcje`; zamknięcie konta go nie ma.

Skutek: komentarze odchodzącego (pod cudzymi memami) znikają, a referencje do nich w cudzych
kolekcjach zostają **na zawsze**. Nikt ich nigdy nie sprząta. `CollectionsClosureParticipant`
czyści wyłącznie referencje *odchodzącego*, po jego `user_id`.

Widać to dopiero patrząc na oba protokoły naraz — czyli dokładnie z tego poziomu, którego dziś
nie ma.

**Rozstrzygnięte tego samego dnia: port dodany.** Szczegóły i dlaczego akurat tak — §6.4.

---

## 2. DRY

### 2.1 Gdzie jest — i gdzie wygląda inaczej, niż jest

W kodzie produkcyjnym reuse **jest i jest dobry**: `PurgeUserContent` nie implementuje kaskady
drugi raz, tylko woła ten sam port `MemeEvents`, przez który idzie `DeleteMeme`. Zamknięcie konta
nie powtarza usuwania mema — ono je *uruchamia*.

Problem: **nic poniżej e2e tego nie dowodzi.** W obu miejscach, gdzie ten port mógłby zadziałać
w teście, stoi mock. Reuse istnieje w kodzie, nie istnieje w dowodzie. To jest ta sama granica
co w punkcie 1.1, widziana z drugiej strony.

Reużyte i sprawdzone: biblioteka `meme-deletion` (jedno słownictwo dla obu hopów i obu kontraktów),
`account-closure`, `unit-of-work`, `purge-rule`, `observation`, `user-id`, oraz `Portal` jako jedna
kopia trzech fake'ów.

### 2.2 Dług, który zostaje

- ~~**`ClosureOutcome` w trzech kopiach**~~ — **jedna kopia w `account-closure`, §6.7(c).**
- ~~**`requestedRule` skopiowane słowo w słowo**~~ między uczestnikami, ~~tak samo `markAndConfirm`
  i cały szkielet guard / `isAddressed` / switch w trzech uczestnikach~~ — **§6.7(c):** szkielet
  w `account-closure` (`ClosureParticipant` / `AtomicClosureParticipant`), reguła
  w `portal-libs/purge-rule` (`RequestedRule`). Biblioteka objęła *zachowanie*, nie tylko
  *wiadomości*.
- ~~**Brakujący port w comments przy zamknięciu konta**~~ — punkt 1.2, **dodany, §6.4.** To był
  reuse, którego brak miał konsekwencję w danych, nie w liniach kodu.
- **Dwa pojęcia „użytecznego id na drucie"**: `Ids.usable` (package-private w `meme-deletion`)
  i `ClosureCommand.userIdOf`.

Kolejność sprzątania: 1.1 i 1.2 przed czymkolwiek z 2.2. Tamte dwa dotyczą danych, te są kosmetyką.

---

## 3. Czy testy semantyczne przeżyją decyzję „mikroserwisy / saga / Kafka"

Na to pytanie nie trzeba odpowiadać hipotetycznie: wyższy poziom **już istnieje w repo** —
`KafkaMemeEvents`, `KafkaMemeDispatch`, outbox, orchestrator `EventsRouter`, trzy
`PurgeConfirmations`, pakty (`microservice-*/pacts/`) i `e2e/`.

### 3.1 Przenosi się 1:1

**Kod produkcyjny pod testem.** `Portal.java` buduje te same klasy, które w deployu odpala
`SagaParticipantConfig` z Kafką. Decyzja architektoniczna nie dotyka ani jednej linii
w `MemesClosureParticipant`, `CommentsDeletionParticipant`, `PurgeUserContent`, `DeleteThread`.

**Słownictwo.** `MemeDeleted.fields()` woła `KafkaMemeEvents`, `CommentsDeleted.fields()` →
`KafkaCommentEvents`, `ClosureConfirmation.fields()` → trzy adaptery i `PurgeCommandsConsumer`.
Zestaw pól na drucie ma jedną definicję, wspólną dla specek i transportu. To jest mechanizm,
przez który poziom semantyczny w ogóle *przewiduje* poziom wyższy.

### 3.2 Nie przenosi się

**Pliki `.feature`.** `e2e/features/` ma własne trzy, w JS, napisane językiem człowieka
(„A meme I saved disappears from my favourites"), nie językiem protokołu. I tak powinno zostać —
tabelka w `specs/README.md` sama to rozdziela. Reuse idzie przez kod i słownictwo, nie przez
Gherkin.

### 3.3 Czego poziom semantyczny nie udowodni — lista ryzyka „zielone specy, czerwona produkcja"

1. **Partycjonowanie na styku protokołów.** `KafkaMemeEvents` kluczuje kaskadę po `memeId`
   (`KafkaMemeEvents.java:93`), `PurgeConfirmations` kluczuje sagę po `sagaId` / id odchodzącego
   (`PurgeConfirmations.java:124`). Scenariusze z punktu 1.1 przechodzą ze strumienia kluczowanego
   userem do strumienia kluczowanego memem. **Żaden schemat partycjonowania tego nie uporządkuje.**
   Cokolwiek napiszemy o kolejności na tym styku w jednym procesie, Kafka tego nie odziedziczy.
   To jedyne miejsce, gdzie poziom semantyczny będzie wprost optymistyczny — i warto, żeby sam
   plik `.feature` to mówił, zamiast milczeć.
2. **Jednostka pracy.** `Runnable::run` nie umie się wycofać. Obietnica
   `CommentsDeletionParticipant`, że ogłoszenie dzieli los skasowania wątku „w obie strony", jest
   nierozstrzygalna na fake'ach — dowozi ją dopiero outbox (`KafkaMemeEventsTransactionTest`).
3. **Serializacja omija specy kaskady.** Bus w `DeletionInOneProcess` przekazuje obiekty
   `MemeDeleted`, nigdy `fields()`. Jedyne miejsce w `portal-specs`, gdzie powstaje prawdziwa mapa,
   to `ClosureInOneProcess`. Specy zamknięcia konta są pod tym względem mocniejsze niż specy
   kaskady.
4. **Współbieżność.** `everyHopAnswers()` drenuje do punktu stałego, jednowątkowo. Idempotencja
   jest sprawdzona w sekwencji, nie w wyścigu dwóch partycji w jednej grupie konsumentów.
5. **Eventual consistency.** Asercje „nic nie ogłoszono" są asercjami po wyciszeniu; na e2e stają
   się odpytywaniem z timeoutem. Decyzje się przenoszą, asercje nie.

Punkty 3–5 są częściowo pokryte warstwą paktów — i to ona, nie e2e, jest właściwym miejscem, gdzie
semantyka spotyka transport.

### 3.4 Kierunek odwrotny

Jeśli decyzja pójdzie w stronę **zwinięcia portalu w jeden deployable**, `portal-specs` nie tyle
zostaje reużyte, co **awansuje**: staje się suitą akceptacyjną tego deployable'a, a e2e kurczy się
do sprawdzenia paru adapterów. `specs/README.md` dokładnie to obiecuje. To jedyny scenariusz,
w którym te testy idą w górę — i mocny argument, żeby przed decyzją domknąć 1.1 i 1.2, bo inaczej
suita akceptacyjna monolitu będzie miała dziurę dokładnie tam, gdzie oba protokoły się spotykają.

---

## 4. Mocki jako mapa granic

Po zmianach z 28.09 w `Portal.java` zostały trzy:

| mock | co przez niego nie istnieje | gdzie to należy |
|---|---|---|
| ~~`MemeEvents`~~ | zdjęty tego samego dnia — patrz §6 | — |
| `MemeContentIndex` (`:103`, `:130`) | „po zamknięciu konta da się wgrać ten sam obrazek ponownie, bo indeks dedup go zapomniał" | `microservice-memes/specs/account-erasure.feature` — obietnica jednego serwisu |
| `TagRepository` (`:103`, `:131`) | to samo dla indeksu tagów | jw. |

A zdjęte tego dnia dawały:

| zdjęty mock | co odzyskane |
|---|---|
| `VoteRepository`, `CommentVotes` | `KEEP_POPULAR_ANONYMIZED` — trzecia reguła `PurgeRule`, dotąd bez ani jednego scenariusza |
| `PurgePolicyOverride` | kolejność rozstrzygania reguły: komenda → dial admina → default (P18 poz. 18) |

**To jest odpowiedź na hipotezę.** Rezygnacja z mocków nie robi testów „ładniejszymi" — ona
przywraca wejścia, bez których pewnych zdań nie da się wypowiedzieć. Każdy mock to jedno wejście
mniej i cały zbiór decyzji zależnych od niego wypadający z zasięgu specyfikacji, po cichu, przy
zielonym buildzie.

Dwa mocki zostają celowo (`MemeContentIndex`, `TagRepository`) — nie dlatego, że są nieszkodliwe,
tylko dlatego, że to, co odsłaniają, jest obietnicą *jednego serwisu* i jego miejsce jest piętro
niżej. Trzeci, `MemeEvents`, był jedyną pozostałą granicą na poziomie portalu — i jedyną, której
zdjęcie wymagało realnej pracy: zszycia obu busów w jedną instancję `Portal`. Zdjęty; §6.

Zostaje też `mock(MemeEvents.class)` w `MemesClosureParticipantTest`. Świadomie: to test jednej
osi jednego serwisu, a styk, który ten mock tam ukrywa, ma już swoje miejsce piętro wyżej. Mock
jest szkodliwy wtedy, kiedy jest **jedynym** miejscem, gdzie dana granica mogłaby być
sprawdzona.

### Ryzyko odwrotne, żeby było uczciwie

Fake, który rozjeżdża się z adapterem, jest **gorszy** od mocka — bo jest przekonująco zły.
Estate ma na to wzorzec: `MemeErasureContractTest` trzyma fake i prawdziwy adapter przy jednym
kontrakcie. Nowe fake'i (`FakeVoteRepository`, `FakeCommentVotes`) takiego kontraktu **nie
mają** — niosą to ryzyko. Różnica wobec stanu sprzed 28.09 jest taka, że ryzyko jest nazwane
i w jednym miejscu, zamiast rozsiane po siedmiu anonimowych klasach.

---

## 5. Kolejność, gdyby pytać mnie

1. ~~**`MemeEvents`**~~ — **zrobione tego samego dnia, §6.**
2. ~~**Rozstrzygnięcie 1.2**~~ — **zdecydowane i zrobione tego samego dnia, §6.4:** zamknięcie
   konta ogłasza skasowane komentarze.
3. ~~**Kontrakty dla nowych fake'ów**~~ — **zrobione, §6.6.**
4. ~~**Dopiero potem** `ClosureOutcome` i `requestedRule` z punktu 2.2.~~ — **zrobione, §6.7(c).**

---

---

## 6. Co zostało zrobione po napisaniu powyższego (ten sam dzień)

Punkty 1 i 2 z §5 są zamknięte. Nie jest to plan — to zapis tego, co jest w repo, i rzeczy, które
wyszły dopiero przy robocie, a których §1.1 nie przewidział.

### 6.1 Zszycie

`ClosureInOneProcess` buduje `DeletionInOneProcess` nad **tą samą** instancją `Portal` i podaje
`world.memesClosure(...)` prawdziwy `MemeEvents`. Ten krok to trzy klasy runnera i ani jedna linia
kodu produkcyjnego (kod produkcyjny rusza dopiero §6.4):

- `Portal.memesClosure(confirmations, memeEvents)` — port zamiast `mock(MemeEvents.class)`.
- `DeletionInOneProcess(Portal)` — drugi konstruktor; bezargumentowy dalej robi własny świat, bo
  specy kaskady nie mają powodu dzielić rzędów z sagą.
- `ClosureInOneProcess` — trzyma kaskadę, **nie** drenuje jej w `everyPartAnswers()`. Saga kończy
  się na ostatnim potwierdzeniu; to, co ono uruchomiło, dalej wisi na drucie, i dopiero krok
  `the cascade reaches every part` je dostarcza. Bez tego rozdzielenia nie da się napisać zdania
  o tym, co jest prawdą *pomiędzy*.

W `account-closure.feature` przybyły z tego cztery reguły — wątki i wskaźniki obcych idące z memem
odchodzącego, liczba w potwierdzeniu kontra to, co realnie znika, kaskada za piwotem, oraz zderzenie
obu protokołów na tym samym wierszu (piąta dochodzi w §6.4). Cztery z sześciu nowych scenariuszy
czerwienieją po wstawieniu mocka z powrotem (sprawdzone), więc trzymają to, o czym mówią.

### 6.2 Dwie rzeczy, których §1.1 nie przewidział

**(a) Fake'i zamknięcia konta nadawały id, których kaskada by nie uniosła.** `Ids.usable` przyjmuje
wyłącznie kanonicznego UUID-a, a `FakeMemes.posted(prefix, …)` (wtedy jeszcze `HeapMemes`) robiło
`alice@example.com-meme-1`.
Po wpięciu prawdziwego portu całe zamknięcie konta ogłaszało dwa `MEME_DELETED`, które
`MemeDeleted.of` odrzucał po cichu — i **wszystkie asercje o styku przechodziły przez to, że nie
ogłoszono nic**. To nie jest błąd produkcji (tam id są UUID-ami); to jest dokładnie ta klasa
rzeczy, którą mock ukrywa, bo mock przyjmuje każdy string. Naprawione jednym mennikiem,
`world.ContentIds`, wspólnym dla obu busów — `MemeDeletionSteps` miał własny, identyczny co do
zamiaru.

Morał do §4: mock nie tylko usuwa wejście. On też **zwalnia test z kontraktu wyjścia** — tu przez
rok nikt nie sprawdził, czy to, co portal ogłasza przy zamknięciu konta, jest w ogóle zdatne do
wysłania.

**(b) Dwa z sześciu zdań §1.1 nie mogły się wydarzyć drzwiami, które wskazywały.** Bullet
o przeplocie MARK → ktoś kasuje mema odchodzącego → ERASE i bullet o RESTORE po tym, jak autor sam
skasował swojego mema, zakładają, że zarezerwowanego mema da się skasować. Nie da się:
`MemeRepository` czyta galerię, mem zamarkowany jest z niej niewidoczny, więc `DeleteMeme` odpowiada
`NO_SUCH_MEME` (sprawdzone wprost). To zachowanie produkcyjne, nie artefakt fake'a — widok
`active_memes` robi to samo.

Zderzenie jest natomiast realne **drugimi drzwiami**: obcy kasuje *swojego* mema, pod którym
odchodzący ma komentarz już zarezerwowany przez sagę. `deleteByMeme` jest ślepe na status —
celowo, bo kaskada kasuje cały wątek — więc wiersz znika spod sagi. Saga to przeżywa (`pendingOf`
nie wylistuje nieistniejącego wiersza), ale:

- liczba, którą portal wysłał w potwierdzeniu, była już nieprawdziwa w chwili wysłania;
- kompensacja nie odda tego, co zabrał drugi protokół — `RESTORE` oddaje trzy komentarze
  z czterech, i to jest decyzja, nie awaria.

Oba te zdania są teraz scenariuszami. Wersja z §1.1 była trafna co do *napięcia* i nietrafna co do
*mechanizmu* — co samo w sobie jest argumentem za tym, żeby takie hipotezy zamieniać na testy,
zamiast zostawiać je w pliku.

### 6.3 Słownictwo: jedno słowo na jedną rzecz

Przy okazji wyszło, że o tym samym mówiliśmy na pięć sposobów — *mock*, *fake*, *stub*,
*stand-in*, *hałda*. Ustalone i zapisane w `portal-specs/README.md`:

| słowo | co znaczy | gdzie |
|---|---|---|
| `mock` | Mockito, zero zachowania | `mock(X.class)` |
| `Fake*` | działająca atrapa in-memory, trzymana przy kontrakcie portu | `src/test/` |
| `InMemory*` | **prawdziwy** adapter trzymający wiersze w RAM-ie, nie atrapa | `src/main/` |
| stub | samodzielny serwis zastępujący stronę trzecią w deploymencie | docker/k8s (IdP, SMS) |

`portal-specs` jest zgodne: pakiet `heap` to teraz `world`, a `HeapMemes/HeapComments/`
`HeapFavourites` to `FakeMemes/FakeComments/FakeFavourites`. Słowo „heap" mówiło, *gdzie* leżą
wiersze, a nie *czym* jest klasa, która je trzyma — i dlatego zaczęło wyglądać na trzeci rodzaj
atrapy obok mocka i fake'a.

Dwie klasy, których `portal-specs` używa, zgodne wtedy **nie były**: `InMemoryCollectionRepository`
(collections-application) i `InMemorySagaStore` (offboarding-system) — to fake'i noszące nazwę
zarezerwowaną dla adapterów produkcyjnych. Siedzą w osobnych repozytoriach z własną historią
(sam offboarding ma ponad dziewięć wywołań), więc ich zmiana nazwy to commit w tamtych repo, nie
w tym. Zapisane jako dług, nie przeoczone — i **spłacone tego samego dnia, §6.7(a)**.

### 6.4 Punkt 1.2: zamknięcie konta ogłasza skasowane komentarze

Decyzja zapadła (wariant „dodać port", odrzucone: sprzątacz w kolekcjach i zostawienie tego
w spokoju). Argument, który przeważył: kasowanie mema **już** obiecuje, że wskaźniki na komentarze
znikną razem z wątkiem, a zamknięcie konta kasowało te same komentarze i nie mówiło nic. Po
zszyciu z §6.1 asymetria zrobiła się jeszcze ostrzejsza — to samo zamknięcie konta sprzątało już
skutki uboczne dla **memów** (bo reużywa kaskady), a dla **komentarzy** nadal nie.

Zmierzone przed zmianą: komentarz znika, wskaźnik obcego na niego zostaje.

Jak to zrobione — wzorem, który ten serwis już ma:

- `PurgeUserComments.execute` zwraca `Purged` — skasowane id, pogrupowane po memie, bo tym kluczuje
  się `COMMENTS_DELETED`. **Nie ogłasza samo.** Ta klasa nosi własny dekorator transakcyjny
  (`CommentsConfig`), a ogłoszenie ma dzielić los kasowania w obie strony, więc należy do unit of
  work wołającego — dokładnie tak, jak `DeleteThread` oddaje swoje id
  `CommentsDeletionParticipant`owi. Javadoc `DeleteThread` mówi to wprost i nie było powodu robić
  drugiego wzorca obok.
- Zanonimizowane **nie** są raportowane. Komentarz, który reguła zachowuje, wraca do wątku, więc
  każdy wskaźnik na niego jest dalej dobry, a ogłoszenie kazałoby kolekcjom zdjąć referencję do
  czegoś, co stoi.
- `CommentsClosureParticipant` ogłasza w swoim unit of work. Nic nowego na drucie: ta sama
  wiadomość, ten sam konsument w kolekcjach, żadnego nowego kontraktu.
- To kolejny hop **za piwotem**, bez potwierdzenia i bez kompensacji — ten sam handel, który strona
  memów już przyjęła.

Dwa scenariusze w `account-closure.feature`: wskaźnik obcego znika razem z komentarzem, i —
kontrast — komentarz, który czytelnicy zachowali, zachowuje wskaźniki na siebie. Pierwszy
czerwienieje po wycięciu ogłoszenia (sprawdzone mutacją).

Koszty poboczne, żeby było uczciwie: `PurgeCommandsListener` dostał parametr, więc trzeba było
tknąć siedem plików testowych w `comments-infrastructure`; w dwóch z nich `PurgeUserComments` jest
mockiem, a mock oddawał `null` tam, gdzie prawdziwy use case oddaje raport — stąd
`Purged.NOTHING` i jawne zaślepki. To ta sama mechanika co w §6.2(a), tylko od drugiej strony.

### 6.5 Co z tego zostaje na później

Z kolejności w §5 zostały dwie ostatnie pozycje: kontrakty dla nowych fake'ów
(`VoteRepositoryContractTest` wzorem `MemeErasureContractTest`), a potem `ClosureOutcome`
i `requestedRule` z §2.2. Doszły trzy:

- ~~Obietnica „mema zarezerwowanego przez trwającą sagę nikt nie zdejmie" nie ma nigdzie
  scenariusza.~~ **Ma, §6.7(b)** — w `microservice-memes/specs`, gdzie jej miejsce.
- ~~`InMemoryCollectionRepository` i `InMemorySagaStore` to fake'i pod nazwą zarezerwowaną dla
  adapterów produkcyjnych.~~ **Przemianowane, §6.7(a).**
- ~~Ogłoszenie z §6.4 nie ma paktu.~~ **Sprawdzone, §6.6.**

Z całej listy została jedna pozycja, i to świadomie: **dwa pojęcia „użytecznego id na drucie"**
z §2.2 (`Ids.usable` w `meme-deletion` i `ClosureCommand.userIdOf`). To jeden pomysł w dwóch
protokołach, każdy z własnym słownictwem, a nie kopia w jednym — dopóki tak jest, to jest decyzja,
nie dług.

### 6.6 Dwie pozycje z §6.5, po kolei

**(a) Kontrakty dla nowych fake'ów.** `VoteRepositoryContractTest` i `CommentVotesContractTest` —
po siedem zdań każdy, zadawane obu implementacjom: stand-inowi z test-jara i adapterowi JDBC na
prawdziwej bazie (`FakeVoteRepositoryTest` / `JdbcVoteRepositoryTest`,
`FakeCommentVotesTest` / `JdbcCommentVotesTest`, wzorem `MemeErasureContractTest`). Trzy zdania
z dawnych testów fake'ów wjechały do kontraktu bez zmian, cztery są nowe: drugi głos tego samego
wyborcy zastępuje pierwszy, wycofanie zdejmuje tylko jego kartkę, strona wyników zgadza się
z czytaniem pojedynczym (`scoresOf` / `tallyAll`), i — po stronie memów — `allScores` nazywa
dokładnie te memy, które mają kartkę.

To ostatnie zdanie **natychmiast zaświeciło na czerwono**: `FakeVoteRepository.allScores` zwracał
mem, którego ostatnią kartkę wycofano, ze score 0, a adapter go nie zwraca (wiersza już nie ma).
Ranking czyta tę odpowiedź i mem bez ani jednego głosu stawał w nim wyżej niż mem przegłosowany na
minus. Poprawione w fake'u, nie w kontrakcie — i to jest dokładnie to ryzyko z §4, złapane pierwszym
uruchomieniem, a nie za trzy miesiące. Sprawdzone mutacją: po przywróceniu starego `allScores`
kontrakt czerwienieje.

Czego kontrakty **nie** mówią, wypisane w ich javadocach, żeby asymetrie były w jednym miejscu,
a nie do odkrycia: głos na mema/komentarz, którego nie ma (to pilnuje SQL adaptera i klucz obcy —
`OrphanBallotTest`, `UnknownComment`; stand-in nie ma czego zapytać), czas publikacji w `allScores`
(stand-in go nie zna, więc żaden stand-in nie nadaje się do rankingu), i czy nieobstawione id jest
w `scoresOf` nieobecne, czy obecne z zerem (port mówi wprost, że to decyzja use case'u — kontrakt
żąda tylko, by nie czytało się jako cokolwiek innego niż 0).

**(b) Transport ogłoszenia z §6.4.** `ClosureAnnouncementMatchesTheCascadesTest`: ten sam mem i te
same id komentarzy idą oboma prawdziwymi drogami — kaskadą i zamknięciem konta — i rekordy, które
wychodzą do brokera, muszą się zgadzać: temat, klucz partycji i cała treść z dokładnością do id
koperty (na nim właśnie konsument deduplikuje, więc te dwa muszą się różnić). Rekord kaskady jest
zweryfikowany paktem konsumenta (`CommentsDeletedPactProviderTest`), więc przypięcie do niego
przenosi tę gwarancję na drugiego producenta — w tym nazwę tematu, czyli tę dziurę, którą audyt
26.07 nazwał najgroźniejszą. Drugi test przy okazji: zamknięcie, które nic nie skasowało, nic nie
ogłasza (obietnica z javadoca `eraseAndAnnounce` — puste ogłoszenie nie nosi faktu i wracałoby przy
każdym ponowieniu ERASE). Oba sprawdzone mutacją: po wycięciu ogłoszenia z uczestnika test pada.

Dlaczego **porównanie, a nie drugi provider test od paktu**: pact-jvm szuka metody
`@PactVerifyProvider` po OPISIE interakcji, skanując classpath, a konsument ma jedną interakcję
`COMMENTS_DELETED`. Dwie metody odpowiadające na ten sam opis sprawiają, że wybór między nimi jest
losowy — próba stanęła na tym od razu (weryfikator wszedł w `CommentsDeletedPactProviderTest`
zamiast we własną klasę), a gdyby trafił odwrotnie, to weryfikacja **kaskady** po cichu dowodziłaby
czegoś o rekordzie zamknięcia. Jeżeli kolekcje kiedyś nazwą osobny stan („given an account closure
erased comments"), to wraca do paktu jako druga metoda — po ich stronie.

*Pobocznie, poza tą listą:* `memes-ui` ma czerwony test (`gallery.test.tsx`, timeout 5 s) —
nietknięte, nie z tej zmiany, ale pełny reaktor memów przechodzi tylko z pominięciem tego modułu.

### 6.7 Resztki z §6.5, dokończone

**(a) Nazwy dwóch fake'ów.** `InMemoryCollectionRepository` → `FakeCollectionRepository`
(collections-application, plus `FakeFavourites` w portal-specs, które je rozszerza) i
`InMemorySagaStore` → `FakeSagaStore` (offboarding-system): razem trzydzieści miejsc w dwóch
repozytoriach. Tabela w `portal-specs/README.md` mówi teraz, że majątek mówi jednym słowem, zamiast
wyliczać dwa wyjątki; javadoc `FakeFavourites` stracił akapit tłumaczący, dlaczego nazywa się inaczej
niż klasa, którą rozszerza. Przy okazji zniknęło „heap" z javadoców obu klas — słowo wycofane
w §6.3, a w tekście zostało.

**(b) Scenariusz rezerwacji.** `microservice-memes/specs/account-erasure.feature`, reguła „While
the SAGA holds a MEME, nobody takes it down": po MARK ani autor, ani MODERATOR nie zdejmuje mema —
endpoint pyta najpierw galerię, a zarezerwowanego mema w niej nie ma, więc odmowa to „no such meme"
dla obu i nic za tą kontrolą się nie wykonuje. Potem kompensacja oddaje mem, a obraz jest dalej na
dysku, czyli naprawdę nikt go nie zdjął. Trzy kroki glue w `ErasureSagaSteps`. Sprawdzone mutacją:
bez `markForErasure` w kroku PURGE autor kasuje mema i scenariusz pada (200 zamiast 404).

**(c) `ClosureOutcome`, `requestedRule`, `markAndConfirm`.** Trzy kopie `ClosureOutcome` zastąpione
jedną w `shared/account-closure` — z tego samego powodu, który javadoc `DeletionOutcome` podawał,
patrząc na tę stronę (ten javadoc mówi teraz, jak jest). Jedna zmiana w samym słownictwie: liczby.
`Reserved(rows)` liczą wszystkie trzy osie, bo to jest to, co słyszy orkiestrator; `Erased(rows,
leftBehind)` i `Restored(rows)` mają `UNCOUNTED = -1` dla osi, której use case liczby nie oddaje —
nie zero, bo zero jest odpowiedzią („ten człowiek nie miał u nas nic"), a uczestnik, który nie
liczy, nie ma jej udawać.

Szkielet wyjechał do `account-closure` jako `ClosureParticipant` (strażnik, drop komendy bez
adresata, switch, ostrzeżenie o marku, który nic nie zarezerwował) i `AtomicClosureParticipant`
(mark i jego potwierdzenie w jednym unit of work) — ten sam podział na dwa poziomy, który po stronie
testów robią `ClosureParticipantContractTest` i `AtomicParticipantContractTest`. Kolekcje rozszerzają
poziom niższy, bo nie mają outboxu, więc nie mają własnego potwierdzenia do zacommitowania razem
z markiem. Logger jest `getClass()`, żeby linia dalej nazywała uczestnika, a nie szkielet — na tym
stoją testy logów w trzech serwisach. Zdanie INFO o marku zostało przy osi (hook `marked`), i dalej
pada po commicie, nie w środku transakcji. Biblioteka przestała być samym słownictwem, więc dostała
własny test szkieletu (`SkeletonParticipantTest`, uczestnik o jednej liczbie) — dotąd jej zachowanie
sprawdzałyby tylko trzy kopie podklasy, czyli dokładnie to, co ten refaktor likwiduje.

`requestedRule` wylądowało w `portal-libs/purge-rule` jako `RequestedRule`, **nie**
w `account-closure`: biblioteka sagi wie, że zamknięcie wiozą warunki, i celowo nie wie, co one
mówią — to słownictwo portalu (a trzeci uczestnik nie czyta ich wcale). Zwraca regułę i *zdanie do
zalogowania*, bez tekstu reguły, bo ten pochodzi z kreatora odchodzącego — więc oba warianty logu
zostały słowo w słowo, razem z nazwą osi, na której stoją asercje w `comments-infrastructure`.

Dwie rzeczy wyszły przy okazji: switch po trzech w pełni kwalifikowanych `ClosureOutcome.Reserved`
w `ClosureInOneProcess` zwinął się do jednego czytania, a `PurgeCommandsConsumer` w kolekcjach
buduje potwierdzenie z `reserved.rows()` zamiast `references()`.

Zielone po całości: memes 238 (bez `memes-ui`), comments 170, collections 172, offboarding 83,
portal-specs 66, `account-closure` 18, `purge-rule` 11.

*Kontekst kodu: §§0–5 opisują stan po commitach z 28.09.2026 (`69cb8c7` w `workspace-portal`,
`80a9aec` w `microservice-memes`, `ab178cb` w `microservice-comments`) i numery linii są z tego
stanu — §6 je przesunął. W szczególności `Portal.java` leży teraz w `portal/world/`, a `Heap*`
nazywają się `Fake*` (§6.3), więc odwołania w §1.1 i §4 trafiają w pliki pod innymi ścieżkami.*
