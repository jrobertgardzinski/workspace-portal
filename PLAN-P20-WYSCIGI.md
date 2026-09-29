# P20 — warstwa wyścigów nad semantyką usuwania konta i mema

> **Czym ten plik JEST:** planem jednej nowej warstwy testowej nad `portal-specs` i uzasadnieniem
> każdej jej granicy. Mechanizm, niezmienniki, etapy, kandydaci na znaleziska, koszty.
>
> **Czym NIE jest:** listą znalezisk. Nic tu nie jest zgłoszone jako błąd. Dwa miejsca, które
> sprawdziłem wprost i które okazały się **poprawne**, są opisane jako poprawne — §3 — bo plan,
> który zaczyna od wymyślonych bugów, jest wart tyle, co jego pierwszy fałszywy alarm.
>
> **Stan wejściowy:** `ANALIZA-2026-09-28-MOCKI-I-GRANICE-SEMANTYKI.md` §3.3 poz. 1 i 4. Poz. 4
> mówi wprost: *„`everyHopAnswers()` drenuje do punktu stałego, jednowątkowo. Idempotencja jest
> sprawdzona w sekwencji, nie w wyścigu dwóch partycji w jednej grupie konsumentów."* Ten plan
> jest odpowiedzią na to zdanie.

---

## 1. Teza

**To nie jest warstwa „odpalmy to na wątkach".**

Estate ma już na to zdanie i jest ono zapisane w `microservice-memes/memes-infrastructure/`
`src/test/java/.../VoteUpsertTest.java`:

> *„Neither can be produced on cue by two real threads against H2, and a test that waits for a race
> to happen is a test that passes for the wrong reason."*

Test, który uruchamia dwa wątki i czeka, aż się zderzą, jest albo zielony przez przypadek, albo
czerwony raz na dwieście przebiegów — i w obu wypadkach nie mówi, **który** przeplot był zły.
`VoteUpsertTest` robi coś innego: **nazywa przeplot i wymusza połowę przegrywającą**. Deterministycznie,
odtwarzalnie, z nazwą w `@DisplayName`.

Nowa warstwa jest uogólnieniem tego chwytu z jednego wiersza SQL na **dwa protokoły portalu**:

> Zamiast czekać, aż przeplot się wydarzy — **wyliczyć wszystkie przeploty, które transport
> dopuszcza**, przepuścić przez nie ten sam świat i zapytać, ile różnych końców z tego wychodzi.

Produktem warstwy nie jest zielony build. Produktem jest **lista różnych stanów końcowych jednego
scenariusza, każdy z jednym zdaniem, dlaczego jest dopuszczalny**. Stan dzisiejszy jest taki, że ta
lista ma jeden element, bo jeden przeplot jest wykonywany, i nikt nie wie, czy pozostałe są takie
same.

### 1.1 Dlaczego ręcznie postawione przeploty to za mało

`specs/account-closure.feature:155` — *„A cascade can take a row the saga had already reserved"* —
jest już przeplotem. Dobrym. Ale postawionym ręcznie: autor wybrał moment, w którym `bob` kasuje
mema, spośród kilkunastu możliwych.

Jak zawodne jest wybieranie ręką, pokazuje `ANALIZA` §6.2(b): **pierwsze dwa zdania o tym styku
trafiały w napięcie i nie trafiały w mechanizm.** Przeplot „MARK → autor kasuje swojego mema →
ERASE" jest niewykonalny (zamarkowany mem wypada z galerii, `DeleteMeme` odpowiada `NO_SUCH_MEME`),
a realny okazał się dopiero przeplot drugimi drzwiami — obcy kasuje **swojego** mema z komentarzem
odchodzącego. Hipoteza była napisana przez kogoś, kto znał ten kod, i dotyczyła dwóch przeplotów
z kilkunastu.

Warstwa, która wylicza przeploty, nie zgaduje.

---

## 2. Trzy rodzaje wyścigu i ich domy

Nie ma jednej warstwy łapiącej „race conditions". Są trzy różne rzeczy pod jedną nazwą i każda jest
rozstrzygalna gdzie indziej. To jest pierwsza rzecz, którą ten plan ustala, bo bez niej warstwa
spuchnie do „testujemy wszystko" i nie dowiezie nic.

| rodzaj | przykład | czy da się rozstrzygnąć w jednym procesie na fake'ach | dom |
|---|---|---|---|
| **wyścig kolejności** — dwie wiadomości, żadnej gwarancji, kto pierwszy | kaskada `MEME_DELETED` dociera do komentarzy przed ERASE sagi czy po nim | **tak** — kolejność jest decyzją transportu, a nie bazy | **ta warstwa** |
| **wyścig o wiersz** — dwie transakcje piszą ten sam wiersz | dwa pierwsze głosy tego samego wyborcy, `MERGE` przegrywający `WHEN NOT MATCHED` | **nie** — to semantyka izolacji, fake jej nie ma | test JDBC per serwis, wzorem `VoteUpsertTest` |
| **wyścig o konsumenta** — rebalans, offsety, dwa pody | dwa sweepery wybierają tę samą przeterminowaną sagę | **nie** | testy pętli konsumenta (`*ConsumerLoopTest`) + `e2e/` |

Warstwa bierze **pierwszą kolumnę i tylko ją**. Za to bierze ją całą — dziś nie ma ani jednego
testu, który by wyliczał przeploty czegokolwiek.

### 2.1 Dlaczego akurat kolejność jest tu rozstrzygalna

Bo kolejność wiadomości **nie zależy od tego, czy pod spodem jest Postgres, czy `HashMap`**. Zależy
od kluczy partycji i od tego, ile jest grup konsumentów — a to są dane, nie implementacja. Można je
przepisać z adapterów do modelu i mieć model, który jest wierny przy dowolnym magazynie.

Wiersz środkowy zależy od izolacji i od SQL-a, więc fake go nie uniesie — i dobrze, że estate
trzyma go w testach JDBC, gdzie jest sprawdzalny.

---

## 3. Co w repo już jest — żeby nie budować drugi raz

Sprawdzone przy pisaniu tego planu, wprost w kodzie:

**(a) Orkiestrator jest już zahartowany na zderzenia przejść.** Każde przejście stanu sagi
w `JdbcSagaStore` jest warunkowym UPDATE-em, nie odczytem-i-zapisem:

```
:206  UPDATE offboarding_sagas SET state='COMPLETED' … WHERE id=? AND state='STARTED'
:259  UPDATE offboarding_sagas SET state='COMPENSATED' … WHERE id=? AND state='STARTED'
:297  UPDATE offboarding_sagas SET retries=retries+1 … WHERE id=? AND state='STARTED' AND retries=?
```

Zatrzask „dokładnie jeden dowiaduje się, że zamknął" jest w bazie, a nie w javie. Ostatnie
potwierdzenie ścigające się ze sweeperem nie da dwóch werdyktów. `retryDelivered` ma warunek na
`retries = ?`, czyli obciążenie budżetu retry jest compare-and-set — javadoc `SagaStore#retryDelivered`
opisuje dokładnie ten scenariusz z dwoma sweeperami.

**(b) Podwójne ostatnie potwierdzenie nie zawiesza sagi.** `JdbcSagaStore.confirm` robi INSERT,
potem czyta zbiór potwierdzeń, potem warunkowo domyka — wszystko na autocommicie, więc SELECT
zawsze widzi INSERT wykonany wcześniej. Przeplot „obaj wstawili, żaden nie zobaczył kworum"
nie istnieje. Sprawdzone, nie zakładane.

**Wniosek, który z tego płynie i który kształtuje całą warstwę:** *wyścigów nie ma tam, gdzie ich
szukano.* Orkiestrator jest zrobiony. Nietknięte są **wiersze portalu** — memy, komentarze, ulubione
— po których chodzą dwa protokoły naraz, żaden nie trzyma zamka na drugim, i żaden z nich nie ma
ani jednego testu na kolejność inną niż ta, którą runner akurat wykonuje.

**(c) Ręcznie postawione przeploty**: `account-closure.feature` reguły z linii 155 i 186. Zostają.
Warstwa ma je **objąć**, a nie zastąpić — patrz §7, etap 1.

---

## 4. Mechanizm

### 4.1 Słownik (jedno słowo na jedno pojęcie)

Kod po angielsku, dokumenty po polsku, jak w całym estate. Mapowanie, żeby nie urosły synonimy:

| polski | angielski w kodzie | co to jest |
|---|---|---|
| przeplot | `Interleaving` | konkretna kolejność kroków, wykonana od początku do końca |
| krok | `Step` | jedna rzecz, którą transport może zrobić teraz |
| zbiór gotowych | `ready set` | kroki dopuszczalne w danym stanie |
| planista | `Scheduler` | wybiera krok ze zbioru gotowych |
| eksplorator | `Explorer` | wykonuje świat raz na każdy legalny przeplot |
| odcisk stanu | `fingerprint` | kanoniczny, porównywalny zapis całego świata |
| model transportu | `TransportModel` | reguły mówiące, które przeploty są legalne |

Słowo **przeplot** jest już używane w `ANALIZA` §6.2(b) — stąd, a nie z nowego pomysłu.

### 4.2 Trzy zmiany w runnerach — i dlaczego są bezpieczne

Dziś oba busy drenują do punktu stałego pętlą `while (!inFlight.isEmpty())`
(`ClosureInOneProcess.everyPartAnswers`, `DeletionInOneProcess.everyHopAnswers`). Kolejność jest
FIFO po liście, a uczestnicy w zamknięciu konta są obsługiwani w stałej kolejności
`List.of(MEMES, COMMENTS, COLLECTIONS)` (`ClosureInOneProcess.deliver`).

Zmiana:

1. `inFlight` przestaje być jedną listą, a staje się **mapą kolejek po (temat, klucz, grupa)**.
   Głowa każdej kolejki jest jednym krokiem gotowym.
2. Drenaż dostaje planistę: `run(Scheduler)`.
3. **Dotychczasowe metody zostają**, zaimplementowane jako `run(Scheduler.FIFO)` — FIFO po liście,
   uczestnicy w tej samej stałej kolejności.

Punkt 3 jest całym zabezpieczeniem etapu 1: **66 zielonych speców zachowuje semantykę z konstrukcji**,
bo dostają dokładnie tego planistę, którego zachowanie dziś jest wpisane w pętlę. Jeśli któryś
zczerwienieje, to znaczy, że `Scheduler.FIFO` nie odtwarza pętli — czyli że mamy błąd w refaktorze,
a nie znalezisko. To rozróżnienie jest darmowe i warto je mieć.

### 4.3 Model transportu — czyli co wolno planiście

Warstwa jest warta dokładnie tyle, ile jej model. Planista, który dopuszcza przeploty niemożliwe
w produkcji, produkuje **znaleziska nie do naprawienia** i zabija warstwę w dwa tygodnie. Reguły
przepisane z adapterów, nie wymyślone:

| reguła | skąd | co z niej wynika dla planisty |
|---|---|---|
| kolejność zachowana w obrębie klucza partycji | `KafkaMemeEvents` kluczuje po `memeId`, `PurgeConfirmations` po `sagaId` / id odchodzącego (`ANALIZA` §3.3 poz. 1) | głowa kolejki, nigdy środek |
| brak jakiejkolwiek kolejności **między** kluczami | to samo miejsce | dowolny przeplot sagi z kaskadą — **to jest ten styk** |
| trzy grupy konsumentów są niezależne | trzy serwisy, trzy grupy | memy/komentarze/kolekcje w dowolnej kolejności względem siebie |
| at-least-once, bez dedupu | `transactional-outbox/README.md`: *„the service embeds it in the payload so a redelivery is a recognisable duplicate"* — rozpoznawalny, ale **nigdzie nie ma tabeli przetworzonych**; idempotencja jest strukturalna | każdy dostarczony krok może wrócić; budżet duplikatów na przeplot |
| hop jest atomowy wtedy i tylko wtedy, gdy siedzi w `UnitOfWork` | `unit-of-work/README.md`; w `Portal` to `Runnable::run` | jednostka przeplatania = hop w UoW; **hop bez UoW przeplata się drobniej** |
| zegar może skoczyć za timeout w dowolnym momencie | `SweepOverdue`, `world.windForward` | „sweeper strzela teraz" jest krokiem jak każdy inny |

Ostatni wiersz pierwszej kolumny to najcenniejsza linijka w tej tabeli. `ANALIZA` §3.3 poz. 1 mówi,
że **żaden schemat partycjonowania tego nie uporządkuje** i że poziom semantyczny będzie w tym
miejscu wprost optymistyczny. Model transportu zamienia to zdanie z przypisu w **regułę generującą
przeploty**: skoro nic nie porządkuje styku, planista wolno przeplata go na wszystkie sposoby.
Warstwa nie naprawia tej dziury — ona jako pierwsza **mierzy, ile ona kosztuje**.

Przedostatni wiersz to drugi zysk: dziś atomowość hopa jest nieodróżnialna od jej braku, bo
`Runnable::run` zawsze się udaje. Model żąda, żeby o każdym hopie powiedzieć, czy jest w UoW —
a hop, który nie jest, dostaje drobniejsze kroki i wolno go przerwać w środku. To jest jedyny
sposób, żeby pytanie „czy ten hop musi być transakcyjny" miało na tym poziomie **odpowiedź**,
a nie tylko javadoc.

### 4.4 Odcisk stanu — jedyna zdolność, której światu dziś brakuje

Żeby porównywać przeploty, trzeba umieć porównać ich końce. Dziś nie ma jak: asercje są punktowe
(„nic nie zostało pod pierwszym memem"), nie ma żadnego totalnego odczytu świata.

`Portal.fingerprint()` — kanoniczny, niezależny od kolejności zapis wszystkiego: wiersze trzech
fake'ów, stan sagi, zawartość każdego drutu. Pojęcie jest już w estate: javadoc `FakeSagaStore.Saga`
mówi *„package-visible for the tests' state fingerprints"*.

Odcisk robi w tej warstwie **trzy** rzeczy, i to jest powód, dla którego jest pierwszą rzeczą do
zrobienia:

1. **grupuje przeploty** w klasy stanów końcowych — czyli produkuje wynik warstwy (§4.6);
2. **przycina przeszukiwanie** — dwa różne przeploty, które doszły do tego samego odcisku i mają ten
   sam zbiór kroków w locie, mają identyczną przyszłość, więc drugi nie musi być kontynuowany.
   Memoizacja po `(odcisk, wielozbiór kroków gotowych)` jest poprawna i **nie wymaga żadnej analizy,
   które kroki komutują** — co jest zwykle najdroższą częścią takich narzędzi;
3. **jest asercją idempotencji** — niezmiennik I4 to dosłownie równość dwóch odcisków.

### 4.5 Szkic API

```java
sealed interface Step {
    /** Head of one partition's queue for one consumer group. */
    record Deliver(String topic, String key, String group, Object message) implements Step { }
    /** At-least-once: the broker hands the same record over again. */
    record Redeliver(Deliver original) implements Step { }
    /** The clock reached a purge timeout and the sweeper ran. */
    record Sweep() implements Step { }
    /** Somebody outside both protocols acts — a stranger takes their own meme down. */
    record Intrusion(String name, Runnable what) implements Step { }
}

interface Scheduler {
    Step next(List<Step> ready);
    Scheduler FIFO = ready -> ready.get(0);   // what the loop does today
}

final class Explorer {
    /** Every legal interleaving of this seed, grouped by the state it ends in. */
    Map<Fingerprint, List<Interleaving>> explore(Seed seed, Bounds bounds);
}
```

`Seed` to dzisiejszy scenariusz: świat wyjściowy + wyzwalacz + zbiór intruzji, które wolno wpleść.
`Bounds` to głębokość, budżet duplikatów i budżet sweepów — bez nich przestrzeń jest nieskończona,
bo duplikat można wyprodukować zawsze.

### 4.6 Produkt: konfluencja, nie zieloność

Dla każdego ziarna eksplorator zapisuje plik:

```
seed: closure of alice, one meme of bob with her comment under it
legal interleavings explored: 1184   (memoised to 213 distinct states-in-progress)
distinct end states: 3

[A] 1102 interleavings — the closure erased everything, the cascade took the thread
[B]   78 interleavings — the cascade took the comment BEFORE ERASE; the confirmed count
                         was already false when it was sent  (account-closure.feature:155)
[C]    4 interleavings — <opis>
```

Ten plik jest **wersjonowany i zatwierdzany**. Jego diff jest decyzją: nowy stan końcowy w wyniku
zmiany w kodzie to albo znalezisko, albo świadomie przyjęty handel, który trzeba opisać jednym
zdaniem. To jest ta sama dyscyplina, którą estate stosuje do paktów, przeniesiona na kolejność.

I to jest odpowiedź na pytanie „czym ta warstwa różni się od dopisania dziesięciu scenariuszy":
scenariusz mówi, że **jeden** przeplot daje dobry koniec. Ten plik mówi, **ile jest wszystkich
końców** — łącznie z tymi, których nikt nie wymyślił.

---

## 5. Niezmienniki

Sprawdzane **po każdym kroku każdego przeplotu**, nie tylko na końcu. Krok, po którym niezmiennik
pada, jest wskazany z palcem — to jest cała przewaga nad dwoma wątkami.

| # | niezmiennik | co go łamie |
|---|---|---|
| **I1** | żaden zapisany wskaźnik nie celuje w nieistniejący mem ani komentarz | to jest dokładnie treść `ANALIZA` §1.2 — dziura, która była w danych przez rok; teraz ma port, ale ani jednego przeplotu poza szczęśliwym |
| **I2** | wiersz skasowany nie wraca inaczej niż przez RESTORE, a RESTORE oddaje wyłącznie to, co ta saga zarezerwowała | duplikat MARK po ERASE; RESTORE po tym, jak drugi protokół zabrał wiersz |
| **I3** | liczba w potwierdzeniu = liczba zarezerwowana **w chwili potwierdzania** | świadomie osłabiony: rozjazd PO wysłaniu jest znany i opisany (`feature:155`). Warstwa mierzy **rozmiar** rozjazdu po wszystkich przeplotach — rozjazd większy niż w którymkolwiek nazwanym scenariuszu jest znaleziskiem |
| **I4** | przeplot S i przeplot S z jednym zduplikowanym krokiem kończą się tym samym odciskiem | to jest poz. 4 z `ANALIZA` §3.3, jedyny niezmiennik, którego dziś nie da się nawet wypowiedzieć: `redeliver()` odtwarza wyłącznie **ostatni** `MEME_DELETED` i nic po stronie sagi |
| **I5** | każdy przeplot dochodzi do ciszy, a w ciszy nie ma wierszy zamarkowanych bez sagi w stanie końcowym | zagubiony hop; sweeper strzelający między MARK a potwierdzeniem |
| **I6** | RESTORE bez ingerencji drugiego protokołu oddaje dokładnie stan sprzed MARK; z ingerencją — różni się **dokładnie o to, co zabrał drugi protokół**, ani o wiersz więcej | `feature:155` drugi przykład stwierdza to dla jednego przeplotu |
| **I7** | co ogłoszone, to zacommitowane, i odwrotnie | **na etapie 1 i 2 niesprawdzalne** — `Runnable::run` nie umie się wycofać. Patrz etap 3 i §9 |

I7 jest w tabeli po to, żeby było widać, czego brakuje, a nie dlatego, że etap 1 to dowozi.

---

## 6. Gdzie to mieszka

**W `portal-specs`, jako pakiet `races`. Bez nowego modułu.**

Argument jest już napisany, w javadocu `portal.world.Portal`:

> *„One world, two protocols, because there is one portal. A second specs module would have meant a
> second copy of these three fakes — or a third module to share them — and the copies would have
> drifted, which is the failure this repository has already paid for twice."*

Ta sama logika obejmuje trzeci konsument tego świata. Osobny moduł oznaczałby wystawienie
`portal-specs` jako test-jara i **jeszcze jeden `install` w kolejności budowania**, która i tak jest
najbardziej kosztowną rzeczą w tym repo. Nowy pakiet nie zmienia w kolejności budowania niczego.

Eksplorator dostaje `@Tag("races")` — żeby dało się go wyłączyć, jeśli wyjdzie z sekund. **Nie**
wyłączamy go domyślnie zanim zmierzymy: test wyłączony domyślnie to test, którego nie ma.

Co gdzie ląduje:

- **mechanizm** (`Step`, `Scheduler`, `Explorer`, `fingerprint`) → `portal-specs`, pakiet `races`;
- **znaleziska** → reguły w **istniejących** `specs/account-closure.feature` i `meme-deletion.feature`.
  Bez trzeciego pliku `.feature`: `specs/README.md` już mówi, że styk obu protokołów jest
  *„the last five rules of `account-closure.feature`"*, a duplikaty mają swój dom w regule
  *„The same deletion twice changes nothing and announces nothing"*;
- **pliki konfluencji** → `portal-specs/src/test/resources/races/*.outcomes`, zatwierdzane.

Eksplorator sam nie dostaje Gherkina. Gherkin jest formą **obietnicy**, a przeszukiwanie nie jest
obietnicą — jest sposobem na znalezienie obietnicy, której brakuje.

---

## 7. Etapy

### Etap 1 — planista (kryterium: 66 zielonych speców, zero zmian w ich treści)

1. `Portal.fingerprint()` + test, że odcisk jest niezależny od kolejności wstawiania.
2. `inFlight` → kolejki po `(temat, klucz, grupa)` w obu runnerach.
3. `Scheduler`, `Scheduler.FIFO`, `run(Scheduler)`; `everyPartAnswers()` i `everyHopAnswers()`
   delegują do `run(FIFO)`.
4. Stała kolejka uczestników w `ClosureInOneProcess.deliver` znika — trzy grupy, trzy kroki.
   `FIFO` odtwarza dotychczasową kolejność.

Wychodzi z tego zero nowych asercji i cała zdolność. To jest etap, w którym nic się nie dowiaduje
i wszystko może się zepsuć — więc ma własne kryterium i własny commit.

### Etap 2 — eksplorator i niezmienniki (kryterium: pierwszy plik konfluencji w repo)

5. `TransportModel` z §4.3, wraz z testem, że model **odrzuca** przeplot łamiący kolejność w kluczu.
6. `Explorer` z memoizacją po odcisku; `Bounds`; raport konfluencji.
7. Niezmienniki I1–I6 jako `Invariant`, sprawdzane po każdym kroku.
8. Delta-debugging: przeplot łamiący niezmiennik skracany do minimalnego, który nadal łamie.
9. Cztery–sześć ziaren z §8, każde z plikiem konfluencji.
10. Każdy stan końcowy w każdym pliku dostaje **zdanie**. Stan bez zdania jest znaleziskiem.

### Etap 3 — oś awarii (osobna decyzja, nie robić razem z 1 i 2)

Dopiero to dowozi I7. Wymaga, żeby fake'i umiały się wycofać: dziennik zapisów i `rollback()`,
czyli `UnitOfWork`, który potrafi nie zadziałać. To jest zmiana w `FakeMemes`/`FakeComments`/
`FakeFavourites`, nie w warstwie — i **to jest powód, żeby ją odciąć**. Etap 1 i 2 nie dotykają
fake'ów wcale.

Co dowozi: awaria między pracą a ogłoszeniem, uczestnik umierający w środku ERASE, ogłoszenie
wysłane przez transakcję, która się nie zacommitowała. Czego nadal nie dowiezie: że **Postgres**
tak się zachowa. To zostaje w testach JDBC i w `e2e/`, na zawsze.

---

## 8. Kandydaci na znaleziska — od czego zacząć przeszukiwanie

Wszystkie wyczytane z kodu, żadne nie jest zgłoszeniem błędu. To jest lista ziaren, nie lista wad.

1. **Trzej uczestnicy w stałej kolejności.** `ClosureInOneProcess.deliver` iteruje
   `List.of(MEMES, COMMENTS, COLLECTIONS)`. Trzy niezależne grupy konsumentów; 6 kolejności;
   wykonywana 1. *Ziarno: zamknięcie konta z zawartością na wszystkich trzech osiach.*
2. **Kaskada za piwotem, w dowolnym miejscu.** `everyPartAnswers()` świadomie nie drenuje kaskady,
   a scenariusz woła `the cascade reaches every part` w wybranym momencie — zwykle na końcu.
   Kaskada mema #1 dostarczona **przed** ERASE komentarzy nie jest wykonywana nigdy.
   *Ziarno: dwa memy odchodzącego, wątki obcych pod oboma.*
3. **Duplikat czegokolwiek poza ostatnim `MEME_DELETED`.** `DeletionInOneProcess.redeliver()`
   odtwarza tylko `lastMemeDeleted`; po stronie sagi nie ma duplikatu w ogóle. Cały projekt stoi na
   at-least-once bez tabeli dedupu. *Ziarno: dowolne, z budżetem jednego duplikatu.*
4. **Sweeper strzelający w środku.** Dziś `waitsAndAsksAgain()` przesuwa zegar i **od razu** drenuje.
   Sweep między dostarczeniem MARK a potwierdzeniem nie zdarza się nigdy. *Ziarno: jedna oś
   wyciszona, budżet dwóch sweepów.*
5. **Oba protokoły na tym samym wierszu komentarza, we wszystkich położeniach.**
   `feature:155` ustawia jedno. *Ziarno: to samo, oddane eksploratorowi.*
6. **Powtórzony MARK z ERASE pomiędzy.** `ClosureInOneProcess.answerOf` ma
   `confirmedBy.putIfAbsent` z komentarzem *„a re-commanded MARK finds everything already reserved
   and confirms 0, which is idempotence working"* — zdanie prawdziwe dla jednej kolejności.
7. **Drugie zamknięcie tego samego konta w locie.** `SagaStore.start` ma wprost opisaną obsługę
   *„the one already running for the email (a second request racing the first)"*, łącznie
   z przejęciem ADMIN → SELF i skasowaniem polityki. Ani jeden spec portalowy tego nie dotyka
   **z prawdziwymi uczestnikami** — a to jest zmiana reguły purge w trakcie trwania sprawy.

Pozycja 7 jest moim faworytem: jedyne miejsce, gdzie wyścig zmienia nie kolejność skutków, lecz
**politykę**, według której wiersze są niszczone.

---

## 9. Ryzyka i koszty

**Eksplozja przestrzeni.** Realne. Łagodzone memoizacją po odcisku (§4.4 pkt 2), twardymi budżetami
i małymi ziarnami (2 memy, 3 komentarze — dokładnie skala dzisiejszego `Background`). Plan zakłada
**pomiar przed obietnicą**: jeśli ziarno robi więcej niż ~10⁴ przeplotów, zmniejsza się ziarno,
a nie podkręca maszynę.

**Model transportu rozjeżdża się z rzeczywistością.** Najgroźniejsze ryzyko całej warstwy i ten sam
kształt, który `ANALIZA` §4 nazwała przy fake'ach: *„Fake, który rozjeżdża się z adapterem, jest
gorszy od mocka — bo jest przekonująco zły."* Model jest fake'em **transportu**. Łagodzenie: każda
reguła modelu wskazuje plik adaptera, z którego pochodzi (tabela §4.3), a model ma własne testy na
przeploty, które ma **odrzucać**. Domknięcie kontraktowe — model przy prawdziwym brokerze — jest
poza zakresem i należy do `e2e/`.

**Refaktor runnerów psuje 66 zielonych.** Łagodzone przez `Scheduler.FIFO` jako wierne odtworzenie
pętli i przez to, że etap 1 nie dodaje ani jednej asercji.

**Fake'i nie mają rollbacku.** Znane, nazwane, odcięte do etapu 3. I7 zostaje niesprawdzone i jest
tak oznaczone w tabeli §5.

**Czas buildu.** `@Tag("races")` w odwodzie, użyć dopiero po pomiarze.

**Znaleziska bez właściciela.** Eksplorator wyprodukuje stany końcowe, które nie są ani błędem, ani
obietnicą — tylko handlem, którego nikt nie nazwał. Reguła z §7 pkt 10 (każdy stan dostaje zdanie)
jest jedynym, co trzyma warstwę przy życiu; bez niej plik konfluencji w trzy miesiące staje się
tapetą, którą się zatwierdza bez czytania.

---

## 10. Czego ta warstwa nie udowodni

Dopisek do `ANALIZA` §3.3, bo ta lista właśnie się skraca i uczciwie jest powiedzieć o ile:

| poz. §3.3 | było | po tej warstwie |
|---|---|---|
| 1. partycjonowanie na styku | „poziom semantyczny będzie wprost optymistyczny" | **przestaje być optymistyczny** — brak porządku na styku jest regułą modelu, a koszt jest policzony w pliku konfluencji. Nadal nie dowodzi, że **Kafka** zachowa się jak model |
| 2. jednostka pracy | nierozstrzygalne na fake'ach | etap 1–2: bez zmian. Etap 3: rozstrzyga, czy **kod** postawił oba zapisy w jednej jednostce. Nigdy: czy Postgres to dowiezie |
| 3. serializacja omija specy kaskady | bus przekazuje obiekty, nie `fields()` | **bez zmian** — to jest ortogonalne i zostaje |
| 4. współbieżność | „idempotencja sprawdzona w sekwencji, nie w wyścigu" | **załatwione w swojej klasie** (I4, duplikat w dowolnym punkcie dowolnego przeplotu). Rebalans i offsety zostają w testach pętli konsumenta |
| 5. eventual consistency | asercje po wyciszeniu | **bez zmian** — cisza pozostaje pojęciem tego poziomu |

Dwa wiersze bez zmian są tu celowo. Warstwa, która twierdzi, że zamyka pięć na pięć, kłamie.

---

## 11. Pierwszy krok

`Portal.fingerprint()` i test, że odcisk nie zależy od kolejności wstawiania wierszy.

Jedna metoda, żadnej zmiany w runnerach, żadnej w specach — a bez niej nie da się zrobić ani
grupowania, ani przycinania, ani I4. Do tego od razu widać, czy trzy fake'i w ogóle umieją oddać
swoją zawartość w sposób totalny, czy każdy odpowiada tylko na pytania, które ktoś już zadał.
Jeśli nie umieją, to jest pierwsze znalezisko tej warstwy i jest o nim wiadomo w dniu pierwszym,
a nie w trzecim tygodniu.

---

## 12. Co zostało zrobione — noc 28/29.09.2026

Etapy 1 i 2 są w repo. To nie jest plan: to zapis tego, co jest w kodzie, i — przede wszystkim —
trzech rzeczy, których §§1–11 nie przewidziały, bo wyszły dopiero przy uruchomieniu.

### 12.1 Etap 1: planista (kryterium spełnione)

`races.Wire` trzyma kolejkę na **pas** — trójkę (temat, klucz partycji, grupa konsumentów), czyli
to, co broker naprawdę szereguje. `races.Scheduler` wybiera spośród głów pasów;
`Scheduler.FIFO` to dawna pętla, nazwana. Oba runnery przepisane na wire:

- `ClosureInOneProcess.deliver` już nie iteruje `List.of(MEMES, COMMENTS, COLLECTIONS)` — jedna
  komenda to **trzy kroki na trzech pasach**, a potwierdzenie uczestnika to **czwarty**, na pasie
  orkiestratora. Do 28.09 orkiestrator nie był w tym runnerze osobnym konsumentem: jego
  `handle()` wołało się w środku dostarczania komendy.
- `DeletionInOneProcess` dostaje wire zamiast listy i — na żądanie zamknięcia konta — **ten sam
  wire**. Dwa wire'y czyniłyby styk obu protokołów nieprzeplatalnym z definicji, a to jedyna rzecz,
  której produkcja nie porządkuje w ogóle.
- `Portal.fingerprint()` — totalny, kanoniczny odczyt świata. Rezerwacja czytana jako **bool**,
  nie jako instant: kiedy wiersz zarezerwowano, to sprawa zegara, a dwa harmonogramy różniące się
  tylko liczbą przeczekanych timeoutów nie są dwoma wynikami.

**Kryterium etapu: 66 zielonych, zero zmian w treści speców.** Spełnione za pierwszym przebiegiem.

### 12.2 Etap 2: eksplorator

`races.Explorer` chodzi w głąb po drzewie decyzji, odtwarzając ziarno od początku dla każdego
węzła — drożej niż migawka świata, za to bez pisania migawki, a całe zamknięcie konta trwa
milisekundy. Przycinanie: dwa węzły o tym samym odcisku, tym samym wire'rze przed sobą i tych samych
budżetach mają tę samą przyszłość. Dzięki temu **zbiór stanów końcowych jest dokładny**, a liczba
harmonogramów jest liczbą tych faktycznie przejściowych.

Osiem praw (§5 plus cztery, które dopisały się przy robocie: „portal decyduje raz", „purged znaczy,
że portal nie trzyma nic tej osoby", „żaden wątek nie przeżywa mema, pod którym wisi", „kapitulacja
oddaje wszystko, czego nie zabrał ktoś inny"). Jedenaście ziaren. Pliki konfluencji w
`specs/races/*.outcomes`, obok plików `.feature`, bo to ten sam rodzaj rzeczy: zdanie o portalu jako
całości, którego żaden serwis nie wypowie sam.

| ziarno | harmonogramów | węzłów | stanów końcowych |
|---|---:|---:|---:|
| `three-parts` | 5 | 215 | **1** |
| `cascade-after-the-pivot` | 5 | 203 | **1** |
| `both-protocols-on-one-row` | 13 | 1 423 | **2** |
| `at-least-once` | 27 | 3 033 | **2** |
| `the-sweeper` | 16 | 269 820 | **2** |
| `a-second-request` | 7 | 2 475 | **1** |
| `the-whole-seam` | 15 | 3 463 | **2** |
| `a-popularity-condition` | 5 | 203 | **1** |
| `a-cascade-twice` | 7 | 36 | **1** |
| `two-people-leaving` | 13 | 17 738 | **2** |
| `given-up-after-the-cascade` | 14 | 56 995 | **2** |

Każde przeszukiwanie **wyczerpujące** — żadne nie odbiło się od limitu.

### 12.3 Wynik merytoryczny: portal jest odporny na kolejność tam, gdzie to boli

**Żadne prawo nie pękło na żadnym harmonogramie żadnego ziarna.** To nie jest „testy przeszły" —
to jest zdanie o systemie, którego przedtem nikt nie mógł wypowiedzieć, bo istniał jeden
harmonogram.

Gdzie stany końcowe się różnią, różnią się **wyłącznie w tym, co portal OBIECAŁ**, nigdy w tym, co
trzyma. Wszystkie „2" w tabeli to jeden i ten sam kształt: liczba w potwierdzeniu zależy od tego,
czy drugi protokół zabrał wiersz przed markiem, czy po nim. Wiersze zawsze kończą tak samo.

Dwa wyniki warte osobnego zdania:

- **`a-cascade-twice`: 1 stan.** Obietnica z `meme-deletion.feature` („ta sama delecja dwa razy nic
  nie zmienia i nic nie ogłasza") była sprawdzona na jednej kolejności; teraz jest sprawdzona na
  wszystkich, razem z duplikatem w dowolnym punkcie. Idempotencja jest strukturalna i **działa** —
  w portalu nie ma ani jednej tabeli przetworzonych zdarzeń i okazuje się, że nie musi być.
- **`at-least-once`: 2 stany, i różnica jest poza portalem.** Jedyny duplikat, który cokolwiek
  zmienia obserwowalnie, to duplikat **werdyktu wysłanego do tożsamości** — `PORTAL_CONTENT_PURGED`
  dwa razy. Wszystkie duplikaty rekordów, które portal *konsumuje*, znikają bez śladu. To jest
  dokładnie podział odpowiedzialności, który `transactional-outbox` deklaruje: id koperty jest
  w payloadzie właśnie po to, żeby **konsument** rozpoznał duplikat.

### 12.4 Znalezisko 1 — runner speców nigdy nie oznaczał sagi jako ogłoszonej

**Najważniejsza rzecz z tej nocy, i dokładnie ta klasa, którą §1.1 planu opisywał jako niewidoczną.**

`EventsRouter.sweepOverdue()` robi trzy rzeczy, nie jedną: ponawia komendy, kapituluje — i
**re-publikuje werdykty saga, które nie dostały znacznika `outcome_announced`**. Wdrożona pętla
stawia ten znacznik po udanym wysłaniu (`KafkaLoop#settleDeliveries`). `ClosureInOneProcess`
**nigdy go nie stawiał**.

Skutek: każdy sweep po domknięciu sprawy ogłaszał werdykt jeszcze raz. Ziarno `the-sweeper` kończyło
w stanie `identity was told: [PURGED, PURGED, PURGED, PURGED, PURGED]`.

Dlaczego przez rok nikt tego nie widział — i to jest pointa:

> **Sprawdzone mutacją: po wycięciu znacznika 66 speców jest dalej zielonych, a warstwa wyścigów
> czerwona.** Żaden scenariusz nigdy nie zamiótł sagi, która już się skończyła, bo `givesUpWaiting`
> występuje wyłącznie w scenariuszach porażki, a tam saga się nie kończy sukcesem.

Naprawione: bus stawia znacznik przy publikacji, bo w tym runnerze publikacja **jest** dowodem
dostarczenia. Cena starej dziury, policzona: przeszukiwanie `the-sweeper` spadło z **1 516 193
węzłów do 269 820**, stanów końcowych z **6 do 2**, a cała suita wyścigów z **415 s do 73 s**.
Połowa przestrzeni stanów tego ziarna była artefaktem brakującego pół-protokołu.

### 12.5 Znalezisko 2 — „wyciszony uczestnik" był modelowany jako gubienie rekordów

`silence(part)` odrzucało wiadomość w chwili dostarczenia. Konsument, który leży, **nie gubi**
rekordów: broker je trzyma, offset nie rusza, a po powrocie konsument czyta wszystko, co go
ominęło, **w kolejności**, zanim przeczyta cokolwiek nowego.

Zmienione: `Wire.hold(group)` / `release(group)` — pas przestaje być gotowy. Różnica jest
widoczna dokładnie tam, gdzie powinna: część wraca do marka, którego nie słyszała, z kompensacją
stojącą za nim w tej samej partycji — i to, że obie przychodzą w tej kolejności, jest **jedynym**
powodem, dla którego nic nie zostaje zarezerwowane na zawsze. Prawo „sprawa zamknięta i nic nie
jest zarezerwowane" stoi na współdzielonym kluczu partycji, nie na uprzejmości uczestników.

Przy okazji drugie: pasy kaskady i pasy sagi **dzieliły nazwy grup** (`comments`, `collections`),
więc wyciszenie uczestnika sagi wyciszało też hop kaskady tego samego serwisu. To są dwie różne
grupy konsumentów w jednym serwisie, a runner inscenizował awarię, której produkcja mieć nie może.
Teraz `comments-cascade` / `collections-cascade`.

### 12.5a Znalezisko 3 — eksplorator brał przycięcie za ciszę

Moje własne, i warte zapisania, bo to najgroźniejszy rodzaj błędu w narzędziu tej klasy.

Cztery z ośmiu praw wolno pytać **tylko w ciszy** — po dostarczeniu wszystkiego. Między skasowaniem
mema a dotarciem kaskady wskaźnik na niego NAPRAWDĘ wisi, wątek NAPRAWDĘ stoi, rezerwacja NAPRAWDĘ
nie jest zwolniona. To nie są wady, to jest wygląd środka przebiegu.

Pierwsza wersja `Explorer` zerowała listę opcji po przekroczeniu limitu kroków — i tym samym
mówiła prawom „to jest cisza". Pierwsze ziarno, które o limit zahaczyło, zgłosiło **cztery złamane
prawa**. Wszystkie cztery były środkiem przebiegu.

Naprawione: przycięcie i cisza to dwie różne rzeczy, prawa ciszy nie są pytane w przyciętym
przebiegu, a raport i tak mówi `TRUNCATED` wielkimi literami. Morał, który zapisuję dla siebie:
**narzędzie do szukania wyścigów, którego nie sprawdzono na własnych granicach, produkuje dokładnie
te fałszywe alarmy, dla których ludzie takie narzędzia wyłączają.**

### 12.5b Prawo, które musiało się nauczyć, czego NIE wolno mu żądać

Prawo „kapitulacja oddaje wszystko" w pierwszej wersji żądało, żeby po werdykcie FAILED każdy mem
odchodzącej wciąż istniał. To jest fałsz, jeśli w scenariuszu moderator może zdjąć jej mema —
a po fakcie **nic w świecie nie odróżnia zdjęcia przez moderatora od erazury, która nie powinna
była się wydarzyć**. Rzędy nie pamiętają, kto je skasował.

Zamiast dokładać atrybucję do fake'ów, ziarno deklaruje
(`whereOthersMayTakeTheirContentDown`), że w tej sytuacji ktoś z zewnątrz może zdjąć treść
odchodzącej, a prawo pyta wtedy słabiej. To jest granica, nie obejście: prawo silne wszędzie, gdzie
da się je utrzymać, i jawnie osłabione tam, gdzie nie da się.

Komentarza odchodzącej to nie dotyczy — ten ma własne, sprawdzalne usprawiedliwienie: wolno mu
zniknąć wyłącznie wtedy, gdy zniknął mem, pod którym wisiał, i ten mem musiał w świecie **naprawdę
być**. Komentarz pod memem, którego ten świat nigdy nie trzymał, nie ma kaskady do obwinienia.

### 12.6 Znalezisko 4 — ten moduł nigdy nie był cichy

`portal-specs/pom.xml` bierze `slf4j-nop` z komentarzem, że to „keeps the runner silent". Logback
przyjeżdża tranzytywnie za którymś z uczestników i **wygrywa** wyścig providerów — SLF4J mówi to
na każdym przebiegu. Przy 66 scenariuszach było to darmowe. Przy 10⁵ przebiegach tych samych
uczestników **log wypełnił dysk, zanim raport wypełnił się treścią**, i to log, nie przeszukiwanie,
był wąskim gardłem. `src/test/resources/logback-test.xml`, root OFF.

### 12.7 Nowa reguła w `account-closure.feature`

Pętla z §4.6 zamknięta raz: eksplorator znalazł drugi koniec, człowiek napisał zdanie.

`both-protocols-on-one-row` ma dwa stany, bo take-down obcego może wypaść **przed** markiem części
komentarzy albo **po** nim. Plik `.feature` opisywał tylko „po" („the comments part had confirmed 4
reserved, one of which was gone before it was erased"). Dopisany przykład opisuje „przed": część
komentarzy potwierdza **3, nie 4**, liczba jest prawdziwa, a wskaźnik na słowa odchodzącej sprząta
**drugi protokół**, bo zamknięcie konta nie miało już czego kasować.

Sprawdzone mutacją: bez wstrzymania pasa komentarzy scenariusz pada (4 zamiast 3).

### 12.8 Co to zmienia w `ANALIZA` §3.3 — wersja po fakcie

| poz. §3.3 | stan |
|---|---|
| 1. partycjonowanie na styku | **zmierzone.** Brak porządku na styku jest regułą modelu; koszt to dwa stany końcowe w czterech ziarnach, zawsze w liczbie w potwierdzeniu i nigdy w wierszach. Nadal nie dowodzi, że Kafka zachowa się jak model |
| 2. jednostka pracy | **bez zmian** — etap 3, świadomie odcięty |
| 3. serializacja omija specy kaskady | **bez zmian** |
| 4. współbieżność | **zamknięte w swojej klasie.** Duplikat dowolnego rekordu w dowolnym punkcie dowolnego harmonogramu; jedyny obserwowalny to werdykt do tożsamości, i to jest kontrakt, nie wada |
| 5. eventual consistency | **bez zmian co do asercji**, ale okno jest teraz nazwane: prawo o wskaźnikach jest pytane w ciszy, bo między delecją a kaskadą wskaźnik NAPRAWDĘ wisi i to jest handel zawarty świadomie |

### 12.9 Koszt i decyzja o buildzie

Suita wyścigów: **~92 s**, z czego ~65 s to jedno ziarno (`the-sweeper`, cztery timeouty i część,
która wraca). `RacesTest` ma `@Tag("races")` i **nie jest domyślnie wyłączony** — tag jest po to,
żeby ciasna pętla mogła powiedzieć `-Dgroups='!races'`. Suita wyłączona domyślnie to suita, której
nikt nie uruchamia.

Zielone po całości: `portal-specs` **67 speców + 11 ziaren = 78 testów**, `clean test` w 1 min 43 s,
wszystkie przeszukiwania wyczerpujące, żadne prawo złamane na żadnym harmonogramie.

### 12.10 Co zostaje

- ~~**Etap 3** (oś awarii, `UnitOfWork`, który potrafi się nie udać).~~ **Zrobiony — §13.**
  Prawo I7 sprawdzone w swojej klasie.
- ~~**Dwie sagi i kompensacja.**~~ **Zmierzone.** Wersja z dwiema sagami i czterema sweepami
  okazała się za droga (533 tys. węzłów i przeszukiwanie dalej przycięte), a przede wszystkim
  zadawała pytanie okrężnie. Ziarno `given-up-after-the-cascade` zadaje je wprost, jedną sagą:
  kolekcje leżą, więc sprawa i tak skapituluje, a obcy zdejmuje swojego mema w dowolnym momencie.
  Wyczerpujące, 56 995 węzłów, **dwa stany końcowe**, żadne prawo złamane — i oba stany różnią się
  wyłącznie tym, czy część komentarzy zdążyła zarezerwować wiersz, zanim kaskada go zabrała. To
  jest ten sam kształt, co wszędzie indziej w tej tabeli: **kolejność zmienia to, co portal
  obiecał, nigdy to, co trzyma.**

  Zostaje z tego jedno zdanie, którego nikt nie wypowiedział: `feature:155` mówi „kompensacja nie
  odda tego, co zabrał drugi protokół" dla jednego przeplotu i nazywa to decyzją. Teraz wiadomo, że
  to ta sama decyzja na **każdym** przeplocie, a nie że tak akurat wyszło.
- **Redukcja częściowego porządku.** Przycinanie po odcisku wystarczyło wszędzie poza
  `the-sweeper`. Jeśli dojdą ziarna z sweepami, będzie to pierwsze miejsce do policzenia.

---

## 13. Etap 3 — oś awarii (29.09.2026)

Etap, który §7 kazał odciąć i zrobić osobno. Dowozi prawo I7 i jedno znalezisko, którego §§1–12 nie
przewidziały — bo dotyczy nie portalu, a momentu, w którym wolno o portal pytać.

### 13.1 Świat, który potrafi się cofnąć

`world.UnitsOfWork` to `UnitOfWork`, któremu można powiedzieć, że **następna** transakcja się nie
zacommituje. Do 29.09 runner wstawiał wszędzie `Runnable::run`, czyli jednostkę pracy, która zawsze
się udaje — i dlatego cały powód istnienia `AtomicClosureParticipant` („ukryte wiersze, o których
nikt nie jest winien słowa" i „słowo o wierszach, których nikt nie ukrył") był tutaj niewypowiadalny.

Wycofanie stawia świat tak, jak go krok zastał: `Portal.snapshot()`, a pod nim trzy fake'i, które
oddają `Snapshot`, zamiast dać sobie nadpisać wiersze. Droga powrotna idzie **tymi samymi drzwiami,
którymi piszą use case'y** — `store` na marka, `add`/`remove` na zapisany wskaźnik — więc żaden
restore nie postawi świata w stanie, do którego use case nie umiałby dojść.

Zasięg snapshotu to zasięg `fingerprint()`, i to celowo: prawa czytają portal wyłącznie przez odcisk,
więc czego odcisk nie widzi, tego nie warto przywracać, a co widzi — trzeba. Dlatego w środku są też
głosy i dial administratora: `PurgeUserContent` wycofuje głosy odchodzącej **przed** odczytem score'u,
a wycofanie, które zostawiłoby je wycofane, zmieniłoby decyzję następnej próby.

Trzy granice wypowiedziane od razu, zamiast odkryte później:

- to jest **stan postawiony z powrotem, nie odtworzony dziennik zapisów**. Jest poprawne tylko
  dlatego, że nic tu nie biegnie obok jednostki pracy: krok kończy się przed następnym, więc „jak
  było, gdy to się zaczęło" i „jak byłoby, gdyby to się nie wydarzyło" są tym samym światem. Runner
  z dwoma wątkami potrzebowałby dziennika;
- **zagnieżdżenie dołącza.** Jednostka pracy zaczęta w środku już trwającej to ta sama transakcja —
  jeden snapshot, jedno zakończenie — czyli to, co daje prawdziwy menedżer transakcji use case'owi,
  który otwiera swoją;
- staged jest **kształt awarii, nie obietnica o Postgresie**. Tamto zostaje w testach JDBC i w `e2e/`.

### 13.2 Outbox: słowo wychodzi z transakcją albo nie wychodzi wcale

Potwierdzenie obu atomowych uczestników budował dotąd **bus**, z liczby, którą uczestnik zwrócił;
port `ClosureConfirmations` był podłączony do `(sagaId, leaver, reserved) -> { }`. Słowo zbudowane
w ten sposób nie umie się rozjechać z markiem, cokolwiek padnie — co jest wygodne i co czyni jedyną
awarię, przed którą `AtomicClosureParticipant` broni, niewypowiadalną w jedynym miejscu, gdzie oba
protokoły się spotykają.

Teraz każdy atomowy uczestnik potwierdza **swoim portem**, port pisze do jednostki pracy, a
`UnitsOfWork#onCommit` wysyła to, co transakcja zacommitowała, i wyrzuca to, co wycofała. Tymi samymi
drzwiami idą ogłoszenia kaskady: `MEME_DELETED` z nieodwracalnej połowy zamknięcia i
`COMMENTS_DELETED` z purge'a komentarzy — dokładnie tak, jak `KafkaMemeEvents` pisze je do tabeli
outboxa, a nie wprost do brokera. Poza jednostką pracy rekord wychodzi od razu, bo nic go nie trzyma.

Kolekcje dalej potwierdzają przez swojego **konsumenta**, i ta asymetria jest treścią, nie
przeoczeniem: nie mają outboxa, do którego by pisały, więc nie mają transakcji do współdzielenia. To
ten sam podział, który robią kontrakty estate'u (`ClosureParticipantContractTest` vs
`AtomicParticipantContractTest`).

### 13.3 Dwie końcówki w przeszukiwaniu — i model offsetu

Na każdym kroku, którego konsument pracuje w transakcji (dwaj atomowi uczestnicy i hop komentarzy
kaskady), eksplorator dostaje dwie dodatkowe opcje:

- **transakcja się wycofuje.** Rekord wraca na **głowę** swojego pasa, bo konsument, który nie
  zacommitował, nie przesunął też offsetu — broker wciąż jest mu ten rekord winien. Zamodelowanie
  nieudanej dostawy jako **zgubionego** rekordu byłoby tym samym błędem, co modelowanie leżącego
  uczestnika przez wyrzucanie jego wiadomości (§12.5), o jeden poziom niżej. Ma własny test;
- **commituje i proces umiera, zanim outbox zostanie wysłany.** Wiersze zapisane, słowo
  zacommitowane i niewysłane, a relay może przyjść w dowolnym momencie. Relay jest **zawsze
  dostępny i nigdy obowiązkowy**, więc przeszukiwanie pyta, czy portal przeżyje każde jego
  **opóźnienie** — i żaden harmonogram nie kończy się z pełnym outboxem.

Zawartość outboxa jest częścią stanu (to tabela), więc wchodzi do odcisku przeszukiwania; w pliku
konfluencji linia o niej pojawia się tylko wtedy, gdy outbox coś trzyma — pusty outbox nie jest
faktem o harmonogramie, a linia mówiąca to w każdym pliku nie mówiłaby nic.

Czego oś **nie** dotyka: kolekcji na obu protokołach (nie mają transakcji) i **orkiestratora** — jego
własny store nie jest częścią świata, który snapshot przywraca, więc jego transakcja jest granicą tej
warstwy, a nie czymś staged byle jak.

### 13.4 Prawo I7, i kiedy wolno je pytać

> **Część, która mówiła, jest trzymana przez tę samą część.**

Pytane w ciszy i tylko dopóki sprawa jest **otwarta**: oba działania, które zdejmują marka — ERASE
i RESTORE — są komenderowane w tym samym kroku, który produkuje werdykt, więc cichy harmonogram bez
werdyktu to taki, w którym nic nie miało jeszcze okazji zdjąć rezerwacji części, która coś
zarezerwowała. Po werdykcie pytają o to dwa inne prawa: „sprawa zamknięta i nic nie jest
zarezerwowane" oraz „purged znaczy, że portal nie trzyma nic tej osoby".

O części, która potwierdziła **zero**, prawo nie mówi nic: zero to prawdziwa odpowiedź części, która
nie ma nic tej osoby, i re-komenderowanego MARK-a, który znajduje wszystko już zarezerwowane.

### 13.5 Znalezisko 5 — prawo pytane w złym momencie

Moje własne, drugie tej klasy po §12.5a, i warte tyle samo.

Prawo „część nigdy nie potwierdza więcej, niż trzyma" było pytane tam, gdzie rekord **wychodzi**.
Z outboxem to są dwa różne momenty i to jest cały sens tabeli: słowo powstaje w transakcji, która
ukryła wiersze, a relay wysyła je, kiedy przyjdzie — a wtedy erazura może już zabrać każdy wiersz,
o którym słowo mówiło. Pierwszy przebieg `a-word-that-waits` zgłosił dokładnie to jako część, która
kłamie. To był outbox działający poprawnie.

Naprawione: prawo jest pytane tam, gdzie słowo **powstaje**. Morał ten sam, co poprzednio:
narzędzie do szukania wyścigów, którego nie sprawdzono na własnym mechanizmie, zgłasza swój model.

### 13.6 Test mutacyjny, bez którego etap byłby zdaniem bez dowodu

`SENDS_AND_THEN_ROLLS_BACK` — słowo poszło, praca nie — to jedyna awaria, którą outbox czyni
niemożliwą, więc **nic nie oferuje jej przeszukiwaniu**: warstwa, która inscenizuje awarię wykluczoną
własnym projektem i potem ją zgłasza, mierzy swoją inscenizację. Jeden test stawia ją ręcznie
i sprawdza, że prawo pęka: część mówi, że zarezerwowała mema, mark się wycofuje, saga idzie dalej do
erazury, która nie ma czego zetrzeć, i portal mówi tożsamości, że treść jest wyczyszczona, kiedy
treść stoi. Bez tego „żadne prawo nie pękło na żadnym harmonogramie" byłoby zdaniem bez dowodu, że
prawo w ogóle mogło pęknąć.

### 13.7 Liczby

| ziarno | harmonogramów | węzłów | stanów końcowych |
|---|---:|---:|---:|
| `a-unit-of-work-that-fails` | 10 | 983 | **1** |
| `a-word-that-waits` | 37 | 189 331 | **3** |

Oba wyczerpujące, żadne prawo złamane. Trzy końce `a-word-that-waits` to ten sam kształt, co wszędzie
indziej w tej warstwie: **wiersze są identyczne** — portal nie trzyma nic jej — a różni się liczba
w potwierdzeniu, bo re-komenderowany MARK znajduje wszystko już zarezerwowane i prawdziwie potwierdza
0, kiedy pierwsze słowo leży jeszcze w outboxie. Kolejność zmienia to, co portal obiecał; nie zmienia
tego, co trzyma.

Koszt: `portal-specs` to **87 testów** (67 speców, 5 na samo wycofanie, 13 ziaren, 2 na model osi),
`clean test` w 2 min 34 s. Jedenaście starszych ziaren kończy dokładnie tam, gdzie kończyło —
budżet awarii jest zerowy, dopóki ziarno o niego nie poprosi.

### 13.8 §3.3 poz. 2 po fakcie

| poz. §3.3 | stan |
|---|---|
| 2. jednostka pracy | **zmierzone w swojej klasie.** `Runnable::run` zastąpiony jednostką pracy, która potrafi się nie udać; obietnica „ogłoszenie dzieli los pracy w obie strony" jest tu sprawdzona na każdym harmonogramie, w którym jedna transakcja nie commituje. Nadal **nie** dowodzi, że Postgres i outbox tak się zachowają — to zostaje w `KafkaMemeEventsTransactionTest` i w `e2e/` |

### 13.9 Co zostaje

- ~~**Orkiestrator.**~~ **Zrobione — §13.10.**
- ~~**Budżet awarii większy niż jeden.**~~ **Zmierzone — §13.12.**
- **Redukcja częściowego porządku** — dalej pierwsza rzecz do policzenia, jeśli ziaren z sweepami
  albo awariami dojdzie więcej (§12.10).

### 13.10 Orkiestrator też ma transakcję (29.09.2026, później tego dnia)

`Portal.alsoRestoring(...)` wpuszcza do snapshotu wiersze, które należą do tego świata, a trzyma je
ktoś inny — czyli tabelę sag, bo mieszka w busie. Krok z potwierdzeniem biegnie teraz w jednostce
pracy świata, a każdy rekord, który orkiestrator produkuje, wychodzi przez outbox: wycofana obsługa
zostawia potwierdzenie na głowie partycji i sagę taką, jaka była. Pas orkiestratora wchodzi do
`TRANSACTIONAL`.

Nowe prawo, jego połowa I7: **werdykt, który wyszedł, jest zapisany jako wyszedł.** Sprawdzone
mutacją i okazało się mocniejsze od błędu, który je podpowiedział: po wyjęciu `markAnnounced` — czyli
w stanie, w jakim ten runner był do 29.09 — prawo pęka na **13 z 16** testów, każdy na pierwszym
przejrzanym harmonogramie. §12.4 potrzebowało ziarna, które zamiecie już zakończoną sprawę; to prawo
pada na każdym ziarnie, które sprawę domyka.

`an-orchestrator-that-fails`: 11 harmonogramów, 766 węzłów, **1 stan końcowy**, wyczerpujące. I wynik
wart ziarna: udostępnienie transakcji orkiestratora do awarii **nie dodaje ani jednego nowego stanu
końcowego** — dwa starsze ziarna awaryjne urosły o kilka tysięcy węzłów i kończą dokładnie w tych
samych zbiorach. 88 testów zielonych.

Zostaje z tego jedna granica: wycofanie transakcji, która **otwiera** sagę. Referencyjny store nie
umie zapomnieć wiersza (zmiana w innym repo), a sprawę otwiera wyłącznie fakt dostarczony poza
drutem — nigdy krok, który ta warstwa umie zepsuć.

### 13.11 Sweeper, i dwa rodzaje producenta (29.09.2026, wieczór)

Transakcja sweepera: to, co wybrał, doliczone retry i wyprodukowane rekordy to jedna jednostka pracy.
Zegar rusza niezależnie — transakcja, która nie commituje, nie oddaje czasu, więc następny tik
znajduje tę samą sprawę przeterminowaną.

**Znalezisko 6 — moje, trzecie tej klasy.** Pierwsza wersja modelowała rekordy sweepera jako wiersze
outboxa, czekające na relay. `KafkaLoop#sweep` wysyła je **wprost do brokera**, flushuje i dopiero
potem oznacza sagi (`settleDeliveries`) — więc śmierć między commitem a wysłaniem **gubi** te rekordy,
a naprawą jest następny sweep. Model z relayem dał prawo złamane („sprawa zamknięta i nic nie jest
zarezerwowane"): re-komenda MARK zatrzymana w outboxie, wysłana **po** kapitulacji, rezerwowała
wiersze, których nic już nigdy nie zwolni. Brzmi jak realna wada — i nie jest, bo takiego wiersza
outboxa w produkcji nie ma. Trzeci raz to samo: warstwa niesprawdzona na swoim własnym mechanizmie
zgłasza swój model.

Stąd dwa rodzaje producenta i dwa zakończenia:

| producent | jak publikuje | zakończenie „commit i śmierć przed wysłaniem" | naprawa |
|---|---|---|---|
| trzech uczestników | wiersz outboxa w tej samej transakcji (`SpringOutbox` + republisher) | `COMMITS_AND_SAYS_NOTHING_YET` — wiersz czeka | relay |
| orkiestrator i jego sweeper | wprost do brokera, znacznik po udowodnionym wysłaniu | `COMMITS_AND_LOSES_WHAT_IT_SAID` — rekord przepada | następny sweep |

Przy okazji `markAnnounced` przeniesione z chwili **wyprodukowania** werdyktu na chwilę jego
**wysłania**, bo tak robi `KafkaLoop`: werdykt zgubiony między commitem a wysłaniem musi zostać
nieoznaczony, żeby sweeper go powtórzył.

Nowe ziarno `a-sweep-that-fails` (kolekcje leżą, cztery tiki cierpliwości, jedna awaria):
28 harmonogramów, 35 940 węzłów, **4 stany końcowe**, wyczerpujące, żadne prawo złamane.
`an-orchestrator-that-fails` dostało jeden tik cierpliwości, żeby naprawa też była w drzewie:
43 harmonogramy, 28 562 węzły, 4 stany.

Trzy ziarna awaryjne zyskały po jednym nowym stanie końcowym tego samego kształtu: **werdykt zgubiony,
nikomu nie powiedziano, wiersze zarezerwowane**. To prawdziwe zdanie o portalu przy skończonym budżecie
sweepów — wdrożony sweeper tyka bez końca i następny tik te rekordy wystawia ponownie. Zapisane
w `specs/races/README.md`, żeby nikt nie czytał tego stanu jako utraconej treści.

89 testów zielonych.

### 13.12 Dwie awarie w jednym harmonogramie — zmierzone

`a-unit-of-work-that-fails` podniesione do dwóch awarii: **983 → 1 900 węzłów, 10 → 21 harmonogramów,
zbiór stanów końcowych bez zmian.** Czyli suma, nie iloczyn — wbrew temu, co §13.9 zgadywało.

Dlaczego: krok, który się wycofał, zostawia świat tam, gdzie go znalazł, więc druga awaria prawie
zawsze bada stan, do którego pierwsza już doszła, a memoizacja po odcisku to ścina. Iloczyn zostaje
realnym ryzykiem tylko tam, gdzie awaria **zmienia** stan trwale — czyli przy „commituje i gubi", i
w ziarnach ze sweepami (`a-word-that-waits` ma 209 tys. węzłów przy jednej awarii i dwóch sweepach,
i tam budżetu nie podnoszę bez policzenia).