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
Tego dnia dwie z nich zostały zastąpione prawdziwymi implementacjami in-memory (`FakeVoteRepository`,
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

**Ten punkt jest wprost zablokowany przez `mock(MemeEvents.class)`.** Dopóki tam stoi, żaden
z tych scenariuszy nie da się napisać, bo drut między protokołami jest przecięty.

### 1.2 Asymetria, która jest dziurą w danych, nie w stylu

`PurgeUserComments` nie ma żadnego portu zdarzeń. Kasowanie mema ma hop
`COMMENTS_DELETED → kolekcje`; zamknięcie konta go nie ma.

Skutek: komentarze odchodzącego (pod cudzymi memami) znikają, a referencje do nich w cudzych
kolekcjach zostają **na zawsze**. Nikt ich nigdy nie sprząta. `CollectionsClosureParticipant`
czyści wyłącznie referencje *odchodzącego*, po jego `user_id`.

Widać to dopiero patrząc na oba protokoły naraz — czyli dokładnie z tego poziomu, którego dziś
nie ma.

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
kopia trzech hałd.

### 2.2 Dług, który zostaje

- **`ClosureOutcome` w trzech kopiach** (`memes/`, `comments/`, `collections/`). Javadoc
  `DeletionOutcome` sam to wytyka jako błąd, którego strona deletion uniknęła — strona closure go ma.
- **`requestedRule` skopiowane słowo w słowo** między `MemesClosureParticipant`
  a `CommentsClosureParticipant` (różnica: jedno słowo w logu), tak samo `markAndConfirm` i cały
  szkielet guard / `isAddressed` / switch w trzech uczestnikach. Biblioteka `account-closure`
  objęła *wiadomości*, nie objęła *zachowania*.
- **Brakujący port w comments przy zamknięciu konta** — punkt 1.2. To reuse, którego brak ma
  konsekwencję w danych, nie w liniach kodu.
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
   nierozstrzygalna na hałdzie — dowozi ją dopiero outbox (`KafkaMemeEventsTransactionTest`).
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
| `MemeEvents` (`Portal.java:104`) | cały styk obu protokołów — punkt 1.1 | tu, poziom portalu |
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
niżej. Trzeci, `MemeEvents`, jest jedyną pozostałą granicą na poziomie portalu i jedyną, której
zdjęcie wymaga realnej pracy: zszycia obu busów w jedną instancję `Portal`.

### Ryzyko odwrotne, żeby było uczciwie

Fake, który rozjeżdża się z adapterem, jest **gorszy** od mocka — bo jest przekonująco zły.
Estate ma na to wzorzec: `MemeErasureContractTest` trzyma fake i prawdziwy adapter przy jednym
kontrakcie. Nowe stand-iny (`FakeVoteRepository`, `FakeCommentVotes`) takiego kontraktu **nie
mają** — niosą to ryzyko. Różnica wobec stanu sprzed 28.09 jest taka, że ryzyko jest nazwane
i w jednym miejscu, zamiast rozsiane po siedmiu anonimowych klasach.

---

## 5. Kolejność, gdyby pytać mnie

1. **`MemeEvents`** — jedna instancja `Portal`, dwa busy, prawdziwy port wpięty w bus kaskady.
   Odblokowuje cały punkt 1.1, nie wymaga żadnej decyzji architektonicznej i jest jedynym
   pozostałym mockiem, który coś ukrywa na poziomie portalu.
2. **Rozstrzygnięcie 1.2** — czy zamknięcie konta ma ogłaszać skasowane komentarze. To decyzja
   produktowa, nie refaktor.
3. **Kontrakty dla nowych fake'ów** — `VoteRepositoryContractTest` wiążący stand-in z adapterem
   JDBC, wzorem `MemeErasureContractTest`.
4. **Dopiero potem** `ClosureOutcome` i `requestedRule` z punktu 2.2.

---

*Kontekst kodu: stan po commitach z 28.09.2026 (`69cb8c7` w `workspace-portal`, `80a9aec`
w `microservice-memes`, `ab178cb` w `microservice-comments`). Numery linii z tego stanu.*
