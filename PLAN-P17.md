# Plan pracy po przeglądzie P17 — 2026-07-29 (późny wieczór)

> ## STAN REALIZACJI — czytaj to najpierw
>
> Przegląd: Fable 5, ultracode, recenzenci wymiarowi + 3 przeciwstawne soczewki weryfikacji.
> Materiał: wyłącznie POPRAWKI z planu P16 wgrane 2026-07-29 wieczorem — kod dwie warstwy
> naprawy głęboko (naprawy napraw znalezisk P15), nieczytany wcześniej przez nikogo poza autorem.
> **2 znaleziska potwierdzone (każde 3/3 głosy), 0 odrzuconych w podważaniu.**
>
> | | |
> |---|---|
> | ZROBIONE | **2 z 2** — obie pozycje zamknięte 2026-07-29 późnym wieczorem, w głównej pętli |
> | ZOSTAJE | **0** obowiązkowych; **1 świadomie pominięta opcja** (republikacja rekordów legacy pod nowym kluczem — poz. 2, szczegóły w **Stan:**) |
> | Build | security: `mvn clean verify` ZIELONY (118 testów, 0 porażek, 20:01); email: `mvn clean verify` ZIELONY (44 testy, 0 porażek, 19:58) |
> | Blokuje wdrożenie na k3s | **nic.** Poz. 1 (WYSOKI) odtwarzała dokładnie klasę awarii, którą P16 poz. 2 zamknęła tego samego wieczoru — lampa wyciągała identity z Service podczas zwykłego backoffu retry. Naprawione dyskryminatorem czasowym; dowód: chirurgiczny revert daje 3 czerwone testy, przywrócenie poprawki — 10/10 zielonych. |
>
> ### Jak zamknięto (2026-07-29, późny wieczór)
>
> | poz. | repo | pliki | dowód, że test umie paść |
> |---|---|---|---|
> | 1 | security | `OffboardingListenerHealth.java` + `OffboardingListenerHealthTest.java` | TAK — chirurgiczny revert zachowania (stara jednolinijkowa gałąź `isPaused` ⇒ od razu porzucona): 3 czerwone (`a_partition_between_retries_is_up`, `a_resumed_pause_starts_a_fresh_clock`, pierwsza asercja `an_abandoned_partition_is_down`); po przywróceniu 10/10 zielonych |
> | 2 | email | `ParkedMails.java` (wyłącznie javadoc) | NIE SPRAWDZONO — zmiana w samym javadocu, zero kodu wykonywalnego; nie istnieje test runtime'owy, który mógłby paść po cofnięciu, a test grepujący treść źródła byłby naciągnięty (repo nie ma takiego wzorca). Zachowanie opisywane przez akapit pokrywa istniejący `DlqLedgerKeysTest.two_legacy_id_less_records_are_two_entries` (zielony w buildzie) |
>
> **Uwaga do samego siebie:** revert dla poz. 1 był chirurgiczny, nie `git checkout HEAD` — pełne
> cofnięcie pliku dawało tylko błąd kompilacji (nowa stała nie istniała w starym pliku), czyli
> „czerwono", które nie dowodzi niczego o zachowaniu. Stałą zostawiono, przywrócono starą logikę,
> i dopiero to dało czerwień z właściwego powodu. Bramka, która nie sprawdza tego, co się wydaje,
> potrafi być także po stronie dowodu.
>
> ### Co ten przegląd mówi o poprzednim
>
> Obie pozycje siedzą w plikach POPRAWIANYCH w P16 i obie są tą samą klasą wady, którą P16 tępił:
> zdanie o mechanizmie frameworka, fałszywe od dnia napisania, wpisane do pliku podczas usuwania
> takiego samego zdania. Poz. 1 to wprost druga połowa P16 poz. 1: wykrycie wyczerpanych retry
> działa, ale dyskryminator „backoff vs porzucenie" został zbudowany na javadocowej obietnicy,
> której bajtkod micronaut-kafka nie dotrzymuje. Poz. 2 to przegięcie sprostowania z P16 poz. 3
> w drugą stronę: z fałszywego „przetrwa co najwyżej jeden" zrobiło się fałszywe „nie skompaktuje
> nic". Test `a_partition_between_retries_is_up` przechodził z niewłaściwego powodu — stubował
> stan, którego runtime nie produkuje — czyli dokładnie ta klasa wady, która stworzyła P16 poz. 1.
>
> ### Zasady pracy nad tymi poprawkami
> - Fable robi przegląd, Opus pisze kod — implementacja zawsze w głównej pętli, nie w subagencie.
> - Każda naprawa to najmniejsza zmiana zamykająca wadę.
> - **Test musi umieć paść** — po napisaniu cofnąć poprawkę i sprawdzić, że świeci na czerwono;
>   dla zmian czysto dokumentacyjnych (poz. 2) uczciwie odnotować, że ten krok nie ma przedmiotu,
>   zamiast dopisywać test na niby.
> - Po każdej pozycji: pełny `mvn verify` w repo, i dopiero wtedy dalej.


**Podsumowanie.** Rewizja napraw napraw — i wzorzec z P15/P16 powtórzył się trzeci raz, znów piętro wyżej: **jedyne znalezisko WYSOKIE to dyskryminator zbudowany tego samego wieczoru w naprawie P16 poz. 1, który rozróżnia dwa stany konsumenta na podstawie javadocowej intuicji sprzecznej z bajtkodem micronaut-kafka**. `delayRetry` (backoff między próbami) woła TEN SAM `ConsumerState.pause` co `stopOnExhaustedRetry`, więc `isPaused` odpowiada true także przez cały backoff — lampa orzekała „gave up for good" podczas samonaprawialnego odzysku i wyciągała identity z Service dokładnie w klasie awarii, którą P16 poz. 2 zamknęła kilka godzin wcześniej, każąc przy tym operatorowi restartować poda bez potrzeby. Poz. 2 to miniatura tej samej choroby w javadocu ledgera maila: sprostowanie z P16 poz. 3 przegięło w drugą stronę i ogłosiło bezidowe rekordy legacy niekompaktowalnymi „przez nic" — a skompaktuje je każdy nowszy rekord o wspólnym kluczu `"<no id>"`, gdy aktywny segment się zroluje. Dobra wiadomość: dwa znaleziska zamiast dwunastu, oba w wąskim promieniu wczorajszych diffów, zero w logice biznesowej i zero w kodzie sprzed tego dnia. Wniosek operacyjny bez zmian, tylko ostrzej: zdanie o zachowaniu frameworka wolno napisać dopiero po zajrzeniu w bajtkod albo po teście, który modeluje stan faktycznie produkowany przez runtime — stub `isPaused=false` dla backoffu był dokładnie takim samym fałszywym świadkiem jak metryka resetująca się co poll w P16.

---

## WYSOKI

### 1. Dyskryminator `isPaused` w lampie listenera nie istnieje — backoff między retry przechodzi przez ten sam `ConsumerState#pause` co `stopOnExhaustedRetry`, więc lampa orzeka DOWN „gave up for good" podczas zwykłego backoffu i wyciąga identity z Service w trakcie samonaprawialnego odzysku
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/OffboardingListenerHealth.java`
**Co robi kod:** Naprawa P16 poz. 1 (`cc39e7d`) dodaje gałąź `abandonedPartitions`: każda partycja, dla której `ConsumerRegistry.isPaused` zwraca true, jest raportowana jako „gave up on ... after the retries were spent — paused for good ... only a restart replays it" i daje DOWN w grupie readiness. Javadoc uzasadnia: „a retry's backoff pause is applied to the Kafka consumer directly, while stopOnExhaustedRetry goes through ConsumerState#pause ... isPaused answers true for given up on and false for backing off between attempts. No timer, no tolerance". Bajtkod micronaut-kafka 6.1.0 mówi co innego: `delayRetry(Duration,Set)` — backoff między próbami — woła TEN SAM `ConsumerState.pause(Collection)` (dodaje do `pauseRequests`) i planuje `resume(Collection)` po upływie delayu; `pauseTopicPartitions()` na początku każdej iteracji pętli kopiuje `pauseRequests` do konsumenta Kafki i do `pausedTopicPartitions`; `isPaused` wymaga obecności w OBU zbiorach — więc odpowiada true także przez cały czas backoffu (od następnej iteracji poll do zaplanowanego resume). Dwa zdania javadocu są nieprawdziwe, a plik przeczy własnemu, poprawnemu javadocowi `STALL_FLOOR` („resuming when the delay is up"). Wykrycie stanu wyczerpanych retry działa (nic nie usuwa pause requestu), więc tę połowę P16 poz. 1 naprawa zamyka.
**Awaria:** Listener ma `RETRY_EXPONENTIALLY_ON_ERROR`, retryDelay=1s, retryCount=10 → okna backoffu 1, 2, ..., 512 s; od 6. próby (32 s+) każde okno przekracza próg readiness (3×10 s). Przejściowa awaria bazy ~1 min: rekord wchodzi w długie backoffy, baza wraca, `JdbcIndicator` zielony — ale lampa (`@Readiness`) trzyma DOWN do końca bieżącego okna, do 512 s. Pod security NotReady → sign-in, refresh i introspekcja tokenów znikają z Service dla OBU produktów podczas najzdrowszego możliwego odzysku — dokładnie klasa awarii, którą P16 poz. 2 zamknęła tego samego wieczoru — a komunikat lampy fałszywie każe operatorowi restartować („paused for good, only a restart replays it"). Test `a_partition_between_retries_is_up` przechodzi z niewłaściwego powodu: stubuje `isPaused→false` dla stanu backoffu, którego runtime tak nie raportuje — ta sama klasa wady (test modeluje stan nieprodukowany przez runtime), która stworzyła P16 poz. 1.
**Najmniejsza naprawa:** Zgodnie z alternatywą z samego PLAN-P16 poz. 1: uznawać partycję za porzuconą dopiero, gdy `isPaused` utrzymuje się dłużej niż budżet retry (próg konfigurowalny wzorem `stallTolerance`); albo wariant deterministyczny — własny `KafkaListenerExceptionHandler` ustawiający flagę „exhausted" czytaną przez lampę. W obu wariantach sprostować javadoc `abandonedPartitions` (backoff też przechodzi przez `pauseRequests`) i komunikat DOWN; test backoffu przepisać tak, by modelował `isPaused=true` krótsze niż próg, a nie `isPaused=false`.
**Stan:** **WDROŻONE** — wariant czasowy. Uwaga: próg musi przekraczać **SUMĘ** backoffów (1+2+...+512 s = 1023 s), nie najdłuższe pojedyncze okno, bo sonda może nie trafić w krótkie resume między oknami — stąd `ABANDON_TOLERANCE = 20 min`, czyli cały budżet retry z zapasem na inwokacje handlera. Lampa (singleton) pamięta pierwszy moment zaobserwowania pauzy per partycja (`pausedSinceNanos`, ten sam wstrzykiwany nanoTime co reszta lampy); partycja widziana jako wznowiona zeruje zegar, więc dwa incydenty w odstępie dni się nie sumują. Sprostowany javadoc `abandonedPartitions` (delayRetry woła pause i planuje resume — potwierdzone `javap` na bajtkodzie z `~/.m2`), bullet w javadocu klasy i komunikat DOWN („paused longer than the whole retry budget, so no scheduled resume is coming"). Testy: `an_abandoned_partition_is_down` przepisany na dwa odczyty (start zegara + przekroczenie progu), `a_partition_between_retries_is_up` modeluje teraz `isPaused=true` przez 512 s (stan faktycznie produkowany przez runtime) zamiast `isPaused=false`, plus nowy `a_resumed_pause_starts_a_fresh_clock` pilnujący zerowania zegara. Dowód, że test umie paść: chirurgiczny revert starej gałęzi ⇒ 3 czerwone, przywrócenie ⇒ 10/10 zielonych; pełny `mvn verify` security ZIELONY (118 testów).
**Dowody:** `javap` na `micronaut-kafka-6.1.0.jar`: `ConsumerState.delayRetry(Duration,Set)` → `invokevirtual pause(Collection)` + `scheduleTask(lambda→resume)`; `pause(Collection)` → `pauseRequests.addAll`; `pauseTopicPartitions()` → `kafkaConsumer.pause` + `pausedTopicPartitions.addAll`; `isPaused(Collection)` → `pauseRequests.containsAll && pausedTopicPartitions.containsAll`; `ConsumerStateSingle.stopOnExhaustedRetry` → seek, handleException, ten sam `pause(Collection)`. `KafkaConsumerProcessor.isPaused` deleguje do `ConsumerState.isPaused`. Konfiguracja listenera: `OffboardingOutcomeListener.java:66-70`.

---

## NISKI

### 2. Javadoc `ledgerKey` twierdzi, że starych bezidowych rekordów „nie skompaktuje nic" — a skompaktuje je każdy nowszy rekord o wspólnym kluczu `"<no id>"`, gdy aktywny segment się zroluje; niewidzialność starszego niedostarczonego maila, którą P16 poz. 3 ogłasza zamkniętą, pozostaje otwarta po stronie brokera
**Plik:** `shared/microservice-email/src/main/java/com/jrobertgardzinski/mail/boundary/ParkedMails.java:86-99`
**Co robi kod:** Naprawa P16 poz. 3 (`2138fd0`) słusznie kluczuje bezidowe legacy rekordy per rekord w ledgerze i przepisuje javadoc, prostując fałszywe „at most one of them survives there". Ale nowy akapit przegina w drugą stronę: „every id-less record ever parked is still on the broker" oraz „a mail parked before 2026-07-29 without an id cannot be compacted by anything" (commit message: „nothing will ever compact them away"). Wszystkie te rekordy dzielą na topicu JEDEN klucz Kafki `"<no id>"` — więc gdy aktywny segment się zroluje (dowolny append po `segment.ms`; topic dostaje nowe parkowania i retrakcje), log cleaner zostawia pod `"<no id>"` wyłącznie rekord o najwyższym offsecie i usuwa starsze — tym chętniej, że `docker-compose.identity.yml:289` ustawia `min.cleanable.dirty.ratio=0.1`. Ten sam akapit dwie linijki wyżej sam przyznaje, że segment „MAY never roll" — czyli może się zrolować — a mimo to formułuje trwałość bezwarunkowo.
**Awaria:** Estate ma ≥2 bezidowe rekordy sprzed 2026-07-29 (dokładnie przesłanka całej naprawy — test parkuje dwa); nowszy z nich NIE jest rozliczony, starszy też nie. Po zrolowaniu segmentu i przebiegu cleanera broker niszczy starszy rekord (nowszy pod `"<no id>"` go kompaktuje); po następnym restarcie replay go nie widzi i wpis znika z ledgera bez śladu — dokładnie „silent destruction of evidence", którą javadoc ogłasza niemożliwą. Operator i następny przeglądający, czytający „still on the broker / cannot be compacted by anything", uznają dowód za trwały i nie redrive'ują zawczasu; to samo zdanie w commit message `2138fd0` utrwala fałsz w historii. Klasa wady identyczna z P16 poz. 7/11/12: zdanie o mechanizmie frameworka, fałszywe od dnia napisania, w pliku poprawianym przy usuwaniu takiego samego zdania.
**Najmniejsza naprawa:** Przepisać koniec akapitu do stanu faktycznego: retrakcja nigdy nie skompaktuje rekordu `"<no id>"`, ale NOWSZY rekord `"<no id>"` tak — po zrolowaniu aktywnego segmentu cleaner (dirty ratio 0.1) zostawia tylko ostatni, więc starszy nierozliczony legacy mail może jeszcze zniknąć z brokera; ledger chroni go tylko dopóki broker go ma. Analogicznie sprostować „still on the broker". Opcjonalnie: przy replayu republikować rekord legacy pod jego nowym kluczem hashowym, co domyka lukę naprawdę.
**Stan:** **WDROŻONE** w części obowiązkowej — „still on the broker" jest teraz warunkowe („until it does", tj. do zrolowania aktywnego segmentu), a bezwzględne „cannot be compacted by anything" zastąpione faktycznym mechanizmem (nowszy rekord `"<no id>"` kompaktuje starszy po zrolowaniu segmentu, `min.cleanable.dirty.ratio=0.1` z compose); javadoc mówi wprost, że ledger chroni starszy legacy mail tylko dopóki broker go trzyma i że nierozliczone wpisy legacy trzeba redrive'ować zawczasu, a nie traktować jak trwały dowód. **POMINIĘTE świadomie:** (a) republikacja rekordów legacy pod nowym kluczem hashowym przy replayu — zmiana behawioralna wykraczająca poza najmniejszą zmianę zamykającą wadę; samo znalezisko oznacza ją jako „opcjonalnie"; (b) sprostowanie commit message `2138fd0` — niewykonalne bez przepisywania historii (zakaz commitowania). Build email ZIELONY (44 testy). Testu-który-umie-paść nie ma z natury zmiany (sam javadoc); zachowanie opisywane przez akapit pokrywa istniejący `DlqLedgerKeysTest.two_legacy_id_less_records_are_two_entries`.
**Dowody:** Lektura pełnych źródeł `ParkedMails.java`/`DlqResource.java`/`MailRequestsConsumer.java` i diffu `5afc33f..HEAD`; semantyka log cleanera Kafki (retencja najwyższego offsetu per klucz w części cleanable, aktywny segment nietykany); konfiguracja topicu w `docker-compose.identity.yml:288-294` (`cleanup.policy=compact`, `min.cleanable.dirty.ratio=0.1`, 1 partycja). Wewnętrzna sprzeczność akapitu („may never roll" vs „cannot be compacted by anything") widoczna w samym pliku.

---

## Czego celowo NIE raportuję

- **Nic nie padło w podważaniu.** Obie kandydatury przeszły 3/3; żadnego znaleziska nie odrzucono, więc — inaczej niż w P16 (hook postStart, 2/3) — ta sekcja nie zawiera obalonych zgłoszeń, tylko stałe wyłączenia zakresu.
- **Pozostałe 10 poprawek P16** — czytane w tym przebiegu i nieobalone; brak kontrdowodu po jednym przebiegu nie jest dowodem prawdziwości, ale żadna nie wygenerowała znaleziska.
- **Jackson 3 i Flyway 13** — świadome, udokumentowane odroczenia; nie są znaleziskami z definicji.
- **Dobór Kafki jako brokera** — decyzja zamknięta; obie pozycje powyżej dotyczą wyłącznie poprawnego użycia (semantyka pause/backoff, semantyka log cleanera).
- **Observability w manifestach k8s** — celowo poza zakresem, udokumentowane.
- **formula/** — inny produkt, nie czytany; ten raport nie mówi o nim nic.
- **Wszystko spoza diffów poprawek P16** — czytane w P15/P16, ocena stoi; obie pozycje powyżej siedzą w plikach zmienionych 2026-07-29 wieczorem jako naprawy P16.
- **Styl, nazewnictwo, „warto wyekstrahować", spekulatywna skalowalność, długość komentarzy** — poniżej progu; fałszywy komentarz jest znaleziskiem (obie pozycje tego planu), długi nie jest.
- **Konwencja `_ClassName`** — celowa (ADR 0002).
- Nie proponuję żadnych nowych frameworków, warstw ani przepisań — obie naprawy powyżej to najmniejsze zmiany zamykające konkretną wadę; jedyna odłożona rzecz (republikacja legacy pod kluczem hashowym) jest odłożona dlatego, że wykracza poza tę zasadę, nie dlatego, że jest zła.