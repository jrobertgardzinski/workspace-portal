# Zakres przeglądu P16 — kod z 2026-07-29, którego nikt poza autorem nie czytał

## Po co to

Przegląd P15 (Fable 5, ultracode) czytał stan **porannego** repozytorium i wyprodukował
`PLAN-P15.md` — 24 pozycje, wszystkie zamknięte. Od tamtej pory w ośmiu repozytoriach przybyły
**23 commity, ~2860 linii**, i **żadnej z nich Fable nie widział**. To jest kod, który powstał
JAKO NAPRAWA znalezisk — czyli dokładnie ta kategoria, w której najłatwiej o wadę wprowadzoną
przy usuwaniu innej.

Że to nie jest hipotetyczne: mój własny przegląd tego materiału znalazł sześć rzeczy, w tym
**regres wprowadzony przez samą naprawę** (nadanie bezidowym mailom UUID zamknęło niszczenie
rekordów przez kompakcję i otworzyło ich mnożenie przy każdym nieudanym redrivie) oraz **dwie
awarie runtime'owe przy w pełni zielonym `mvn verify`**.

A siódmą znalazłem **pisząc ten dokument**: pierwsze twierdzenie, które postanowiłem tu wypisać do
obalenia, okazało się fałszywe po dwóch sesjach psql — wyścig rotacji z unieważnieniem rodziny był
otwarty na ścieżce JDBC, w kodzie, którym rano zamykałem ten sam wyścig w adapterze in-memory
(poz. 1 niżej, naprawione). To jest najlepszy dostępny argument za tym przeglądem: samo spisanie
własnych twierdzeń w formie „to jest do obalenia" wywróciło jedno z nich w kwadrans.

Nie zakładam, że znalazłem wszystkie.

## Co czytać — dokładne zakresy

| repo | zakres | commitów | rozmiar |
|---|---|---|---|
| `shared/microservice-security` | `e06ac79..3b837e1` | 5 | 19 plików, +1074 |
| `shared/microservice-email` | `d9c308f..5afc33f` | 3 | 10 plików, +533 |
| `portal/microservice-comments` | `81e2abb..11e9a92` | 6 | 10 plików, +520 |
| `portal/microservice-memes` | `3cf8580..495ebc2` | 4 | 12 plików, +260 |
| `portal` (workspace) | `5b29802..73a3518` | 5 | 9 plików, +249 |
| `portal/microservice-offboarding` | `57821cf..3f16a71` | 2 | 2 pliki, +158 |
| `shared` (workspace) | `87e311d..c3b4f07` | 1 | 2 pliki, +28 |
| `portal/microservice-user-collections` | `c1ea825..37a3a17` | 1 | 1 plik, +39 |

**Poza tymi zakresami nie ma czego szukać** — reszta była czytana rano i jej ocena stoi.

## Twierdzenia do obalenia

To jest właściwa robota. Każde z poniższych to zdanie, które napisałem w kodzie albo w commicie
i w które wierzę — a które ma konkretny, sprawdzalny kształt. Proszę je atakować, nie streszczać.

### security

1. ~~„Na ścieżce JDBC domyślna implementacja `rotateAndCreate` wystarczy, bo transakcja."~~
   **SPRAWDZONE PRZY PISANIU TEGO DOKUMENTU — TWIERDZENIE BYŁO FAŁSZYWE, NAPRAWIONE.**
   Odtworzone na PostgreSQL 16 dwoma sesjami psql: pod READ COMMITTED `DELETE` rewokujący rodzinę
   bierze snapshot na START STATEMENTU, blokuje się na wierszu zajętym przez rotację, a po
   commicie tamtej re-ewaluuje TYLKO ten wiersz (EvalPlanQual) — następca wstawiony w międzyczasie
   jest dla niego niewidzialny i **zostaje ACTIVE w rodzinie zgłoszonej jako unieważniona**.
   Transakcja tego nie zmienia. Naprawa: `lockFamily` (`SELECT … FOR UPDATE`) jako osobny statement
   przed `DELETE`, to samo dla „wyloguj wszędzie"; `SessionLineageRaceTest` odtwarza przeplecenie
   na Testcontainers i czerwienieje po usunięciu blokady.
   **Zostaje do oceny:** czy blokada jest w KAŻDEJ ścieżce, która niszczy sesje (usunięcie konta,
   `ExpiredSessionReaper`), i czy kolejność zakładania blokad nie tworzy zakleszczenia z innymi
   transakcjami tej tabeli.

2. **„Lampa mierzy pętlę, a nie brokera."** (`OffboardingListenerHealth`)
   `last-poll-seconds-ago` rośnie tylko wtedy, gdy pętla stoi — sprawdzone raz, na żywo, przy
   martwym brokerze. Czy są stany, w których konsument nie pollowa, a licznik nie rośnie
   (rebalans, pauza partycji przez retry, `stopOnExhaustedRetry` w trakcie zatrzymywania)? Jeśli
   przy zatrzymywaniu konsumenta metryka znika razem z nim, moja gałąź „the consumer is gone"
   raportuje DOWN — ale czy na pewno tam trafia?

3. **`STALL_FLOOR = 60s`, „bo retry nie śpią na wątku pollującym — micronaut pauzuje partycję".**
   Wyprowadzone z bajtkodu `ConsumerState` (jest `pause`/`resume`/`seek`, nie ma `Thread.sleep`),
   nie z pomiaru. Jeśli w jakiejś ścieżce retry jednak trzyma wątek, ten floor jest za niski
   i lampa będzie kłamać pod obciążeniem — czyli ta sama wada, którą naprawiałem w comments
   (poz. 13 z P15), tylko w drugą stronę.

4. **Filtr wygaśnięcia w `listActiveSessions`** — `findByEmailAndStatusAndRefreshTokenExpirationAfter`
   z `LocalDateTime.now(clock)`. Semantyka strefy: kolumna jest `timestamp` bez strefy, zegar to
   `Clock` wstrzykiwany (w compose systemowy UTC, w testach zamrożony). Czy w środowisku z
   `TZ != UTC` ten filtr nie zacznie ukrywać żywych sesji albo pokazywać martwych?

5. **`stopOnExhaustedRetry = true` jest lepszym z dwóch dostępnych zła.**
   Po wyczerpaniu retry kontener staje, lampa świeci DOWN, readiness wyciąga poda z Service — ale
   **nic go nie restartuje** (liveness celowo nie patrzy na lampę). Czy stan końcowy „pod żyje,
   nie obsługuje ruchu, nikt go nie budzi" jest tym, co chcieliśmy? Jeśli nie, to nie jest bug
   w kodzie, tylko w decyzji — i chcę to wiedzieć.

### email

6. **`parkedId` jest nadawany RAZ i jest tym samym stringiem na topicu, w ledgerze i w retrakcji.**
   Czy istnieje ścieżka, którą rekord trafia na `mail-requests-dlq` z pominięciem `park()`
   (`markRedriven` pisze kluczem ledgera — czy zawsze równym `parkedId`)? I czy zwijanie
   wszystkich starych bezidowych rekordów do jednego `unidentified-legacy` na pewno niczego nie
   traci, czego broker jeszcze ma?

7. **`ApiKeyFilter` przed `RateLimitFilter` dzięki `@Priority(AUTHENTICATION)`.**
   Sprawdzone zachowaniem (bezkluczowe żądania nie zjadają okna). Czy priorytet 1000 vs 5000
   jest wiążący w tej wersji Quarkusa dla WSZYSTKICH ścieżek (także dla filtrów pre-match)?

### comments

8. **„Szczegóły lampy są wolne od PII z konstrukcji."** (`show-details=always`)
   Sprawdziłem tyle: do szczegółów nigdy nie trafia treść rekordu, a rekord jest jedynym miejscem,
   gdzie jest adres wychodzącego użytkownika. Czego NIE sprawdziłem: `SagaListenersHealth.idOf` ma
   fallback `container.toString()` na wypadek `getListenerId() == null` — co dokładnie Spring Kafka
   tam wypisuje i czy ta gałąź jest w ogóle osiągalna dla naszych kontenerów. Aktuator comments
   zszedł z ingressu na 9085, ale w compose jest czytelny z hosta.

9. **„Zajęta pętla stempluje znacznik nie rzadziej niż raz na pełny budżet retry jednego rekordu."**
   (`SagaListenersHealth.recordDelivered`) Interceptor stempluje w `intercept` i `afterRecord`.
   Jeśli `DefaultErrorHandler` ponawia rekord w pamięci wewnątrz jednego `invokeOnMessage`, a nie
   przez seek i ponowny poll, to `intercept` NIE odpala się na próbę — i zdanie jest fałszywe
   dokładnie dla rekordu, który zjada cały budżet.

10. **Przeniesienie aktuatora na 9085 niczego nie zerwało.** Sprawdziłem compose, prometheus,
    manifest, overlay `dev`, `wait-healthy.sh`, `infra-smoke.sh`. Czy coś jeszcze puka do
    `comments:8085/actuator`?

### memes

11. **`no-store` dla `?wall=favourites` to właściwy koszt.** Ściana ulubionych przestała być
    cache'owana w ogóle. Przy 200 pozycjach to 200 żądań na każde otwarcie — czy `MakeThumbnail`
    na pewno serwuje wtedy z object store bez dekodowania, i czy limit wątków (32) tego nie boli?

12. **`FilterOrderTest` przypina FAKTYCZNĄ kolejność.** Porównuję dwa beany
    `AnnotationAwareOrderComparator`-em, a nie łańcuchem, który buduje Boot. Czy to na pewno ta
    sama reguła (`FilterRegistrationBean`, `OncePerRequestFilter`, auto-konfiguracja)?

### przekrojowo

13. **Sześć bramek „Prove the packaged service boots and answers its probes".**
    Każda przećwiczona lokalnie na prawdziwym artefakcie, dwie sprawdzone od strony padania.
    Czy któraś przechodzi z niewłaściwego powodu? W szczególności: bramka comments asserty
    „port requestów zwraca 404 na /actuator" przy porcie management podanym **ze zmiennej
    środowiskowej w samej bramce** — czyli dowodzi mechanizmu, nie wysyłanej wartości (tę pilnuje
    `ProbeUrlsTest`, czytając manifest). Czy taki podział na pewno pokrywa całość?

14. **Bramka security „chodzi w prawdziwym wiringu".** Środowisko `dev`, prawdziwy Postgres,
    warstwa Kafki, broker celowo martwy, `kafka.health.enabled=false`. Czy wyłączenie sondy
    klastra nie wyłącza przypadkiem czegoś jeszcze, na czym mi zależy?

## Czego NIE raportować

- **Jackson 3, Flyway 13** — świadome, udokumentowane odroczenia.
- **Dobór Kafki jako brokera** — decyzja zamknięta (uwagi o poprawnym UŻYCIU są w zakresie).
- **Observability w manifestach k8s** — celowo poza zakresem.
- **`formula/`** — inny produkt, nie ruszany.
- **Styl, nazewnictwo, „warto wyekstrahować", spekulatywna skalowalność** — poniżej progu.
- **Konwencja `_ClassName`** — celowa, ADR 0002.
- **Wszystko sprzed zakresów z tabeli** — czytane w P15, ocena stoi.
- **Długość komentarzy** — w tym projekcie komentarz tłumaczy DLACZEGO i jest to celowe. Fałszywy
  komentarz jest znaleziskiem; długi nie jest.

## Próg znaleziska

Ten sam co w P15: **znalezisko to konkretna wada z konkretną konsekwencją**, nie preferencja.
Najmocniejsze są te, które pokazują, że coś napisanego wprost jest nieprawdą — bo cały ten dzień
polegał na usuwaniu takich zdań, więc nowe są tym bardziej dotkliwe.

Preferowany kształt, jak w P15: plik i linia, „co robi kod", „awaria" (konkretny scenariusz),
„najmniejsza naprawa". Ocena ważności osobno od pewności.
