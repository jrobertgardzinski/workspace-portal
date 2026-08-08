# PODRĘCZNIK — jak działa ten system, na przykładzie usuwania konta

## Etap 1: przepływ „użytkownik usuwa konto"

---

## 0. Jak czytać ten podręcznik

Ten dokument nie jest dokumentacją referencyjną. Jest materiałem **do nauki własnego systemu**:
tak, żebyś umiał go opowiedzieć — na rozmowie technicznej, koledze, sobie samemu za pół roku.

**Zasada porządkująca:** jeden etap = jeden **przepływ biznesowy** (business flow), przeprowadzony
do samego dna kodu. Etap 1 to **usunięcie konta**. Wybrałem go pierwszy nie z przekory: to jedyny
przepływ, który przechodzi przez **oba repozytoria, siedem serwisów, cztery frameworki, siedem
topików Kafki i trzy różne mechanizmy niezawodności**. Kto rozumie ten jeden przepływ, rozumie
90% konstrukcji całego systemu. Wszystko inne (logowanie, wrzucenie mema, komentarze) to prostsze
wersje tych samych chwytów.

**Konwencje w tekście:**
- terminy branżowe podaję po polsku, a w nawiasie po angielsku: *zatrzask jednorazowy (once-latch)*;
- skróty rozwijam przy pierwszym użyciu: *DLQ (Dead Letter Queue — kolejka wiadomości
  niedostarczalnych)*;
- ścieżki plików są względne wobec `/home/robert/Documents/git/` i podaję je z numerem linii,
  żeby dało się zajrzeć: `shared/microservice-security/.../DeleteAccountController.java:42`;
- **fakt** kontra **opinia**: wszystko, co tu piszę, jest odczytane z kodu na dziś. Tam, gdzie
  kod robi coś dziwnego, piszę że robi dziwnie — podręcznik, który upiększa system, jest
  bezużyteczny do nauki.

**Czego tu NIE ma:** pytań sprawdzających (o to poprosiłeś osobno — to inny dokument) i opisów
rzeczy zaplanowanych, ale niezaimplementowanych.

---

## 1. Cała historia w jednym akapicie

Użytkownik klika „usuń konto". Serwis tożsamości (`microservice-security`) **nie usuwa konta** —
najpierw żąda ponownego dowodu tożsamości (bo usunięcie jest nieodwracalne), potem **blokuje konto**
(kasuje wszystkie sesje, wyłącza logowanie) i **ogłasza fakt**: „ten człowiek poprosił o usunięcie".
Fakt jedzie przez Kafkę do portalu. Tam osobny serwis (`microservice-offboarding`) jest
**koordynatorem**: rozsyła trzem serwisom treści (memy, komentarze, kolekcje) rozkaz „wyczyść dane
tego użytkownika", zbiera od nich potwierdzenia i — kiedy zbierze **wszystkie** — ogłasza jeden
werdykt: *wyczyszczone* albo *nie udało się*. Serwis tożsamości słucha tego werdyktu. Przy
„wyczyszczone" usuwa użytkownika **na dobre** i wysyła list pożegnalny. Przy „nie udało się"
(albo przy ciszy dłuższej niż 5 minut) **odblokowuje konto** i wysyła przeprosiny.

To jest **saga** (saga). I to jest cała lekcja: **rozproszona operacja bez wspólnej transakcji**.

---

## 2. Dlaczego to nie może być zwykła transakcja

Gdyby wszystko było w jednej bazie, kod usuwania konta wyglądałby tak:

```java
@Transactional
void deleteAccount(String email) {
    memes.deleteByAuthor(email);
    comments.deleteByAuthor(email);
    collections.deleteByUser(email);
    users.delete(email);
}                                  // commit albo rollback — całość albo nic
```

Jedna linia `@Transactional` daje ci **atomowość** (atomicity): albo wszystko, albo nic. Baza
pilnuje tego za ciebie.

W tym systemie te dane leżą w **pięciu osobnych bazach Postgresa** (security, memes, comments,
collections, offboarding), w pięciu osobnych procesach, na pięciu osobnych maszynach. Nie ma
wspólnej transakcji. Nie ma `rollback`. Nie ma nawet gwarancji, że wszystkie pięć serwisów żyje
w tej samej sekundzie.

Zostają dwa wyjścia:

1. **Rozproszona transakcja dwufazowa** (two-phase commit, 2PC) — koordynator pyta wszystkich
   „możesz?", potem mówi wszystkim „to rób". Działa, ale wymaga, żeby wszyscy uczestnicy
   trzymali blokady w czasie oczekiwania na koordynatora — a jak koordynator umrze między fazami,
   uczestnicy wiszą zablokowani. W mikroserwisach się tego praktycznie nie używa.
2. **Saga** — dzielisz operację na **lokalne transakcje** (każda w swojej bazie, każda normalnie
   atomowa) i dodajesz koordynatora, który wie, kto już zrobił swoje. Jak coś padnie, nie ma
   `rollback` — jest **kompensacja** (compensation): czynność odwracająca skutek, wykonana
   świadomie, jako osobna operacja.

Ten system wybrał sagę. I tu zaczyna się rzecz, którą trzeba zrozumieć, żeby zrozumieć resztę:

> **Kompensacja tego, co nieodwracalne, nie istnieje.** Usunięty mem nie wraca. Dlatego cała
> konstrukcja jest ustawiona tak, żeby **nieodwracalny krok był ostatni** — i to nie tylko
> w kolejności uczestników, ale **w kolejności faz**.

I tu jest zmiana, którą trzeba zrozumieć osobno, bo wywraca intuicję (2026-08-08, ADR 0007).
Uczestnik na komendę czyszczenia **nic nie kasuje**. Ustawia status `PENDING_ERASURE` na swoim
agregacie (mem, komentarz) i to wszystko. Treść w tej samej chwili znika ze świata — z galerii,
z wyszukiwarki tagów, z rankingu, z wątku, z każdego URL-a obrazka — bo **wszystkie** odczyty idą
przez widok `active_memes` / `active_comments`, który statusu innego niż `ACTIVE` nie widzi. Ale
wiersz stoi, blob stoi, głosy stoją. Potwierdzenie, które zbiera orkiestrator, znaczy więc
**„zarezerwowane"**, a nie „zniszczone" — a rezerwację da się oddać.

Z tego wynikają trzy rzeczy:

- **Domknięcie kasuje, nie komenda.** Gdy potwierdzą wszyscy uczestnicy — czyli gdy sprawa nie
  może już się nie udać — orkiestrator wysyła `ERASE_USER_CONTENT` i dopiero to niszczy.
- **Kompensacja to `RESTORE_USER_CONTENT`**: statusy wracają na `ACTIVE`, użytkownik dostaje konto
  **razem z treściami**. Przedtem kompensacja oddawała konto puste i przepraszała za usunięcie,
  które w istocie się odbyło.
- **Nic nie kasuje z upływu czasu.** Uczestnik nie ma własnego timeoutu na oznaczeniu: saga stojąca
  godzinę, bo padł sąsiad, to saga, która wciąż może kompensować. Czas kupuje wyłącznie **alarm**
  (`StuckErasureWatch`: „ukryte, ale NIE wymazane, i nic tego nie skasuje samo").

**Pivot** — punkt bez powrotu — leży w jednym miejscu: `PurgeUserContent` w memes, w chwili gdy
obrazek opuszcza MinIO/S3. Za nim saga nie ma kompensacji, ma tylko **ponawianie**.

Zapamiętaj to, bo z tego wynika każdy timeout w rozdziale 10 i cała katastrofa z rozdziału 12 —
tyle że po tej zmianie katastrofa z §12.1 kosztuje treść tylko wtedy, gdy zdążyła przekroczyć
pivot.

---

## 3. Kto jest kim (obsada)

```
   PRZEGLĄDARKA
   security-ui / memes-ui
        │  POST /account/step-up      (dowiedź, że to ty)
        │  POST /account/delete       (zamknij konto)
        ▼
┌──────────────────────────┐
│  microservice-security   │  TOŻSAMOŚĆ (identity). Micronaut.
│  shared/                 │  Blokuje konto, ogłasza fakt, czeka na werdykt,
│                          │  usuwa użytkownika albo cofa blokadę.
└──────────────────────────┘
        │ fakt: ACCOUNT_DELETION_REQUESTED
        │ topik: security-events
        ▼
┌──────────────────────────┐
│ microservice-offboarding │  KOORDYNATOR (process manager). Helidon 4 SE.
│  portal/                 │  Nie ma własnych danych użytkownika. Tylko pilnuje,
│                          │  kto potwierdził, i ogłasza jeden werdykt.
└──────────────────────────┘
        │ rozkaz: PURGE_USER_CONTENT          ▲ potwierdzenia: USER_CONTENT_PURGED
        │ topik: content-commands             │ topiki: memes-events, comments-events,
        ▼                                    │         usercollections-events
┌───────────────┬────────────────┬───────────────────────┐
│    memes      │    comments    │   user-collections    │  UCZESTNICY (participants)
│ Spring Boot   │  Spring Boot   │    Helidon 4 SE       │  Każdy czyści SWOJE dane
└───────────────┴────────────────┴───────────────────────┘  i potwierdza.
        │
        │ werdykt: PORTAL_CONTENT_PURGED albo PORTAL_PURGE_FAILED
        │ topik: offboarding-events
        ▼
   z powrotem do microservice-security
        │ żądanie maila (topik mail-requests, przez ten sam outbox)
        ▼
   microservice-email  →  SMTP (w dev: Mailpit)
```

Trzy role, które trzeba nazywać precyzyjnie:

| Rola | Kto | Czym się różni |
|---|---|---|
| **Tożsamość** (identity) | `microservice-security` | Zna użytkownika i sesje. **Nie wie nic** o memach ani komentarzach. |
| **Koordynator** (process manager) | `microservice-offboarding` | Zna **przebieg procesu**, nie dane. Nie ma nawet warstwy domenowej. |
| **Uczestnik** (participant) | memes, comments, user-collections | Wykonawca jednej osi danych. Nie decyduje o niczym poza swoją bazą. |

**Dlaczego tożsamość i koordynator są rozdzielone** — to najważniejsza decyzja projektowa w tym
przepływie. `microservice-security` jest wspólny dla **dwóch produktów** (portal memów i gra F1).
Gdyby koordynator sagi siedział w tożsamości, tożsamość musiałaby znać listę serwisów portalu.
Kod pokazuje, że tak kiedyś było i że to wycięto: migracja `V17__saga_extracted_to_offboarding.sql`
usuwa z bazy security kolumny `memes_purged`, `comments_purged`, `collections_purged`, a jej
nagłówek mówi wprost: *„identity no longer tracks per-participant confirmations — it announces the
deletion fact and waits for the portal's single outcome"*.

Ten sam motyw wraca w klasie `PurgeChoices` (rozdział 5) i w liście uczestników (rozdział 7).
Nazywa się to **neutralnością wobec produktów** i jest sprawdzalne: w całej warstwie domenowej
serwisu security nie ma **ani jednego** typu, pola czy metody zawierającej słowo „mem",
„komentarz" czy „kolekcja".

---

## 4. Akt I — przeglądarka i **step-up**

Usunięcie konta jest w interfejsie w dwóch miejscach, z różnym zakresem:

| | `security-ui` | `memes-ui` |
|---|---|---|
| stos | React 19, bez biblioteki komponentów | React 19 + MUI 9 |
| co wysyła | `POST /account/delete` **bez ciała** | `POST /account/delete` z ciałem `{purge:{...}}` |
| wybór losu treści | brak — decyduje polityka serwera | kreator: usuń / wymaż wszystko / zostaw popularne |
| po sukcesie | `signOut()` + „Account closing — you are signed out everywhere." | notka „…the goodbye mail will confirm" + wyczyszczenie `localStorage` |

Oba przechodzą przez **step-up** (step-up authentication — *podniesienie uprawnień sesji*).

**Co to jest step-up.** Zwykłe zalogowanie mówi „ten token należy do tego konta". Step-up mówi
„osoba trzymająca ten token **właśnie teraz** dowiodła, że jest właścicielem". To dwie różne
rzeczy: token można ukraść (z `localStorage`, z niezablokowanego laptopa), a świeży dowód —
hasło plus kolejne czynniki — trudniej. Komentarz w kontrolerze mówi to jednym zdaniem:

> *„deleting an account is irreversible: a live session is not enough, the caller must have just
> stepped up (the thief of a live session would have to pass the chain too)"*
> — `shared/microservice-security/security-infrastructure/.../DeleteAccountController.java:43`

> ⚠️ **To zdanie z kodu jest NIEPRAWDZIWE — przegląd P18 (2026-07-30) to udowodnił, a ja
> potwierdziłem ręcznie.** Elewacja step-upu nie jest wiązana z akcją, więc złodziej żywej sesji
> **nie musi** przejść łańcucha skonfigurowanego dla `delete-account`. Czytaj ten rozdział jako
> opis **zamiaru** projektowego, nie stanu faktycznego — szczegóły w §20 (Errata) i w
> `portal/PLAN-P18.md` poz. 1.

**Jak to działa mechanicznie** (trzy klasy, warto je zapamiętać razem):

1. `POST /account/step-up` z `{action:"delete-account", password:"…"}` → `StepUp.start(...)`.
   Polityka dla akcji `delete-account` to domyślnie **`FULL_CHAIN`**: najpierw hasło (pomijane dla
   konta bezhasłowego/federacyjnego), potem **cały łańcuch czynników MFA** (MFA — Multi-Factor
   Authentication, uwierzytelnianie wieloczynnikowe). Odpowiedzi: `200 ELEVATED` (gotowe) albo
   `202 FACTOR_REQUIRED` z jednorazowym biletem `stepUpTicket`.
2. `POST /account/step-up/factor` z `{stepUpTicket, proof}` → kolejne ogniwo łańcucha, aż do
   `200 ELEVATED`. Sukces to `SessionElevation.elevate(accessToken)` — postawienie **krótkiego,
   jednorazowego znacznika** na access tokenie (mapa w pamięci, czas życia domyślnie 5 minut).
3. `POST /account/delete` → `StepUpGuard.requireElevation(request, "delete-account")`. Bramka
   przechodzi **tylko** gdy `elevation.consume(token)` zwróci `true`. `consume` **usuwa** wpis —
   znacznik jest jednorazowy. Bez niego: `403 {"status":"STEP_UP_REQUIRED","action":"delete-account"}`.

**Trzy rzeczy, które w tym miejscu zaskakują** (i dobrze o nich wiedzieć, zanim ktoś spyta):

- `StepUp.submitFactor` **nie sprawdza wygaśnięcia biletu** — klasa `StepUp` nie ma nawet pola
  `Clock`. Bliźniacza `ContinueAuthentication` (zwykłe logowanie) sprawdza. Bilet step-upu żyje
  do restartu procesu.
- Podniesienie jest konsumowane **przed** wejściem w transakcję (linia 45 kontrolera, transakcja
  w 51). Jeśli transakcja padnie, `rollback` cofnie blokadę konta, ale **nie odda podniesienia** —
  mapa w pamięci nie jest transakcyjna. Użytkownik musi przejść step-up od nowa.
- Step-up **nie jest chroniony przed zgadywaniem hasła**: nie zapisuje nieudanych prób w licznikach
  brute-force logowania i nie ma throttlingu (`SourceThrottle` jest wstrzykiwany tylko do
  rejestracji, resetu hasła i weryfikacji adresu).

---

## 5. Akt II — jedno żądanie HTTP w serwisie tożsamości

To najgęstsze 20 linii kodu w całym przepływie. Warto je znać na pamięć.

### 5.1 Kontrakt

```
POST /account/delete
Authorization: Bearer <accessToken>
Body (opcjonalne):  {"purge": {"memes": "DELETE", "comments": "KEEP_POPULAR_ANONYMIZED:50"}}

202 Accepted  {"status":"ACCOUNT_DELETION_STARTED"}
403           {"status":"STEP_UP_REQUIRED","action":"delete-account"}
403           {"error":"MFA_ENROLMENT_REQUIRED"}     (konto nie spełnia progu czynników)
401                                                  (brak lub odrzucony token)
```

**Kod 202, nie 200 ani 204** — i to jest cała semantyka tego endpointa. `202 Accepted` znaczy
„przyjąłem żądanie, wynik nie jest jeszcze znany". Bo faktycznie nie jest: o usunięciu zadecyduje
portal, kilka sekund (albo minut) później. Endpoint, który zwracałby `200 OK`, kłamałby.

### 5.2 Łańcuch wywołań, krok po kroku

```
CorrelationIdFilter        nadaje/przepisuje X-Correlation-Id → atrybut "cid" + MDC
AuthorizationFilter        Bearer → Authorize.execute → atrybut "authenticatedEmail"; potem próg MFA
DeleteAccountController    StepUpGuard.requireElevation(...)      ← bramka z rozdziału 4
                           PurgeChoices z ciała żądania
                           transactionBoundary.execute( … )       ← JEDNA transakcja stąd
  StartAccountDeletion       1. authorizationDataRepository.revokeAllSessions(email)
                             2. userRepository.markPendingDeletion(email)
                             3. saga.begin(email, purgeChoices)
    AccountDeletionOrchestrator    sagas.start(sagaId, email, now)    ← wiersz sagi STARTED
                                   outbox.append("security-events", email, fakt)
                           commit                                 ← …dotąd
                           202 ACCOUNT_DELETION_STARTED
```

### 5.3 Trzy kroki, w tej kolejności, i dlaczego

`StartAccountDeletion.execute` ma dokładnie trzy linie
(`shared/microservice-security/security-system/.../account/StartAccountDeletion.java:29-33`):

```java
authorizationDataRepository.revokeAllSessions(email);   // 1. wyloguj wszędzie
userRepository.markPendingDeletion(email);              // 2. zablokuj konto
saga.begin(email, purgeChoices);                        // 3. ogłoś fakt
```

**Krok 1** kasuje sesje **fizycznie** (`DELETE FROM sessions`), łącznie z tą, z której użytkownik
właśnie kliknął. Token, którym przyszło żądanie, po commicie nie autoryzuje — plik specyfikacji
sprawdza to wprost („the access token no longer authorizes").

**Krok 2** stawia flagę `users.pending_deletion = true`. To **blokada na czas sagi**: konto
istnieje, ale nie wpuszcza. Logowanie hasłem odrzuca je w kroku `_VerifyCredentials`, filtrem
`.filter(user -> !userRepository.isPendingDeletion(email))` — i konto zablokowane sagą
**zachowuje się dokładnie jak złe hasło** (klient nie dowiaduje się, że konto jest zamykane).
Logowanie przez dostawcę zewnętrznego (OAuth) jest szczersze: zwraca `Refused("ACCOUNT_CLOSING")`.

**Krok 3** dopisuje **wiersz do tabeli** `outbox_events` — nie wysyła nic do Kafki. To sedno
następnego rozdziału.

**Dlaczego ta kolejność jest ważna:** blokada musi wejść **przed** ogłoszeniem faktu. Gdyby fakt
poszedł pierwszy, portal mógłby zacząć czyścić treści konta, na które ktoś jeszcze się loguje
i dodaje nowe memy — czyściłbyś ruchomy cel.

### 5.4 `PurgeChoices` — granica słowników

```java
public record PurgeChoices(Map<String, String> rules) {
    public PurgeChoices { rules = Map.copyOf(rules); }
    public static PurgeChoices serviceDefaults() { return new PurgeChoices(Map.of()); }
}
```

Cały wybór użytkownika („co zrobić z moimi memami") jest w tożsamości reprezentowany jako
**nieprzejrzysta mapa** (opaque map) `String → String`. Security **nie waliduje ani nazw osi, ani
reguł**. Nie zna słowa `DELETE`. Nie wie, że istnieje oś `memes`.

To celowe i jest to jeden z tych fragmentów, gdzie javadoc opisuje **własny błąd z przeszłości**:

> *„Both the axis NAMES (memes, comments, …) and the rules are opaque here on purpose — their
> vocabulary belongs to the content services and their orchestrator; identity only carries the map.
> (This used to name the portal's axes as fields — foreign domain inside an identity value object,
> and the reason the saga was extracted.)"*

Pusta mapa (`serviceDefaults()`) znaczy „każdy serwis treści robi to, co ma w swoim domyślnym
ustawieniu" — i **nie jest awarią**. To ważne rozróżnienie: „nie wybrał" ≠ „coś się zepsuło".

### 5.5 Granica transakcji jako wstrzykiwany interfejs

Kontroler nie ma adnotacji `@Transactional`. Ma wstrzyknięty `TransactionBoundary` z jedną metodą
`<T> T execute(Supplier<T> work)` i dwiema implementacjami:

| Implementacja | Warunek Micronauta | Co robi |
|---|---|---|
| `TransactionalBoundary` | `@Requires(beans = DataSource.class)` | metoda z `@Transactional` |
| `NoTransactionBoundary` | `@Requires(missingBeans = DataSource.class)` | po prostu `work.get()` |

**Dlaczego tak, a nie adnotacja na kontrolerze:** żeby ścieżka bez bazy (testy in-memory)
nie wciągała menedżera transakcji, który bez `DataSource` nie ma czego zarządzać.

I to jest **wzorzec przełączania implementacji w całym tym serwisie**, warto go rozpoznawać:
**nie ma profili** typu springowe `@Profile("dev")`. Jest **warunek na obecność beana**:
`@Requires(beans = DataSource.class)` kontra `@Requires(missingBeans = DataSource.class)`.
Jest `DataSource` → wersja JDBC. Nie ma → wersja pamięciowa. Dotyczy to pary sag, outboxu,
repozytorium użytkowników, sesji i granicy transakcji.

Z tym idzie jedna pułapka, którą javadoc sam nazywa: wersja pamięciowa **jest produkcyjnym
okablowaniem** wszędzie tam, gdzie nie skonfigurowano bazy — *„not merely a test double"*.

---

## 6. Wzorzec pierwszy: **transactional outbox** (skrzynka nadawcza w transakcji)

To najważniejszy wzorzec w tym systemie i pojawia się w tym przepływie **czterokrotnie**
(w security, w memes, w comments, oraz w formie uproszczonej w offboardingu).

### 6.1 Problem, który rozwiązuje (problem podwójnego zapisu)

Chcesz zrobić dwie rzeczy jednocześnie: **zmienić dane w bazie** i **ogłosić to na Kafce**.
Nie da się ich objąć jedną transakcją, bo baza i broker to dwa różne systemy. Zostają dwie
kolejności i **obie są zepsute**:

```
A) najpierw baza, potem Kafka:      commit ✓ … proces pada … zdarzenie NIGDY nie wychodzi
                                    → dane zmienione, świat nie wie
B) najpierw Kafka, potem baza:      send ✓  … transakcja rollback …
                                    → świat wie o czymś, co się nie stało
```

W tym przepływie wariant A ma konkretną, opisaną w kodzie konsekwencję. Zanim memes dostały outbox,
potwierdzenie purge było zwykłym `kafka.send(...)` bez czekania. Spring Kafka zatwierdza offset
zaraz po powrocie metody listenera, więc osiągalna była sekwencja:

> *„content deleted, offset committed, confirmation lost in the producer's accumulator"*

Czyli: **memy skasowane**, potwierdzenie zgubione w buforze producenta, koordynator go nie słyszy,
po trzech ponowieniach kapituluje — a użytkownik dostaje **konto z powrotem, bez swoich treści**,
plus mail z przeprosinami. Javadoc nazywa to *„the worst outcome this system has"*. Trudno się
nie zgodzić.

### 6.2 Rozwiązanie

Zdarzenie **nie jest wysyłane** w trakcie transakcji biznesowej. Jest **zapisywane jako wiersz**
w tabeli outboxu — w **tej samej transakcji** co zmiana danych:

```
BEGIN
  UPDATE users SET pending_deletion = true …          ← zmiana biznesowa
  DELETE FROM sessions WHERE …                        ← zmiana biznesowa
  INSERT INTO account_deletion_sagas (…, 'STARTED')   ← zmiana biznesowa
  INSERT INTO outbox_events (topic, key, payload, …)  ← ZAMIAR wysyłki
COMMIT
```

Po commicie **osobny proces** (poller / republisher) czyta wiersze bez znacznika publikacji,
wysyła je do brokera i dopiero po potwierdzeniu stempluje `published_at`.

Co z tego wynika:
- `rollback` zabiera zdarzenie razem ze zmianą — nie ma ogłoszeń o czymś, co się nie stało;
- awaria między commitem a wysyłką **nic nie gubi** — wiersz czeka i zostanie dosłany;
- ceną jest **at-least-once** (co najmniej raz): awaria między wysyłką a stemplem powoduje
  **duplikat**. Nie da się tego uniknąć: „stempluj przed wysyłką" zamieniałoby każdą awarię
  w ciche zgubienie zdarzenia. Dlatego **każdy odbiorca w tym systemie musi być idempotentny**
  (rozdział 11).

### 6.3 Dwie różne implementacje w jednym systemie — i to nie jest bałagan

| | `microservice-security` | `microservice-memes`, `microservice-comments` |
|---|---|---|
| skąd | własny kod w pakiecie `persistence` | **wspólna biblioteka** `../shared/transactional-outbox` + `../shared/infrastructure-spring-outbox` |
| poller | `@Scheduled(fixedDelay="1s")`, `OutboxPublisher.drain()` | `@Scheduled` co 15 s, `ScheduledOutboxRepublisher` |
| kolejność | `ORDER BY created_at`, **stop przy pierwszym błędzie** (`return`) | `ORDER BY created_at, id`, porcje po 500 wierszy |
| ponowienia | brak licznika, brak backoffu | rozdzielone: **timeout** (broker milczał) → płaskie +30 s **bez** naliczania próby; **odmowa** (broker odpowiedział „nie") → `attempts+1`, backoff wykładniczy 30 s → 1 h |
| „zatrucie" | brak pojęcia | po 25 nieudanych próbach wiersz **przestaje być wybierany**, zostaje w tabeli z `ERROR` w logu |
| retencja | **brak** — nic nigdy nie kasuje opublikowanych wierszy | `DELETE` po oknie retencji, w porcjach, **przed** re-sendem |

Uwaga na jedno źródło pomyłek: `microservice-security` **nie używa** wspólnej biblioteki. Adaptera
Micronauta dla niej po prostu nie ma (choć analogiczny podział istnieje dla zegara:
`adjustable-clock` + `infrastructure-micronaut-clock`). Security ma outbox **starszy i prostszy**.

**Czego uczy podział biblioteki** — to ładny przykład na rozmowę o projektowaniu. Rdzeń
(`transactional-outbox`) zależy **tylko** od `java.sql` i `slf4j`: nie ma klienta Kafki (wysyłka
to interfejs `Dispatch`), nie ma Jacksona (payload to nieprzejrzysty `String`). Metoda
`append(Connection, OutboxEvent)` bierze **surowe** `java.sql.Connection`, bo trzej konsumenci
siedzą na trzech frameworkach, których menedżery transakcji *„nie zgadzają się w niczym poza tym,
że kończą na połączeniu JDBC"*. Adapter springowy dokłada tylko trzy rzeczy: dołączenie do
ambientnej transakcji (`DataSourceUtils`), odroczenie pierwszej wysyłki na *po commicie*
i `@Scheduled`.

Jeden szczegół z tej biblioteki wart osobnej uwagi, bo to zmierzony błąd i jego naprawa:
potwierdzenie od brokera przychodzi na wątku I/O producenta, ale **nie wykonuje tam zapisu do
bazy** — wrzuca identyfikator do kolejki, a stempel zakłada wątek schedulera. Wcześniej stempel
leciał w callbacku i przy wyczerpanej puli połączeń zamrażał całe I/O Kafki serwisu na 30 sekund.
Komentarz: *„A database problem was promoted into a Kafka availability problem, with a feedback
loop, at exactly the moment the database was already suffering."*

### 6.4 Czego outbox **nie** daje

- **Nie daje kolejności.** Kolejność per klucz to *„silna tendencja, nie gwarancja"*: jeśli
  pierwsza próba E1 padnie, a E2 przejdzie, konsument zobaczy E2 przed E1.
- **Nie daje exactly-once.** Świadomie.
- **Nie daje pojedynczego nadawcy.** Poller nie ma `FOR UPDATE SKIP LOCKED` ani leasingu —
  dwie replice serwisu **wysłałyby podwójnie**. Dlatego wdrożenie security w k8s ma
  `strategy: Recreate` (ubij stary pod, potem wstaw nowy), a nie `RollingUpdate`.
  Uzasadnienie: *„holding a database transaction across broker I/O is worse than a duplicate"*.
- **Nie ma DLQ** (DLQ — Dead Letter Queue, kolejka wiadomości niedostarczalnych). Zatruty wiersz
  zostaje w tabeli jako nieopublikowany, a jedynym „odbiorcą" jest człowiek, który znajdzie go
  po identyfikatorze z loga. Log **celowo nie zawiera payloadu**, bo payload potwierdzenia
  `USER_CONTENT_PURGED` niesie adres e-mail osoby, której dane właśnie się wymazuje.

---

## 7. Akt III — koordynator otwiera sagę

Fakt na topiku `security-events` wygląda tak:

```json
{ "id": "…uuid…", "sagaId": "…uuid…", "type": "ACCOUNT_DELETION_REQUESTED",
  "email": "kto@example.com", "version": 1, "policy": { "memes": "DELETE" } }
```

Dwie rzeczy w nazwie typu są nieprzypadkowe: to **fakt** (`…_REQUESTED`), nie rozkaz. Security nie
mówi portalowi „usuń treści" — mówi „stało się to i to". Co portal z tym zrobi, jest sprawą
portalu. To różnica **zdarzenie kontra komenda** (event vs command) i tutaj widać ją w praktyce:
gdyby portal nie istniał, fakt po prostu nikogo by nie zainteresował.

Pole `version: 1` to **wersjonowana koperta** (versioned envelope) z ADR 0004 — patrz rozdział 15.

### 7.1 `microservice-offboarding` — heksagon bez domeny

Ten serwis jest ciekawy sam z siebie, bo jest **inny niż wszystkie pozostałe**:

- **Brak frameworka wstrzykiwania zależności.** Helidon 4 SE, graf obiektów składany ręcznie
  w statycznym `main()`. Zero adnotacji `@Inject`.
- **Brak pliku konfiguracyjnego.** W `src/main/resources` są tylko `logback.xml` i migracje
  Flyway. Cała konfiguracja to **zmienne środowiskowe**, walidowane zakresowo przy starcie —
  zła wartość **odmawia startu** z komunikatem nazywającym zmienną.
- **Brak warstwy domenowej.** Są tylko `application` (4 pliki) i `infrastructure` (7 plików).
  I to jest **decyzja, nie zaniedbanie**, opisana w `shared/docs/onboarding-guide.md:159`:
  *„a hexagon WITHOUT a domain — the saga is a PROCESS, not a model"*. Uwaga na rozmowę:
  ta decyzja **nie ma swojego ADR-a** (jest ich sześć, 0001–0006, żaden o tym nie mówi) — żyje
  w jednym wierszu tabeli w przewodniku i w README serwisu.
- **Dwa wątki wirtualne** (virtual threads, Loom), oba demony: `offboarding-consumer` (pętla Kafki)
  i `offboarding-sweeper` (zamiatacz, co 15 s).

Cztery pliki warstwy `application` to cały „mózg": `BeginOffboarding`, `RecordConfirmation`,
`SweepOverdue` i port `SagaStore`. Każdy jest cienki — po kilkadziesiąt linii — bo **cała logika
przejść stanów siedzi w SQL-u** adaptera `JdbcSagaStore`. To nietypowe i trzeba to umieć obronić:
warunek kompletności sagi to `containsAll(required)` w adapterze, a niezmienniki „jedna saga
w biegu na konto" i „jeden fakt to jedna saga" są wymuszane **ograniczeniami bazy danych**,
nie obiektem w Javie.

### 7.2 Co się zapisuje przy starcie sagi

`INSERT` wstawia osiem kolumn, ale **przed** nim są dwa odczyty — i to one są tu istotne:

```
1. sagaOfFact(fact_id)   → czy ten fakt już otworzył sagę?  jeśli tak: zwróć ją (choćby zamkniętą)
2. runningSaga(email)    → czy dla tego konta trwa już saga? jeśli tak: nowy fakt DOŁĄCZA do niej
3. dopiero teraz INSERT nowego wiersza STARTED
```

Trzy niezależne mechanizmy korelacji — warto je rozróżniać, bo brzmią podobnie:

| Mechanizm | Co gwarantuje | Jak |
|---|---|---|
| `fact_id UUID UNIQUE` | ten sam fakt **dostarczony drugi raz** trafi w swoją starą sagę, a nie rozwidli nowej | ograniczenie `UNIQUE` (V1) |
| `running_email UNIQUE` (nullable) | **jedna saga w biegu na jedno konto** | kolumna niesie adres, dopóki stan to `STARTED`, i idzie na `NULL` w tym samym `UPDATE`, który zmienia stan (V2) |
| `sagaId` odbijane w potwierdzeniu | precyzyjny adres, żeby echo z zamkniętej sprawy nie wylądowało na nowszej sadze | pole w komunikacie, pinowane w paktach |

Sztuczka z `running_email` jest elegancka i warto ją rozumieć: chcemy indeksu unikalnego
„tylko dla wierszy w stanie STARTED". W Postgresie zrobiłbyś indeks częściowy
(`UNIQUE … WHERE state = 'STARTED'`). Ale testy chodzą na H2 w trybie PostgreSQL, a H2 indeksów
częściowych nie ma. Więc niezmiennik przeniesiono **do nullowalnej kolumny**: `NULL`-e nie kolidują
w `UNIQUE` w żadnej z tych baz. Komentarz migracji V2 tłumaczy, po co: *„one running saga per
email was only ever an application-level read-then-insert — two facts racing for the same account
could fork two sagas"*.

A skoro dwa fakty mogą się wyścigować: `start()` jest owinięty w pętlę na **dwie próby**. Przegrany
wyścig kończy się `SQLState 23505` (naruszenie unikalności), pętla kręci się raz jeszcze i przegrany
**adoptuje sagę zwycięzcy**. Kod `23505` jest w tym adapterze traktowany jak normalna informacja,
nie jak awaria — przy potwierdzeniu znaczy „duplikat, ignoruj", przy starcie „przegrałem wyścig,
przyjmuję cudzą sagę".

### 7.3 Lista uczestników jest **konfiguracją**, nie kodem

```
OFFBOARDING_PARTICIPANTS=memes=memes-events,comments=comments-events,collections=usercollections-events
```

Pary `nazwa=topik-potwierdzeń`. Z tego powstaje: lista topików do subskrypcji (klucze) i **zbiór
wymaganych uczestników** (wartości). Nowy serwis treści dołącza do sagi **wpisem w zmiennej
środowiskowej** — bez zmiany linijki w security i bez migracji bazy. Komentarz w compose:
*„Participants are CONFIGURATION — a new content service joins the saga right here, without
touching a line of security."*

To bezpośrednia **lekcja z poprzedniej wersji**: security miało kolumny `memes_purged`,
`comments_purged`, `collections_purged`, więc dodanie czwartego uczestnika wymagało migracji
w serwisie tożsamości. Migracja V1 offboardingu mówi to wprost: *„the required set is
CONFIGURATION, not columns — the lesson of security's memes_purged/comments_purged/…"*.

Parsowanie tej zmiennej **odmawia startu** przy duplikacie topiku **lub** duplikacie nazwy —
z uzasadnieniem, które warto przeczytać, bo pokazuje, jak myśleć o walidacji konfiguracji:

> *„It then announces PORTAL_CONTENT_PURGED on an incomplete quorum, security deletes the account
> for good — and the participant nobody waited for has no timeout and no compensation left…
> Data gone and the verdict false is the one outcome this service exists to prevent; a typo in one
> env var must not be able to buy it."*

Czyli: literówka `memes=…,memes=…` skurczyłaby zbiór wymaganych z trzech do dwóch, saga ogłosiłaby
sukces po dwóch potwierdzeniach, security usunęłoby konto — a trzeci uczestnik nigdy nie dostałby
rozkazu. Dlatego serwis **wolał nie wstać**.

**Pułapka, o której trzeba wiedzieć:** zbiór wymaganych uczestników **nie jest zapisywany z sagą** —
jest czytany z konfiguracji przy **każdym** potwierdzeniu. Zmiana zmiennej i restart zmienia więc
kryterium kompletności dla sag **już otwartych**: skrócenie listy może domknąć trwającą sagę na
następnym potwierdzeniu, wydłużenie każe jej czekać na uczestnika, który nigdy nie dostał rozkazu.
Nic w kodzie ani w testach tego nie wykrywa.

---

## 8. Akt IV — trzech uczestników, trzy różne odpowiedzi

Rozkaz na topiku `content-commands`:

```json
{ "id": "…", "sagaId": "…", "type": "PURGE_USER_CONTENT",
  "email": "kto@example.com", "version": 1, "policy": { "memes": "DELETE" } }
```

Tu jest **rozkaz** (command), nie fakt — koordynator mówi wprost, co ma się stać. Wszyscy trzej
uczestnicy czytają **ten sam topik**, każdy w swojej grupie konsumenckiej (`memes`, `comments`,
`user-collections`), więc każdy dostaje własną kopię.

I teraz najciekawsza część całego rozdziału: **trzy serwisy robią to samo zadanie na trzy różne
sposoby, i każda różnica jest udokumentowana**.

| | **memes** | **comments** | **user-collections** |
|---|---|---|---|
| stos | Spring Boot 4.1, `@KafkaListener` | Spring Boot 4.1, `@KafkaListener` | Helidon 4 SE, **ręczna pętla** na `kafka-clients` |
| polityka | `DELETE` / `ANONYMIZE_AUTHOR` / `KEEP_POPULAR_ANONYMIZED:N` | to samo | **żadnej** — czyści hurtowo |
| domyślnie | `DELETE` | `ANONYMIZE_AUTHOR` | — |
| potwierdzenie | **outbox** (biblioteka wspólna) | **outbox** (biblioteka wspólna) | `producer.send(...).get()` **przed** commitem offsetu |
| przy błędzie | budżet **90 s**, potem **głośno porzuca** rekord | budżet **90 s**, potem **głośno porzuca** | **ponawia w nieskończoność**, nie commituje offsetu |
| DLQ | brak | brak | brak (świadomie: *„No dead-letter queue — on purpose"*) |

### 8.1 memes — decyzja per mem

Reguła jest rozstrzygana w kolejności: **wybór użytkownika z komendy → nadpisanie admina
w tabeli `settings` → domyślna z konfiguracji wdrożenia**. Potem pętla po memach autora:

```
score = voteRepository.scoreOf(memeId)
jeśli rule.keeps(score):     reassignAuthor(memeId, "deleted account")   ← mem ZOSTAJE
inaczej:                     purgeMeme (głosy) + contentIndex.remove
                             + removeMeme (tagi) + deleteById (wiersz + blob)
                             + memeEvents.memeDeleted(...)               ← kaskada
na koniec, ZAWSZE:           voteRepository.purgeVoter(email)            ← głosy ODDANE przez
                                                                            odchodzącego
```

`keeps()`: `DELETE` → nigdy nie zostawia; `ANONYMIZE_AUTHOR` → zostawia zawsze;
`KEEP_POPULAR_ANONYMIZED:N` → zostawia, jeśli `score >= N`.

Trzy szczegóły, które łatwo przeoczyć:
- **Anonimizacja nie dotyka obrazka ani tagów.** Zmienia się **wyłącznie kolumna `author`**.
  Wpis w `content_index` (indeks deduplikacji uploadów po SHA-256) też zostaje, więc nikt nie
  wgra tego samego obrazka ponownie.
- **Głosy oddane przez odchodzącego znikają zawsze**, niezależnie od reguły. To osobna decyzja
  od losu jego treści.
- **Bajty obrazów** żyją w porcie `ObjectStore` z trzema adapterami (baza / system plików / S3).
  Przy adapterze bazodanowym kasowanie idzie **w transakcji** purge. Przy plikach i S3 fizyczne
  usunięcie jest **zaparkowane na po commicie** — bo *„Deleting their object eagerly inside that
  transaction would be wrong in the rollback case: the meme row comes back, its bytes do not"*.
  Cena: przy plikach/S3 usunięcie po commicie jest **best-effort**; jego porażka daje tylko
  ostrzeżenie w logu, a plik zostaje osierocony.

### 8.2 comments — anonimizacja jako domyślna

Domyślna reguła jest **inna niż w memach** (`ANONYMIZE_AUTHOR`, nie `DELETE`) i to nie przypadek:
komentarz jest częścią **cudzej rozmowy**. Usunięcie komentarzy odchodzącego zrobiłoby dziury
w wątkach ludzi, którzy nie odchodzą. Mem to treść własna — tam domyślne `DELETE` broni się
tym, że obraz **sam bywa daną osobową**, a maszyna nie oceni jego zawartości.

Anonimizacja tutaj to również wyłącznie kolumna `author` → `"deleted account"`. **Treść
komentarza zostaje bez zmian** i nikt jej nie przeszukuje. Jeśli użytkownik podpisał się
w tekście („– Robert G."), tekst zostaje. To realne ograniczenie tej anonimizacji, warto je
znać, zanim ktoś o nie zapyta.

### 8.3 user-collections — hurtem, bo referencje są nieprzejrzyste

Żadnej reguły — javadoc mówi wprost: *„there is no per-item policy to honour, because the refs are
opaque"*. Kolekcja trzyma **referencje** do cudzych treści, nie treść. Nie ma czego anonimizować:
albo lista jest, albo jej nie ma.

Ten uczestnik był **ostatnim, który nauczył się kompensacji**, i przez chwilę było to zapisane jako
jawny dług w ADR 0007: memy i komentarze wracały po kapitulacji sagi, a lista ulubionych nie — bo tu
`DELETE` szedł już na pierwszą komendę. Warto zapamiętać dlaczego to była najgorsza z trzech strat:
**prywatnej listy nikt poza właścicielem nie widzi**, więc nikt nie zgłosiłby, że zniknęła.

Dziś jest jak u bliźniaków: `PURGE_USER_CONTENT` oznacza (`status = PENDING_ERASURE`), listy
natychmiast wyglądają na puste (widok `active_collection_items`), `RESTORE_USER_CONTENT` cofa,
`ERASE_USER_CONTENT` kasuje. **Jedna świadoma różnica:** domknięcie to nadal jedno zdanie SQL —
`DELETE FROM collection_items WHERE user_email = ? AND status = 'PENDING_ERASURE'` — a nie pętla po
agregatach, bo skoro nie ma reguły, to nie ma decyzji per wiersz. Warunek statusu w `WHERE` jest tu
całą precyzją: to, co użytkownik zapisał PO oznaczeniu, nie należało do tej sagi i domknięcia nie
dotyczy.

Przy okazji z portu **zniknęło `purgeUser(user)`** — nie zostało jako nieużywane. Hurtowe „skasuj
wszystko tego użytkownika" jest dokładnie tą operacją, przez którą ten uczestnik był
nieodwracalny; zostawienie jej w zasięgu ręki zaprasza następnego wołającego do ominięcia sagi.

### 8.4 Dwie **przeciwne** polityki ponowień — i to jest najlepszy materiał na rozmowę

Ten sam rozkaz, dwa serwisy, **dokładnie odwrotne** decyzje. Oba mają uzasadnienie i oba
uzasadnienia są dobre.

**memes i comments: budżet 90 sekund, potem porzuć.** `SagaRetryBudget` liczy **czas**, nie liczbę
prób (deadline 90 s od pierwszej porażki, mierzony `System.nanoTime()`, pauzy 1→2→4→8→15…15 s).
Po wyczerpaniu: rekord porzucony, offset zatwierdzony, `ERROR` w logu i licznik metryki
`records_dropped_total`, o którym javadoc mówi: *„one increment means one account deletion that
this service did not finish, and the saga is about to compensate"*.

Dlaczego skończony? Bo **wieczne ponawianie może wyczyścić konto, które saga już oddała
właścicielowi**. Koordynator kapituluje po ~165 s. Uczestnik, który ponawia w nieskończoność,
dokończy purge **po** kompensacji — użytkownik ma konto z powrotem i traci treści.

**user-collections: ponawiaj wiecznie, nie commituj offsetu.** Uzasadnienie:
*„the saga must not lose a purge, and the eternal retry is not silent — the cycle marker stops
advancing and /health (readiness) turns 503"*.

**Rozstrzygnięcie napięcia:** javadoc `SagaRetryBudget` w comments **wprost krytykuje** podejście
siostrzanego serwisu (*„it cannot simply be copied here"*), ale collections **nie zostało
zmienione**. Czyli: ryzyko opisane w comments formalnie **dotyczy collections nadal**.

> ⚠️ **Korekta po przeglądzie P18:** nazwałem to wyżej „nazwanym kompromisem". Po weryfikacji to
> jest **defekt**, nie kompromis — scenariusz utraty danych w collections jest osiągalny
> (30-minutowa awaria bazy → kolekcje kasowane po tym, jak saga oddała konto), a u koordynatora
> istnieje **drugi, niezależny** mechanizm dający ten sam skutek (zamiatacz liczy przeterminowanie
> od `created_at`, więc kapituluje ~45 s po progu, gdy uczestnik ma jeszcze ~120 s budżetu).
> Patrz §20 (Errata) oraz `portal/PLAN-P18.md` poz. 11 i 12.

### 8.5 Jeden wspólny odruch: pusty adres **nie jest** potwierdzany

Wszyscy trzej uczestnicy przy komendzie z pustym adresem **porzucają ją bez potwierdzenia**.
Rozumowanie jest identyczne we wszystkich trzech javadocach: potwierdzenie wyczyszczenia „nikogo"
byłoby **kłamstwem**, które popchnęłoby sagę do przodu. Uczciwym sygnałem jest timeout
koordynatora. Test w memach nazywa to: *„confirming a deletion that never happened would advance
the saga on a lie"*.

---

## 9. Akt V — kworum i werdykt

Potwierdzenie od uczestnika: `{"type":"USER_CONTENT_PURGED","email":"…","version":1,"sagaId":"…"}`,
każdy na swoim topiku.

### 9.1 Jak koordynator liczy kworum

```
1. znajdź sagę:  sagaId != null → szukaj po id, ale TYLKO w stanie STARTED
                 sagaId == null → szukaj po adresie (wsparcie dla starych producentów)
2. INSERT do offboarding_confirmations (saga_id, participant, confirmed_at)
   — duplikat rozbija się o klucz główny (23505) i jest JAWNIE ignorowany
3. czy confirmedParticipants(saga).containsAll(required)?
4. jeśli tak:  UPDATE … SET state='COMPLETED', running_email=NULL
               WHERE id = ? AND state = 'STARTED'
5. ogłoś werdykt TYLKO jeśli ten UPDATE zmienił dokładnie jeden wiersz
```

Punkt 4–5 to **zatrzask jednorazowy** (once-latch) i jest to najważniejsza technika w całej
sadze, więc nazwijmy ją precyzyjnie:

> **Zatrzask jednorazowy** to warunkowy `UPDATE`, którego klauzula `WHERE` zawiera stan, z którego
> wychodzimy. Baza gwarantuje, że **dokładnie jedno** wywołanie zmieni wiersz — pozostałe zobaczą
> „0 zmienionych wierszy". Dzięki temu „dowiedziałem się o kompletności" jest zdarzeniem
> jednorazowym nawet przy dostarczaniu at-least-once i przy dwóch równoległych konsumentach.

Bez tego: dwa potwierdzenia dochodzące jednocześnie mogłyby oba stwierdzić kompletność i wysłać
**dwa werdykty**.

Zwróć uwagę, że w punkcie 1 `sagaId` wskazujący sagę **już zamkniętą** jest **błąkańcem** (stray) —
i **nie degraduje się** do szukania po adresie. Komentarz tłumaczy dlaczego: *„that would let an
echo of a finished case land on a NEWER saga for the same account"*. Trójwartościowa logika:
brak pola → szukaj po adresie; pole poprawne → szukaj po id; pole obecne, ale zepsute → porzuć.

### 9.2 Werdykt i „mini-outbox"

```json
{ "id": "UUID.nameUUIDFromBytes(sagaId + '|' + type)",
  "type": "PORTAL_CONTENT_PURGED",     // albo PORTAL_PURGE_FAILED
  "email": "…", "version": 1,
  "confirmed": ["comments","memes"] }  // tylko przy porażce, posortowane
```

**Identyfikator werdyktu jest wyprowadzany deterministycznie** z pary `(sagaId, type)`, nigdy
losowy. To celowe: zamiatacz może ogłosić werdykt ponownie, a **retransmisja musi być bajtowo
identyczna**, żeby odbiorca rozpoznał duplikat po identyfikatorze. Z tego samego powodu lista
`confirmed` jest sortowana.

Offboarding **nie ma tabeli outboxu** — ma **flagę** `outcome_announced` na wierszu sagi.
Uzasadnienie: generyczna tabela obok byłaby drugim źródłem prawdy. Mechanizm jest ten sam:
zakończenie sagi **nie znaczy** ogłoszenia werdyktu. Flaga jest stawiana **osobno**, dopiero po
**udowodnionej** dostawie do brokera (`flush`, potem sprawdzenie każdego `Future` przez `get()`).
A zamiatacz co 15 s wyłapuje sagi zakończone z `outcome_announced = FALSE` starsze niż 30 s
i ogłasza je ponownie.

**Pole `confirmed` przy porażce** to rzecz, którą warto docenić: to różnica między „nic się nie
stało, spróbuj jeszcze raz" a „twoje memy zniknęły, komentarze nie". Ciekawostka: security
tę listę **loguje**, ale **nie mówi o niej użytkownikowi** — mail z przeprosinami mówi tylko, że
usunięcie się nie udało. Javadoc nazywa to jawnie: *„Naming the participants in the log is the
cheap half of the fix"*, a pokazanie tego użytkownikowi wymagałoby nowego pola w żądaniu maila
i szerszego paktu.

---

## 10. Zegary — cała arytmetyka w jednym miejscu

Ten przepływ ma **cztery niezależne budżety czasowe w trzech repozytoriach**. Ich wzajemne
ustawienie nie jest przypadkowe i to jest dokładnie ten rodzaj wiedzy, którego nie da się
odgadnąć z kodu jednego serwisu.

| Kto | Parametr | Domyślnie | Co robi |
|---|---|---|---|
| memes, comments | `SagaRetryBudget` | **90 s** | budżet ponowień jednego rekordu; potem głośne porzucenie |
| offboarding | `OFFBOARDING_PURGE_TIMEOUT_SEC` | **120 s** | po tym czasie saga jest „przeterminowana" |
| offboarding | `SWEEP_EVERY` | **15 s** | jak często zamiatacz sprawdza |
| offboarding | `OFFBOARDING_MAX_PURGE_RETRIES` | **3** | ile razy ponowić rozkaz przed kapitulacją |
| offboarding | `OFFBOARDING_OUTCOME_REPUBLISH_SEC` | **30 s** | po tym czasie nieogłoszony werdykt jest wysyłany ponownie |
| offboarding | `OFFBOARDING_RETENTION_DAYS` | **30 dni** | po tym czasie zakończone sagi są usuwane (dane osobowe) |
| security | `account-deletion.purge-timeout` | **12 min** | siatka bezpieczeństwa: brak werdyktu → kompensacja. Podniesiona z 5 min 2026-08-08, żeby znów odpalała PO portalu — patrz „Pierwsza" niżej |
| security | tick `AccountDeletionTimeouts` | **30 s** | jak często security sprawdza przeterminowane sagi |
| security | listener werdyktów | **10 prób**, 1 s wykładniczo | ponowienia przy błędzie obsługi werdyktu |

**Jak to razem gra** (przebieg kapitulacji — poniższe znaczniki są ZMIERZONE na żywym stosie
2026-08-08, z logów `microservice-offboarding`, a nie wyliczone na kartce):

```
t=0      żądanie usunięcia; konto zablokowane; rozkaz purge wychodzi
t≈0      memes i comments OZNACZAJĄ treść i potwierdzają; collections nie odpowiada (leży)
t=120 s  saga przeterminowana → 1. ponowienie rozkazu
t=240 s  2. ponowienie
t=360 s  3. ponowienie → budżet wyczerpany
t≈480 s  KAPITULACJA PORTALU: RESTORE_USER_CONTENT do wszystkich uczestników (treść wraca),
         potem werdykt PORTAL_PURGE_FAILED → security odblokowuje konto i wysyła „nie udało się"
t=720 s  (siatka security nigdy się nie odpala — werdykt portalu wygrał wyścig)
```

**Ten przebieg był przez chwilę inny i warto wiedzieć dlaczego.** Przy siatce 5-minutowej to
tożsamość odpalała pierwsza: konto wracało ok. 5 minuty, a treść dopiero ok. 8. Nikt tego nie
zaprojektował — po P18 portal potrzebuje ośmiu minut, a próg po drugiej stronie został na pięciu.
2026-08-08 siatka poszła na 12 minut i kolejność wróciła do zamierzonej.

Dwie rzeczy do zapamiętania z tej tabelki:

**Pierwsza — dlaczego security czeka 5 minut, a portal 2 (i dlaczego to już nie wystarcza).**
Komentarz przy parametrze mówi:
*„the safety net fires well AFTER the portal's own timeout (2m), so the portal's failure
announcement normally wins the race"*. Siatka bezpieczeństwa security istnieje na wypadek
**śmierci koordynatora**, nie zwykłej porażki. Gdyby oba timeouty były równe, wyścig
rozstrzygałby się losowo, a wtedy w bazie sagi security mógłby wylądować stan `COMPENSATED`
w momencie, gdy portal właśnie ogłasza sukces — dokładnie ta katastrofa z rozdziału 12.

**Ten argument mówił o progu 2 minut, a po P18 portal potrzebuje ośmiu** — więc pięciominutowa
siatka przestała być „dobrze PO" i zaczęła odpalać PRZED. **Rozstrzygnięte 2026-08-08: siatka
poszła na 12 minut**, czyli ponad `purgeTimeout × (retries + 1)` plus zamiatanie i drogę maila.
Reguła jest zapisana przy samym parametrze, bo to jedyne miejsce, w którym ktoś zmieniający tamtą
stronę ma szansę ją przeczytać. Wariant odrzucony: „przyjmijmy, że przy długiej ciszy konto oddaje
tożsamość" — brzmi niewinnie, ale zamienia siatkę na martwego koordynatora w drugi, równoległy
mechanizm kompensacji, a wtedy spóźniony werdykt portalu trafia na sagę `COMPENSATED` i jest
odrzucany. To jest dokładnie katastrofa z rozdziału 12, tylko wpisana w projekt.

**Druga — pułapka, którą warto znać, i to w wersji ODWRÓCONEJ po P18.** Do lipca 2026
przeterminowanie liczono od `created_at`: po pierwszym przekroczeniu progu saga była
przeterminowana **na zawsze**, więc ponowienia wychodziły na KAŻDYM przebiegu zamiatacza — co 15 s
— i cała kapitulacja mieściła się w ~172 s. Brzmiało to sprytnie, a było błędem: zamiatacz
kapitulował, gdy uczestnik jeszcze pracował (jego własny budżet ponowień to 90 s na rozkaz), więc
purge mógł się wykonać PO tym, jak konto oddano właścicielowi z przeprosinami.

Naprawa (P18) liczy przeterminowanie od `updated_at` — od OSTATNIEJ dostarczonej próby. Skutek
arytmetyczny: ponowienia co 120 s, a cała sprawa to `purgeTimeout × (retries + 1) = 120 × 4 ≈
8 minut`. Komentarz w `Main` mówi to wprost. **Uwaga na dokumenty starsze niż ta zmiana** — scenariusz
e2e kapitulacji nosił starą arytmetykę (~172 s) w prozie i w timeoutach kroków jeszcze 2026-08-08,
i nie wykrył tego nikt, bo ten scenariusz nie był uruchamiany w żadnym CI.

I jeszcze jedna subtelność, ładna: **licznik ponowień nie jest naliczany w momencie wysłania
rozkazu**, tylko dopiero po **udowodnionej dostawie** do brokera. Uzasadnienie:
*„Counting here would let a dead broker burn all retries without a single command on the wire,
and the saga would compensate having never re-asked."* Martwy broker nie spala budżetu ponowień.

---

## 11. Wzorzec drugi: **idempotencja** — trzy różne techniki, jeden cel

`at-least-once` znaczy „każda wiadomość dojdzie co najmniej raz, ale może kilka razy". Skoro
duplikaty są faktem, **każdy odbiorca musi znieść powtórkę**. Ten system robi to na **trzy różne
sposoby** i różnica między nimi jest istotna:

### 11.1 Zatrzask jednorazowy (warunkowy `UPDATE`)

Stosowany do **przejść stanów sagi**, w obu serwisach:

```sql
UPDATE account_deletion_sagas SET state = 'COMPLETED' WHERE email = ? AND state = 'STARTED'
UPDATE offboarding_sagas      SET state = 'COMPLETED', running_email = NULL
                              WHERE id = ? AND state = 'STARTED'
```

Powtórka zmienia 0 wierszy i nie robi nic więcej. Javadoc: *„Transitions are idempotent and latch
once — at-least-once delivery makes duplicates a fact of life."*

### 11.2 Rejestr obsłużonych wiadomości (tabela deduplikacji)

Stosowany w security do **werdyktów portalu**, bo tam skutek jest nieodwracalny:

```sql
INSERT INTO processed_offboarding_outcomes (id, outcome_type, processed_at)
VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING
```

Zwraca liczbę wstawionych wierszy. `1` → „to ja, działam". `0` → „już obsłużone, wychodzę".
Wykonywane **w tej samej transakcji** co przejście sagi, więc rollback zwalnia rezerwację
i ponowne dostarczenie jest znów „pierwszą próbą". Wiersze sprzątane po 7 dniach.

**Dlaczego to musiało powstać** (migracja V18 opisuje konkretną awarię): przejścia sagi w security
korelują po **adresie e-mail**, a *„an e-mail is not an identity, it is a person, and a person can
have more than one deletion saga"*. Powtórnie ogłoszony werdykt **sagi nr 1** mógł zamknąć
**sagę nr 2** tej samej osoby. Rejestr po identyfikatorze wiadomości to załata — ale zauważ, że
jest to **plaster na słabą korelację**, nie jej usunięcie: `sagaId` jest wysyłany w fakcie, ale
metody `complete`/`compensate` w security w ogóle go nie przyjmują.

### 11.3 Idempotencja z natury operacji (bez żadnego rejestru)

Stosowana u **uczestników**. `DELETE FROM collection_items WHERE user_email = ?` wykonany dwa razy
daje ten sam stan. Drugi przebieg purge w memach nie znajduje już nic po autorze. Javadoc:
*„The purge is idempotent, so at-least-once delivery needs no extra dedup."*

Konsekwencja, którą trzeba znać: uczestnik **potwierdza także pusty przebieg**. „Nie miałem nic
do zrobienia" i „zrobiłem" to **to samo zdarzenie**. Jedynym wyjątkiem jest pusty adres
(rozdział 8.5).

### 11.4 To jest zapisane jako **prawo**, nie zwyczaj

ADR 0006 („idempotent commands by default") mówi: każda komenda wykonana dwa razy zostawia stan
jak po jednym wykonaniu — a **odpowiedzi mogą się różnić** (`SAVED` → `ALREADY_SAVED`). Pilnuje
tego **jeden generyczny test na serwis**, nie scenariusz na operację: `IdempotentCommandsTest`
ma mapę komend, uruchamia każdą raz i dwa razy, i porównuje odcisk stanu. Nowa komenda wchodzi
pod prawo **dopiero po dopisaniu do tej mapy**.

Zadeklarowane wyjątki od prawa: `AddComment` (dwa wywołania = dwa komentarze) i `CastVote`
(drugi identyczny głos **odwraca** pierwszy).

---

## 12. Co może pójść nie tak — i co system o tym wie

Ten rozdział jest najcenniejszy do opowiadania, bo pokazuje **granice** rozwiązania. Systemy
rozproszone nie mają rozwiązań bez wad; mają wady **nazwane** albo **ukryte**.

### 12.1 Katastrofa: treść wymazana **po** kompensacji

Najgorszy możliwy przebieg, obsłużony jawnie w kodzie:

```
security kapituluje (5 min ciszy)  → COMPENSATED, konto odblokowane, mail „nie udało się"
        …ale portal jednak dokończył purge i ogłasza PORTAL_CONTENT_PURGED
security: zatrzask nie puszcza (stan już COMPENSATED)
```

Kod rozpoznaje to i **nie miesza z duplikatem**:

```java
if (sagas.lastSagaWasCompensated(email)) {
    LOG.error("CONTENT ERASED AFTER COMPENSATION for {}: the portal confirmed the purge"
            + " after this service had already given up, unlocked the account and"
            + " apologised. The account exists; its content does not. This needs"
            + " a human — the deletion timeout here and the portal's retry budget"
            + " are independent dials in separate repositories.", masked(email));
    return;
}
LOG.info("portal-purged outcome for {} matched no running deletion; ignoring", masked(email));
```

Komentarz nad tym mówi też, jak było **przedtem**: oba przypadki dzieliły jedną linię `INFO`.
Czyli użytkownik zostawał z kontem i bez wszystkich swoich memów, komentarzy i kolekcji,
**a jedynym śladem była linia logu, której nikt nie grepuje**. Zdanie *„the deletion timeout here
and the portal's retry budget are independent dials in separate repositories"* jest istotą całego
problemu: to nie jest bug w jednej linii, to **skutek architektury** — dwa niezależne timeouty
w dwóch repozytoriach, których nikt nie waliduje względem siebie.

### 12.2 Okno resztkowe u uczestnika

Ostatnie ponowienie w memach/comments może dokończyć purge do ~75 s **po** kapitulacji sagi.
Javadoc nazywa to *„the residual window, named rather than hidden"* — okno resztkowe, nazwane
zamiast ukryte. To ta sama katastrofa co wyżej, tylko wywołana z drugiej strony.

### 12.3 Częściowe wyczyszczenie, o którym użytkownik nie wie

Werdykt `PORTAL_PURGE_FAILED` niesie listę `confirmed`. Security ją **loguje**, ale mail
z przeprosinami mówi tylko „usunięcie się nie udało". Użytkownik z listą `confirmed=[memes]`
dostaje przeprosiny i konto — nie dowiaduje się, że memów już nie ma.

### 12.4 Jeden wiersz outboxu blokuje kolejkę (security)

`OutboxPublisher.drain()` przy błędzie brokera robi `return` — **przerywa cały przebieg**, żeby
zachować kolejność (*„keep ordering: stop at the first failure, retry next tick"*). Skutek: jedno
trwale niepublikowalne zdarzenie na czele kolejki **blokuje wszystkie późniejsze**. Kod świadomie
wybiera kolejność ponad postęp. Wspólna biblioteka (memes, comments) tego problemu nie ma —
tam wiersz po 25 nieudanych próbach przestaje być wybierany i reszta płynie dalej.

### 12.5 `stopOnExhaustedRetry` nie robi tego, co brzmi

Listener werdyktów w security ma `errorStrategy = @ErrorStrategy(RETRY_EXPONENTIALLY_ON_ERROR,
retryDelay = "1s", retryCount = 10, stopOnExhaustedRetry = true)`. Nazwa sugeruje „zatrzymaj".
Javadoc jest **jawnie skorygowany** i wart przeczytania w całości, bo to modelowa lekcja
o zaufaniu do nazw:

> *„This javadoc said 'the container STOPS rather than skips' for half a day, and that is not what
> happens … the mechanism is a paused partition, not a stopped service."*

Faktycznie w `micronaut-kafka` 6.1.0 opcja robi **trzy rzeczy i nic więcej**: cofa się na feralny
offset, woła raz `handleException` i **pauzuje tę jedną partycję**. Kontener dalej działa, pętla
`poll` dalej się kręci — partycja po prostu **nigdy nie jest wznawiana**. Offset nie jest
zatwierdzony, więc restart procesu odtworzy rekord.

Konsekwencja, która wygląda niewinnie, a jest poważna: **taki stan jest niewidzialny** dla sondy
żywotności i dla licznika `poll`-i. Serwis odpowiada na `/health`, loguje, obsługuje logowania —
a **żadne usunięcie konta się nie domyka**. Dlatego istnieje osobna lampka
`OffboardingListenerHealth`, która pyta `isPaused`, a nie tylko o licznik przebiegów. Ta lampka
jest wpięta w **readiness** security, i to jest właściwy sygnał: *„a stopped outcome listener means
every account deletion in the portal stops closing while sign-in keeps working perfectly, so
'do not send me work' is the honest signal and a restart is not"*.

### 12.6 Ścieżka „zero uczestników" łamie zatrzask

`BeginOffboarding` **ignoruje** wartość zwracaną przez `sagas.complete(...)` i bezwarunkowo
zwraca `completedImmediately = true`. Przy odtworzeniu tego samego faktu (`fact_id` znajduje
zamkniętą sagę) werdykt zostanie ogłoszony **ponownie**. Ratuje to dopiero deduplikacja po
stronie odbiorcy (identyfikator werdyktu jest deterministyczny, więc retransmisja jest bajtowo
identyczna). `IdempotentCommandsTest` tego nie łapie, bo porównuje **stan**, a nie wysłane
komunikaty.

---

## 13. Co po użytkowniku zostaje (i dlaczego to ma znaczenie prawne)

`DeleteAccount.execute` wykonuje pięć kroków, w tej kolejności (kolejność jest zabezpieczona
testem `InOrder`):

```
1. revokeAllSessions          — sesje
2. enrolledFactorRepository.removeAll   — czynniki MFA (sekrety!)
3. recoveryCodeRepository.removeAll     — kody odzyskiwania (sekrety!)
4. federatedIdentityRepository.unlinkAll — powiązania OAuth
5. userRepository.deleteByEmail          — sam wiersz użytkownika
```

Kolejność jest umyślna: *„the secrets (factor material, recovery-code hashes) must be gone BEFORE
the user row is"*. Krok 4 ma osobne uzasadnienie bezpieczeństwa: stare powiązanie z Google nie może
przeżyć konta, bo otworzyłoby konto **następnej osobie, która zarejestruje zwolniony adres**.

**Co zostaje w bazie security po „prawie do bycia zapomnianym"** (GDPR — General Data Protection
Regulation, po polsku RODO):

| Zostaje | Zawiera adres e-mail? | Retencja |
|---|---|---|
| wiersz `account_deletion_sagas` w stanie `COMPLETED` | **tak**, w jawnej kolumnie `email` | **brak** — nic tego nie kasuje |
| opublikowane wiersze `outbox_events` | **tak**, w `event_key` i w payloadzie | **brak** |
| `processed_offboarding_outcomes` | nie (tylko identyfikator) | 7 dni |
| `email_verifications`, `password_resets`, `email_changes`, `passwordless_accounts` | tak | **brak** — `DeleteAccount` ich nie dotyka |

I dodatkowo: w całym schemacie security **nie ma ani jednego klucza obcego** (`grep REFERENCES`
po migracjach nie zwraca nic), więc **nie ma kasowania kaskadowego**, które posprzątałoby to
za nas. Warto też wiedzieć, że część tych danych **nie da się** wyczyścić bez zmiany kodu, bo
odpowiednie porty domeny nie mają metod usuwających.

Dla porównania: offboarding **ma** retencję (`deleteFinishedBefore`, domyślnie 30 dni, kasuje
zakończone i ogłoszone sagi razem z potwierdzeniami), z jawnie zaakceptowanym skutkiem ubocznym:
usunięcie sagi zapomina jej `fact_id`, więc odtworzenie **bardzo starego** faktu rozwidli świeżą
sagę — *„accepted, because Kafka's upstream retention is far shorter than this threshold"*.

Trzecia rzecz, którą trzeba znać: **access token jest samodzielnym JWT** (JSON Web Token),
podpisanym EdDSA, weryfikowalnym offline przez `/.well-known/jwks.json`. Security traktuje jego
wartość jako nieprzejrzysty sekret, więc jego **własne** wylogowanie działa natychmiast — ale
usługa weryfikująca token offline może go przyjmować **do godziny** (tyle żyje access token) po
usunięciu konta. Javadoc mówi wprost, że to świadomy kompromis: *„offline verification cannot see
a logout or a role change until the token expires, introspection can"*.

---

## 14. Czym to jest udowodnione (i czym nie)

Do rozmowy o jakości — a tu jest ciekawy układ, bo **zielone CI nie znaczy, że przepływ działa**.

**Trzy warstwy testów koordynatora:**
1. **BDD** (`offboarding.feature`, 17 scenariuszy) na **prawdziwym** routerze z pamięciowym
   magazynem i **ręcznie nakręcanym zegarem**;
2. **`JdbcSagaStoreTest`** — adapter JDBC na H2 w trybie PostgreSQL, z **produkcyjnymi**
   migracjami Flyway; tu są testy wyścigów na dwóch prawdziwych wątkach;
3. **`KafkaLoopIntegrationTest`** — Testcontainers z prawdziwym brokerem: `flush` przed
   zatwierdzeniem offsetu, offset przechodzący za zatrutym rekordem, cofnięcie i backoff.

**Pakty** (contract tests, Pact) pinują kształty wiadomości na wszystkich krawędziach:
security→offboarding (fakt), offboarding→3 uczestników (rozkaz), uczestnicy→offboarding
(potwierdzenia), offboarding→security (werdykt), security→email (maile). Tryb **plikowy** (ADR
0003): konsument nagrywa JSON do swojego `pacts/`, producent czyta go **ścieżką względną** do
sąsiedniego checkoutu; brak sąsiada = **skip**, nie fail.

I tu najlepszy szczegół w całym systemie: istnieje klasa `SilentlySkippedPactTest`, która pilnuje,
żeby pakty **nie skipowały się po cichu** — failuje, gdy sąsiad *jest* sklonowany, a pakt nie leży
tam, gdzie patrzy test. Bo *„a contract test that never runs is indistinguishable from one that
passes, which is the worst thing a contract test can be"*.

**Skrypty end-to-end** w repo portalu:
- `e2e-saga.sh` — happy path plus kaskada, 4 scenariusze `cucumber-js` po **czystym HTTP** przeciw
  **żywemu** stackowi. Weryfikuje przez publiczne API: mem zwraca 404, komentarz podpisany
  „deleted account", pusta lista ulubionych, `401` na logowaniu, **mail pożegnalny w Mailpicie**.
- `e2e-saga-outage.sh` — naprawdę **zatrzymuje kontener** `user-collections` i obserwuje
  kapitulację po ~172 s. Ten scenariusz da się zainscenizować deterministycznie **tylko dlatego,
  że kapitulacja jest gwarantowana arytmetyką** (rozdział 10).

**A teraz asymetria, którą trzeba znać:**

| Co zepsute | Co to wyłapie | Kiedy |
|---|---|---|
| logika sagi, kształt wiadomości | reaktor Mavena, pakty, Testcontainers | **per PR** |
| wiring: nazwy topików, `OFFBOARDING_PARTICIPANTS`, compose | **tylko** `e2e-saga.yml` | **nightly** (nocą) |
| kształt faktu po stronie security | **nic w żadnym CI** — pakt providera jest skipowany, bo CI repo `shared` nie klonuje portalu | dopiero lokalnie albo nightly |
| ścieżka kompensacji (`@outage`) | **nic automatycznie** — wymaga ręcznego uruchomienia | nigdy |
| manifesty k8s, usunięcie bajtów obrazków | **nic** | nigdy |

Nagłówek workflow `e2e-saga.yml` mówi, skąd on się wziął, i to jest zdanie do zapamiętania:
literówka w nazwie topiku potwierdzeń *„passed the whole Maven reactor, every pact and that suite
without one red mark, while in production the content was already gone and the saga capitulated.
This workflow is the missing red mark."*

---

## 15. Decyzje zapisane (ADR) i jak to się uruchamia

**ADR-y** (Architecture Decision Record — zapis decyzji architektonicznej) w `../shared/docs/adr`,
siedem sztuk. Dla tego przepływu istotne jest pięć:

- **0002 — podkreślnik dla kroków przypadku użycia.** Klasa `_NazwaKlasy` to **package-private
  krok** wewnętrzny (use-case step). Uzasadnienie: modyfikatory dostępu Javy *„are invisible in
  the places where a reader actually scans a package: the file listing, imports, and IDE
  navigation"*. Uwaga: w pakiecie `account` (czyli w usuwaniu konta) **nie ma ani jednej takiej
  klasy** — ten przypadek użycia jest płaski, jego „kroki" to kolejne wywołania repozytoriów
  w jednej metodzie. Konwencję widać w `authentication` (`_VerifyCredentials`, `_BruteForceGuard`).
- **0003 — kontrakty konsumenckie w plikach** (opisane wyżej).
- **0004 — wersjonowane koperty zdarzeń.** Każda koperta niesie `"version": 1`. W ramach wersji
  ewolucja **wyłącznie addytywna**; zmiana łamiąca podbija wersję, a producent emituje stary
  kształt **obok** nowego (expand/contract). Uzasadnienie: bez wersji *„deploy both sides at once"
  was the default upgrade plan, „which stops being a plan the moment two services deploy
  independently"*.
- **0006 — idempotencja jako prawo domyślne** (opisane wyżej).
- **0007 — miękkie usuwanie przez status, żeby saga miała czym kompensować** (2026-08-08, opisane
  w §2). Trzy rozstrzygnięcia warte zapamiętania: **status na agregacie, nie osobny byt**
  (`ToDelete<T>` ani tabela-kolejka — fakt jest własnością jednego wiersza i miałby własną kaskadę,
  własną idempotencję i join w każdym odczycie); **kasowanie z domknięcia sagi, nie z upływu czasu**
  (reaper to zapytanie `WHERE status = 'PENDING_ERASURE'`, nie scheduler; czas kupuje alarm);
  **pivot w `PurgeUserContent`**, gdy obrazek opuszcza MinIO. Filtr `ACTIVE` jest zapisany raz na
  serwis — jako widok bazodanowy — a strażnik (`MemeReadFilterTest`, `CommentReadFilterTest`)
  wywala build, jeśli jakikolwiek SQL poza adapterem świadomym wymazywania nazwie tabelę bazową.

**Uruchomienie lokalne** — jeden stack `docker compose`, sklejony z trzech plików mechanizmem
`include:`, z nazwą projektu **przypiętą** do `security`, żeby portal i gra F1 dzieliły jeden
stack tożsamości i te same wolumeny.

**Kafka:** jeden węzeł KRaft (bez ZooKeepera), `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`. Wszystkie
siedem topików sagi powstaje **automatycznie z domyślnymi ustawieniami brokera** — liczba
partycji i retencja **nigdzie nie są zadane**. Jedyny topik konfigurowany jawnie to
`mail-requests-dlq` (1 partycja, `cleanup.policy=compact`, `retention.ms=-1`), bo — jak mówi
komentarz — *„The dead-letter topic is a LEDGER, not a tape"*: serwis maili odbudowuje swoją
kolejkę zaparkowanych wiadomości **replayem całego topiku**, więc broker nie może ich wymiatać
po 7 dniach.

**Wdrożenie na k8s/k3s** (`k8s`, kustomize base + overlay dev, zwalidowane na lokalnym
k3d) odtwarza compose 1:1, z trzema rzeczami wartymi zapamiętania:

1. **Kafka stoi na `emptyDir`** — każdy reschedule kasuje wszystkie topiki, także zdarzenia sagi
   w locie. Świadome dla klastra dev; znaczy, że trwałość przepływu opiera się na **stanie
   w bazach** (outbox security, magazyn sagi offboardingu), nie na brokerze.
2. **Rozdzielenie sond**: `readiness` (`/health`) mówi „nie kieruj tu ruchu" i czerwienieje, gdy
   leży broker albo baza; `liveness` (`/alive`) czerwienieje **tylko** gdy wątek pętli umarł.
   Wcześniej `liveness` na `/health` **restart-loopował** pody przy leżącym Postgresie — a restart
   leczy wyłącznie martwy wątek. Tolerancja `/alive` ma wyliczoną **podłogę**: suma najgorszej
   legalnej iteracji (146 s) plus 25% marginesu = 183 s; wartość niższa jest głośno podnoszona.
3. **`strategy: Recreate` dla security** — bo relay outboxu to zwykły `SELECT` bez blokad
   (rozdział 6.4).

`microservice-offboarding` **nie jest wystawiony** przez Ingress. Cały proces usuwania konta jest
sterowany **wyłącznie zdarzeniami**; żeby zajrzeć do jego `/health` z zewnątrz, trzeba
`port-forward`.

---

## 16. Kolejność czytania kodu

Jeśli chcesz przejść ten przepływ z palcem na ekranie, to jest kolejność, która się nie zapętla:

```
 1. shared/microservice-security/security-infrastructure/.../DeleteAccountController.java   (70 linii)
 2. shared/microservice-security/security-system/.../account/StartAccountDeletion.java       (34 linie)
 3. shared/microservice-security/security-domain/.../vo/PurgeChoices.java                    (22 linie)
 4. shared/microservice-security/security-infrastructure/.../AccountDeletionOrchestrator.java (191 linii)
 5. shared/microservice-security/security-infrastructure/.../persistence/OutboxPublisher.java (90 linii)
 6. portal/microservice-offboarding/.../infrastructure/EventsRouter.java        ← czysta logika
 7. portal/microservice-offboarding/.../application/SagaStore.java              ← kontrakt w javadocu
 8. portal/microservice-offboarding/.../infrastructure/JdbcSagaStore.java       ← tu siedzi „domena"
 9. portal/microservice-offboarding/.../infrastructure/KafkaLoop.java           ← transport
10. portal/microservice-memes/memes-application/.../PurgeUserContent.java
11. portal/microservice-memes/memes-infrastructure/.../PurgeCommandsListener.java
12. portal/microservice-user-collections/.../infrastructure/PurgeCommandsConsumer.java
13. shared/microservice-security/security-infrastructure/.../OffboardingOutcomeListener.java
14. shared/microservice-security/security-system/.../account/DeleteAccount.java              (42 linie)
```

Plus **migracje jako narracja** — czyta się je jak dziennik decyzji:
`security/…/V6__account_deletion_saga.sql` (początek), `V17__saga_extracted_to_offboarding.sql`
(wyprowadzka orkiestracji), `V18__processed_offboarding_outcomes.sql` (deduplikacja werdyktów),
`offboarding/…/V1__offboarding_sagas.sql`, `V2__saga_hardening.sql` (utwardzenie po wyścigach),
`V3__saga_policy.sql`.

**Uwaga metodyczna, ważna przy nauce tego kodu:** javadoc w tym systemie jest **zapisem potyczek**,
nie ozdobą. Kilka komentarzy zawiera samokrytykę (*„This javadoc said … for half a day, and that
is not what happens"*), a inne opisują zmierzone awarie z liczbami. Ale niektóre są **nieaktualne**
i trzeba to wiedzieć:

- `security/todo.md` twierdzi, że domyślny timeout to 2 minuty — w kodzie jest **5 minut**
  (testy zgadzają się z kodem, nie z `todo.md`);
- javadoc w comments mówi, że rozkaz publikuje offboarding, a w collections — że publikuje go
  outbox security. **Aktualny jest pierwszy opis** (rozkaz wysyła `EventsRouter` offboardingu);
- `docs/mfa-design.md` opisuje endpoint `POST /account/step-up/start` — w kodzie jest
  `POST /account/step-up`;
- `docs/onboarding-guide.md` twierdzi, że duplikat faktu znajduje sagę po `id` faktu — w security
  zatrzask sagi odnajduje ją po **adresie e-mail**; po identyfikatorze deduplikowane są tylko
  **werdykty** portalu.

To nie przypadkowa lista wad — to praktyczna zasada: **kod jest źródłem prawdy, komentarz jest
świadkiem**, i to świadkiem, który mógł nie zauważyć ostatniej zmiany.

---

## 17. Słownik terminów tego etapu

| Termin | Po angielsku | Co znaczy tutaj |
|---|---|---|
| saga | saga | rozproszona operacja bez wspólnej transakcji: ciąg lokalnych transakcji plus kompensacja |
| koordynator | process manager | `microservice-offboarding`: wie, kto potwierdził, i ogłasza jeden werdykt |
| uczestnik | participant | serwis treści czyszczący jedną oś danych: memes, comments, collections |
| kompensacja | compensation | świadome odwrócenie skutku zamiast `rollback`; tutaj: odblokowanie konta |
| skrzynka nadawcza w transakcji | transactional outbox | zdarzenie jako wiersz w tej samej transakcji co zmiana; wysyłka później |
| co najmniej raz | at-least-once | wiadomość dojdzie, ale może kilka razy — nigdy zero razy |
| zatrzask jednorazowy | once-latch | warunkowy `UPDATE … WHERE state='STARTED'`; dokładnie jeden wołający „wygrywa" |
| idempotencja | idempotence | powtórka nie zmienia stanu |
| podniesienie sesji | step-up (authentication) | świeży dowód tożsamości dla jednej wrażliwej akcji |
| blokada konta | pending deletion | flaga `users.pending_deletion`: konto istnieje, ale nie wpuszcza |
| błąkaniec | stray | potwierdzenie, które nie ma na czym wylądować (zamknięta lub nieznana saga) |
| zatruty rekord | poison pill | wiadomość, której nie da się przetworzyć nigdy; tu: log i porzucenie |
| DLQ | Dead Letter Queue | kolejka wiadomości niedostarczalnych. **W tym przepływie jej nie ma** — jedyna w systemie to `mail-requests-dlq` w serwisie maili |
| klucz odtworzenia | replay key | `fact_id`: ten sam fakt drugi raz trafia w swoją sagę, nie rozwidla nowej |
| identyfikator korelacji | correlation id | 8-znakowy `cid` w nagłówku `X-Correlation-Id`; jeden grep pokazuje przepływ w czterech serwisach |
| wersjonowana koperta | versioned envelope | pole `"version": 1` w każdym komunikacie (ADR 0004) |
| kontrakt konsumencki | consumer-driven contract, Pact | konsument deklaruje pola, które czyta; producent weryfikuje plik z sąsiedniego repo |
| tolerancyjny czytelnik | tolerant reader | czytaj tylko to, czego potrzebujesz; nieznane pola ignoruj |
| dane osobowe | PII (Personally Identifiable Information) | tutaj: adres e-mail — dlatego logi maskują go do `ab***@domena` |
| MFA | Multi-Factor Authentication | uwierzytelnianie wieloczynnikowe |
| RODO | GDPR (General Data Protection Regulation) | „prawo do bycia zapomnianym" jest wprost cytowane w javadocu `DeleteAccount` |

---

## 18. Trzy zdania, które warto umieć powiedzieć z pamięci

1. **„Usunięcie konta to saga, bo dane leżą w pięciu bazach i nie ma jednej transakcji; tożsamość
   ogłasza fakt i czeka, portal koordynuje, a nieodwracalny krok jest ostatni — uczestnicy najpierw
   **oznaczają** treść (`PENDING_ERASURE`, niewidoczna, nietknięta), kasują dopiero na domknięcie
   sagi, więc kompensacja oddaje konto **razem z treścią**, a punktem bez powrotu jest dopiero
   skasowanie obrazka z MinIO."**
2. **„Fakt nie jest wysyłany do brokera w trakcie transakcji, tylko zapisywany jako wiersz outboxu
   w tej samej transakcji co blokada konta — dzięki temu blokada i fakt nigdy się nie rozjadą,
   a ceną jest at-least-once, czyli obowiązkowa idempotencja u każdego odbiorcy."**
3. **„Każdy timeout w tym przepływie jest wyliczony względem cudzego timeoutu: 90 sekund budżetu
   uczestnika mieści się pod 120-sekundowym progiem koordynatora, a 5-minutowa siatka tożsamości
   jest z rozmysłem dłuższa niż kapitulacja portalu — żeby werdykt wygrywał wyścig z siatką."**

---

## 19. Co w kolejnych etapach

Kolejność zaproponowana — do zmiany, jeśli któryś temat jest pilniejszy:

| Etap | Przepływ | Co nowego wnosi |
|---|---|---|
| 2 | **Rejestracja i logowanie** | łańcuch czynników MFA, konwencja `_Krok` w praktyce, brute-force i blokady, rodziny sesji i wykrywanie kradzieży refresh tokenu, JWT plus JWKS |
| 3 | **Wrzucenie mema** | droga pliku binarnego, trzy adaptery `ObjectStore`, deduplikacja po SHA-256, przekodowanie WebP, tagi |
| 4 | **Komentarz i głos** | jedyne dwa **zadeklarowane wyjątki** od prawa idempotencji z ADR 0006 |
| 5 | **Kaskada `MEME_DELETED`** | trzeci wzorzec spójności: sprzątanie best-effort **bez** sagi, i dlaczego tu wolno inaczej |
| 6 | **Obserwowalność** | jak przejść jeden `cid` przez cztery serwisy w logach i trace'ach; co mierzą ręczne eksportery Prometheusa |

---

## 20. Errata — co zmienił przegląd P18 (2026-07-30)

Tego samego dnia, po napisaniu etapu 1, kod przeszedł osobny przegląd nastawiony na **defekty**
(9 agentów szukających + 9 adwersaryjnych weryfikatorów; pełna lista w `portal/PLAN-P18.md`).
Wynik dla tego podręcznika jest taki: **szkielet się nie zmienia, trzy miejsca wymagają korekty.**

**Co zostaje bez zmian** (§1–3, §5–7, §9–11, §15–19): opis sagi, transactional outboxu, zatrzasku
jednorazowego, kworum i werdyktu, arytmetyki timeoutów, ADR-ów i wdrożenia jest zgodny z kodem.
Żadne znalezisko nie podważyło **mechaniki** opisanej w tym dokumencie. To ważne przy nauce:
uczysz się poprawnego modelu systemu, tylko w trzech miejscach model działa gorzej, niż kod obiecuje.

**WAŻNE, jeśli czytasz to później:** wszystkie defekty z tabeli poniżej zostały **naprawione tego
samego dnia** (41 pozycji z PLAN-P18, w tym cztery krytyczne). Zostawiam je opisane, bo do nauki
systemu warto wiedzieć, **jak wyglądała wada i czym różni się od poprawki** — a nie dlatego, że
kod nadal tak działa. Przy każdej pozycji piszę, co jest teraz.

**Co się zmienia:**

| Gdzie | Korekta |
|---|---|
| **§4 (step-up)** | Cytowane zdanie o złodzieju sesji jest **nieprawdziwe**. `StepUpGuard` używa nazwy akcji **tylko** do zbudowania odpowiedzi 403, a elewacja jest kluczowana **samym tokenem** — więc elewacja zdobyta pod dowolną tanią (albo nieznaną) akcją odblokowuje `/account/delete`. Dla nieznanej akcji polityka to `SECOND_FACTORS`, przy którym hasło **nie jest weryfikowane**, a konto bez zapisanych czynników dostaje elewację natychmiast. Skutek: **skradziony access token wystarcza, by usunąć konto i całą treść** — bez znajomości hasła. Sprawdziłem to ręcznie, nie tylko przez agenta. **NAPRAWIONE:** elewacja jest teraz kluczowana parą token+akcja, nieznana akcja dostaje najostrzejsze wymaganie (fail-closed), a konto bez czynników musi podać hasło zamiast dostawać elewację milcząco. Zdanie z javadoca stało się prawdziwe — ale dopiero po naprawie, nie w dniu, w którym je napisano. |
| **§4, punkt „trzy rzeczy, które zaskakują"** | Dochodzi czwarta i najważniejsza: **trzy z czterech wrażliwych endpointów nigdy nie zostały za tę bramkę wpięte.** `grep requireElevation` daje dwa trafienia (usunięcie konta, reset czynników przez admina). Zapis i usunięcie czynnika MFA oraz zmiana hasła są chronione samą żywą sesją — a `FactorsController` bierze `target` czynnika **z ciała żądania**, więc skradziona sesja wstawia ofierze drugi czynnik na adres napastnika. **NAPRAWIONE:** zapis i usunięcie czynnika stoją za step-upem, a kod `EMAIL_CODE` idzie wyłącznie na własny, już zweryfikowany adres wołającego. Ta naprawa zmieniła też przepływ w interfejsie — pierwszy czynnik potwierdza się hasłem — co złapał dopiero test przeglądarkowy, nie build. |
| **§8.4 i §12.2 (przeciwne polityki retry)** | Nazwałem to „nazwanym kompromisem". To **defekt**: scenariusz utraty danych w `user-collections` jest osiągalny, a u koordynatora istnieje drugi, niezależny mechanizm o tym samym skutku (przeterminowanie liczone od `created_at`). Patrz korekta w §8.4. **NAPRAWIONE:** wszyscy trzej uczestnicy mają teraz ten sam budżet 90 s, a zamiatacz liczy przeterminowanie od `updated_at`, czyli od OSTATNIEJ dostarczonej próby — dzięki czemu kompensacja nie wyprzedza już uczestnika, który wciąż pracuje. |
| **§13 (co zostaje po użytkowniku)** | Tabela jest poprawna, ale **za łagodna**. Dochodzą dwie pozycje: (1) `password_resets` **nie ma kolumny czasowej**, więc token resetu nie wygasa nigdy i przeżywa konto — po ponownej rejestracji tego adresu przez inną osobę stary link **przejmuje jej konto**; (2) zmiana adresu e-mail **gubi całe MFA** (czynniki, kody odzyskiwania, flagę passwordless są kluczowane adresem i nikt ich nie przenosi), a dla konta federacyjnego **na zawsze blokuje usunięcie siebie**. **NAPRAWIONE:** porty dostały operacje przeniesienia i czyszczenia, `password_resets` kolumnę czasową, a rodzinę zamyka generyczny `AddressKeyedStoresTest` — wylicza osiem magazynów kluczowanych adresem i egzekwuje dwa prawa: dane idą za kontem, nic nie zostaje po usunięciu. |
| **§14 (czym to jest udowodnione)** | Tabela „co zepsute → co wyłapie" miała **dwie luki, obie już zamknięte tego samego dnia** — opis niżej. |

### §14 po naprawach — co się zmieniło w bramkach

Errata w pierwszej wersji mówiła, że **push do ośmiu bibliotek w `shared` nie uruchamia nigdzie ani
jednego testu**. To była prawda przez kilka godzin. Naprawy z tej samej rundy zmieniły dwa wiersze
tabeli z §14:

| Co zepsute | Co wyłapie — **stan aktualny** |
|---|---|
| regresja w bibliotece `shared` bez własnego CI (`voting`, `config`, `password`, `email`, `constraint`, `test-starter`, `adjustable-clock`, `infrastructure-micronaut-clock`) | **nocny reaktor agregatora** (`schedule` w CI `shared`). Gwarancja jest **dzienna, nie per commit** — wybrano ten wariant, bo 6 z 8 bibliotek nie zbuduje się samodzielnie: zależą od siostrzanych SNAPSHOT-ów, które istnieją tylko w reaktorze |
| zmiana kształtu paktu konsumenckiego portal→security | **portal CI, per PR** — nowy krok uruchamia testy providerskie w `security` na paktach właśnie zregenerowanych przez ten PR. Wcześniej te dwa pakty nie były weryfikowane w **żadnym** CI: `shared` nie klonuje portalu, więc `@EnabledIf` pomijał je i tam |

Do tabeli dochodzi też **nowy wiersz**, którego w etapie 1 nie było, bo mechanizm wtedy nie istniał:

| Co zepsute | Co wyłapie |
|---|---|
| przestarzały obraz kontenera albo pin akcji GitHuba | **Dependabot** — od 2026-07-30 pilnuje ekosystemów `docker` i `github-actions`. Wcześniej **nie pilnował ich nikt**, i to dlatego cały stos kontenerów stał na wersjach z połowy 2024, podczas gdy zależności Mavena były bieżące. Nie obejmuje tagów w `Testcontainers` (są w kodzie Javy) ani plików compose o niestandardowych nazwach w `shared` |

**Morał do zapamiętania — ten sam, który ten podręcznik powtarza w §16:** javadoc jest świadkiem,
nie źródłem prawdy. Cztery z powyższych korekt to miejsca, w których **komentarz opisywał ochronę,
której kod nie realizuje**. Przy nauce tego systemu warto to traktować jako regułę: jeśli javadoc
mówi „X nie może się zdarzyć", sprawdź w kodzie, **co konkretnie** temu zapobiega.

### Jedna zmiana w mechanice, o której warto wiedzieć przy §4

Poza naprawami powyżej doszła jedna rzecz, która **zmienia model**, a nie tylko go poprawia:
licznik nieudanych logowań przestał być kluczowany samym adresem IP.

Wada wyszła bocznymi drzwiami. Naprawa jednej z pozycji P18 zamknęła realną lukę — udane logowanie
czyściło rekord całego adresu, więc jedno znane dobre hasło było przyciskiem RESET dla każdego konta
za tym adresem — ale zrobiła to przez wycięcie czyszczenia w całości. Skutek: trzy pomyłki na
piętnaście minut blokowały wszystkich za jednym adresem. Pierwszą ofiarą nie był napastnik, tylko
**własna suita testowa tego projektu**, która zablokowała samą siebie w połowie przebiegu.

Teraz próby liczone są na **parę (źródło, konto)**, z drugim, znacznie wyższym licznikiem per adres,
który łapie rozsiewanie po wielu kontach. Konto zapisywane jest jako odcisk z sekretem serwera, nie
jako adres — do liczenia wystarczy równość, a adres przy każdej nieudanej próbie byłby rejestrem do
enumeracji. Warto zapamiętać kształt tej lekcji: **naprawa luki potrafi otworzyć drugą, jeśli
zamyka się ją przez usunięcie mechanizmu zamiast przez zawężenie go.**

**I morał drugi, świeższy:** ta errata zdążyła się zestarzeć **w ciągu jednego dnia**, bo opisywała
stan bramek, a bramki naprawiliśmy. Dokument o systemie żyjącym starzeje się szybciej niż sam system
— dlatego w §14 warto ufać tabeli, a nie zapamiętanemu zdaniu.

---

*Etap 1 opracowany 2026-07-30 na podstawie skanu kodu w `` i `../shared` (14 równoległych
agentów: warstwy `domain`, `config`, `system`, `application` czytane modelem Opus, warstwy
`persistence`/`infrastructure`, UI, testy i wdrożenie — modelem Fable 5). Wszystkie twierdzenia
przeszły niezależną weryfikację wobec kodu; próbka sprawdzona dodatkowo ręcznie. Jeden szczegół
zgłoszony przez agenta okazał się błędny i został poprawiony: `microservice-offboarding` stoi na
**Helidonie 4 SE**, nie na Quarkusie.*
