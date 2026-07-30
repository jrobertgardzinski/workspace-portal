# Plan pracy po przeglądzie P18 — 2026-07-30

> ## STAN REALIZACJI — czytaj to najpierw
>
> Przegląd: ultracode, 9 agentów szukających + 9 adwersaryjnych weryfikatorów.
> **Podział modeli wg polecenia Roberta:** warstwy `domain`, `config`, `system`, `application`
> czytane **Opusem**, warstwy `persistence`/`infrastructure`, biblioteki, fronty i wdrożenie —
> **Fable 5**.
> Materiał: **cały kod obu repozytoriów**, nie tylko wczorajsze diffy (poprzednie rundy P16/P17
> trzepały wyłącznie własne poprawki). To pierwszy przegląd całości od P15.
>
> | | |
> |---|---|
> | ZROBIONE | **41 z 41 — PACZKA ZAMKNIĘTA.** Fala 1 (17 pozycji, Fable 5) + Fala 2 (20 pozycji, Opus 5) + 4 moje (37, 38, 39, 40, 41 — czyli 5, z czego 37/40/41 poza falami). **Wszystkie 4 krytyczne naprawione.** Zero pozycji obalonych w Fali 2, ale patrz zastrzeżenie o weryfikacji niżej. |
> | Buildy (uruchomione PRZEZE MNIE, nie przez agentów) | security **283** testów / 0 porażek · memes **232** / 0 · offboarding **140** / 0 · collections **128** / 0 · email **46** / 0 · transactional-outbox BUILD SUCCESS · memes-ui **13** / 0 · comments 133 / 0 (Fala 1) |
> | Migracje | **22 migracje security zaaplikowane na prawdziwym Postgresie 16** (V19–V22 są nowe). V22 przetestowana na ZASIANYCH duplikatach: starszą sagę zamyka jako COMPENSATED, nowszą zostawia STARTED, a indeks unikalny odrzuca kolejną próbę. |
> | Znaleziska | 47 zgłoszonych → **41 pozycji** po scaleniu duplikatów (5 defektów znalazło niezależnie dwóch agentów) |
> | Wagi | **4 KRYTYCZNE (poz. 1–4), 18 WYSOKICH (5–22), 14 ŚREDNICH (23–36), 5 NISKICH (37–41)** |
> | Podział pracy | Pierwotnie (2026-07-30): **Fable 5** poz. 1–36, **Opus 5** poz. 37–41. **ZMIANA w trakcie:** Robertowi kończy się usage Fable'a, więc **od Fali 2 wszystko robi Opus 5**. Fala 1 (17 pozycji) była już zrobiona Fable'em. |
> | Blokuje wdrożenie na k3s | **TAK — paczka A.** Pozycje 1 i 2 to obejście step-upu przy pomocy samego skradzionego access tokenu; skutkiem pozycji 1 jest nieodwracalne usunięcie konta i treści. Publiczne demo z kodem QR nie powinno wystać z tym w sieci. |
>
> ### Uczciwie o weryfikacji — przeczytaj, zanim zaufasz tej liście
>
> Weryfikatorzy zwrócili **42 POTWIERDZONE, 4 z obniżoną wagą i ZERO obalonych**. Zero obaleń na
> 47 znalezisk to wynik, któremu **sam nie ufam** — to dokładnie ta klasa „bezbłędnej" weryfikacji,
> która w P17 okazała się fałszywym świadkiem. Dlatego:
>
> - **Pozycje 1, 2, 5 i 18 sprawdziłem ręcznie, czytając kod** (`StepUpGuard:27-34`,
>   `InMemorySessionElevation:21-40`, `StepUp:58-76`, `StepUpPolicy:23`, `BeanFactory:325-328`,
>   `grep requireElevation` → 2 trafienia, `FactorsController` bez `StepUpGuard`,
>   `_CleanBruteForceRecords:17-20` + `Authentication:58`, brak `auto-offset-reset` w memes/comments).
>   Te cztery **potwierdzam z pierwszej ręki**.
> - Pozostałe **37 pozycji ma jedynie werdykt agenta**. Przed naprawą każdej: otwórz plik i sprawdź
>   sam. Traktuj tę listę jako listę **tropów o wysokim prawdopodobieństwie**, nie jako wyrok.
> - Jeden defekt zgłoszony w P17 jako naprawiony (`isPaused`) wraca tu w innym miejscu (poz. 30) —
>   naprawa heartbeatu poszła do comments i nie poszła do memes.
>
> ### Zasady pracy nad tymi poprawkami
> - **Najmniejsza zmiana zamykająca wadę.** Żadnych przepisywań przy okazji.
> - **Test musi umieć paść:** po napisaniu cofnąć poprawkę i zobaczyć czerwień. Cofnięcie
>   chirurgiczne, nie `git checkout` — błąd kompilacji nie dowodzi niczego o zachowaniu (lekcja P17).
> - Po każdej pozycji pełny `mvn verify` w dotkniętym repo, dopiero potem dalej.
> - Paczka A **przed** czymkolwiek innym i przed k3s.
> - Fable robi przegląd i weryfikację, Opus pisze kod — implementacja w głównej pętli.

---

**Podsumowanie.** Cztery rodziny wad, a każda ma ten sam kształt: **mechanizm ochronny istnieje,
jest opisany w javadocu jako działający, i nie działa**.

**Rodzina pierwsza — step-up jest fikcją.** `StepUpGuard.requireElevation(request, action)` używa
parametru `action` **wyłącznie do zbudowania treści odpowiedzi 403**; jedynym sprawdzeniem jest
`elevation.consume(token)`, a mapa elewacji jest kluczowana **samym tokenem**. Elewacja zdobyta pod
dowolną tanią akcją odblokowuje więc `/account/delete`. Gorzej: `StepUpPolicy.requirementFor` dla
akcji **nieznanej** zwraca `SECOND_FACTORS` (nie najostrzejsze), przy `SECOND_FACTORS` hasło **nie
jest weryfikowane**, a dla konta bez zapisanych czynników `StepUp.start` podnosi uprawnienie
natychmiast. Wynik: mając **tylko skradziony access token**, bez hasła, można w dwóch żądaniach
skasować konto ofiary razem z całą jej treścią. Javadoc `SessionElevation` twierdzi dokładnie
odwrotnie: *„a stolen live session cannot quietly delete an account: the thief would have to pass
the step-up too"*. Do tego trzy z czterech wrażliwych endpointów nigdy nie zostały za tę bramkę
wpięte, mimo że `docs/mfa-design.md` ogłasza fazę E jako DONE — a `FactorsController` przyjmuje
`target` czynnika **z ciała żądania**, więc skradziona sesja wstawia ofierze drugi czynnik na adres
napastnika i zamyka ją poza własnym kontem.

**Rodzina druga — tożsamość jest kluczowana adresem e-mail, a adres jest zmienny.** `User` ma
niezmienne `id`, ale `enrolled_factors`, `recovery_codes`, `passwordless_accounts`,
`email_verifications`, `password_resets` i `email_changes` są kluczowane kolumną `user_email`, bez
ani jednego klucza obcego. Skutki: zmiana adresu **po cichu gubi całe MFA** (dla konta
federacyjnego dodatkowo **na zawsze blokuje usunięcie konta**), a usunięcie konta zostawia w bazie
niewygasający token resetu hasła, który po ponownej rejestracji tego adresu **przejmuje konto nowej
osoby**. Ta sama wada w warstwie sagi: security dopasowuje werdykt portalu **po adresie**, więc
spóźniony werdykt starej sprawy kompensuje nowszą.

**Rodzina trzecia — zegary sagi rozjechane między repozytoriami.** Zamiatacz liczy przeterminowanie
od `created_at`, więc po przekroczeniu progu ponawia na **każdym** przebiegu (co 15 s) i kapituluje
~45 s po progu — podczas gdy ostatni re-rozkaz ma u uczestnika jeszcze ~120 s żywego budżetu.
A `user-collections` nie ma budżetu wcale i ponawia w nieskończoność. Oba przypadki dają ten sam
skutek: **treść wyczyszczona po tym, jak saga oddała konto właścicielowi** — czyli katastrofę,
którą `AccountDeletionOrchestrator` już umie tylko *zalogować*.

**Rodzina czwarta — naprawa trafiła do jednego z bliźniaków.** `POST /logout` z P12 dostała tylko
`security-ui`; stemplowanie heartbeatu per rekord — tylko comments; budżet retry — tylko
memes/comments; `auto-offset-reset=earliest` — tylko collections. Cztery razy ten sam wzorzec:
poprawka zrobiona w serwisie, w którym defekt znaleziono, i niezaniesiona do identycznego kodu obok.

Poza rodzinami: jeden trwale niepublikowalny wiersz **blokuje cały outbox security na zawsze**
(a rozmiar payloadu jest pod kontrolą użytkownika), `microservice-email` **potwierdza rekord także
gdy mail nie został ani wysłany, ani zaparkowany**, biblioteka outboxu **nadal zatruwa wiersze przy
długim outage'u brokera** wbrew własnemu javadocowi, a pięć map w pamięci nie ma żadnej eksmisji —
w tym jedna zasilana **anonimowym, nielimitowanym GET-em**.

---

## Kolejność wykonania

| Paczka | Właściciel | Co | Pozycje | Dlaczego w tej kolejności |
|---|---|---|---|---|
| **A** | **Fable 5** | Bramki uwierzytelniania: step-up i brute-force | 1, 2, 5, 6, 22, 23 | **Blokuje k3s.** Skradziony access token = usunięte konto z całą treścią. Wszystkie sześć pozycji dotyka tych samych czterech klas, więc rozdzielanie ich to podwójna praca. |
| **B** | **Fable 5** | Klucz tożsamości i RODO | 3, 4, 9, 10, 21, 24, 27, 36 | Cicha utrata MFA i przejęcie konta przez stary token resetu. Wspólne źródło: wszystko kluczowane zmiennym `user_email` bez kluczy obcych. |
| **C** | **Fable 5** | Utrata treści w sadze | 11, 12, 13, 19, 26, 29, 35 | Treść czyszczona po tym, jak saga oddała konto. Wymaga zmian w **dwóch repozytoriach naraz** (portal + shared) — robić jedną paczką, inaczej arytmetyka timeoutów rozjedzie się jeszcze bardziej. |
| **D** | **Fable 5** | Bliźniaki, transport, bramki CI | 7, 8, 14, 15, 16, 17, 18, 20, 25, 28, 30, 31, 32, 33, 34 | Najtańszy zwrot: w pięciu pozycjach naprawa **już istnieje** w bliźniaczym serwisie i trzeba ją tylko przenieść. |
| **E** | **Opus 5** | Niskie | 37, 38, 39, 40, 41 | Po A–D, bez pośpiechu. Ślad korelacyjny, dane osobowe w kluczu Kafki, próg `KEEP_POPULAR`, komunikaty błędów w UI, martwa instrukcja w walidacji. |

**Kontrola kompletności:** A=6, B=8, C=7, D=15, E=5 → **41 pozycji, każda ma dokładnie jedną
paczkę i jednego właściciela.** Fable 5 bierze 36 pozycji (poz. 1–36), Opus 5 bierze 5 (poz. 37–41).

---

## Stan Fali 1 (2026-07-30)

| Zestaw | Pozycje | Kto | Stan | Dowód — sprawdzony przeze mnie NIEZALEŻNIE |
|---|---|---|---|---|
| **CS13 (część)** | 37 | Opus 5 | ZROBIONE | `mvn clean verify` w offboardingu: 126 testów, 0 porażek. Test sprawdzony chirurgicznym revertem (czerwień z właściwego powodu). |
| **CS5** | 30, 31 | Fable 5 | ZROBIONE | Uruchomiłem sam: memes `KafkaConsumerOffsetResetTest` 1/1, `ListenerHeartbeatTest` **3/3** (1 stary idle + 2 nowe interceptora); comments `KafkaConsumerOffsetResetTest` 2/2. Właściwość `auto-offset-reset=earliest` potwierdzona w obu plikach. |
| **CS9** | 16, 32 | Fable 5 | ZROBIONE | Uruchomiłem sam `mvn clean verify` w `transactional-outbox`: **BUILD SUCCESS**. Diff przeczytany: `catch (RuntimeException)` zwraca teraz `TIMED_OUT`, spóźnione potwierdzenie wchodzi przez istniejącą kolejkę `confirmedAwaitingMark` (bez JDBC na wątku producenta — zgodnie z regułą biblioteki). |
| **CS11** | 7, 8 | Fable 5 | ZROBIONE | Uruchomiłem sam: memes-ui 7/7, security-ui **12/12**. Diff przeczytany: nagłówek `Authorization` dodany w `submitDeleteCode`. |
| **CS12** | 20, 34 | Fable 5 | ZROBIONE | Diff obu `ci.yml` przeczytany. **Wybrano wariant B** (schedule w CI agregatora, nie 8 osobnych workflow) z powodem, którego mój plan nie przewidział: 6 z 8 bibliotek **nie zbuduje się samodzielnie**, bo zależy od siostrzanych SNAPSHOT-ów istniejących tylko w reaktorze — osobne `mvn verify` byłoby czerwone od pierwszego dnia z niewłaściwego powodu. |
| **CS1** | 1, 2, 5, 6, 14, 17, 22, 23 | Fable 5 | ZROBIONE | Build agenta: **237 testów** w modułach security, 0 porażek. **Moja weryfikacja niezależna:** (a) `StepUpHttpTest` 4/4, `AdminFactorResetHttpTest` 1/1, `InMemorySessionElevationTest` 2/2, `MfaHttpTest` 5/5, `StepUpThrottleHttpTest` 1/1; (b) **własny chirurgiczny revert** klucza elewacji na sam token → **3 testy czerwone**, w tym `a_stolen_token_cannot_delete_via_a_cheap_or_unknown_action` (i po przywróceniu znów zielone); (c) **luka zgłoszona przez agenta zamknięta przeze mnie**: wszystkie **19 migracji zaaplikowane na prawdziwym Postgresie 16** — V19 przechodzi, `failed_at TIMESTAMP` istnieje, indeks częściowy odtworzony z **oboma** predykatami (`published_at IS NULL AND failed_at IS NULL`). |

**Uwaga do poz. 2 (sprawdzona, nie jest luką):** `target` z ciała żądania jest nadal przyjmowany dla
typów **innych** niż `EMAIL_CODE` — a `SMS_CODE` istnieje (czynniki kodowe powstają per kanał, kanał SMS
jest wpięty), więc na pierwszy rzut oka ta sama sztuczka szłaby przez SMS. **Nie szłaby:** `enroll/start`
stoi teraz za step-upem z polityką `FULL_CHAIN`, czyli wymaga hasła. Kto ma hasło i token, ma konto
i tak. Wymuszenie własnego adresu dla `EMAIL_CODE` to obrona w głąb, nie jedyna bariera.

**Dwa fałszywe alarmy — oba moje, oba z tego samego powodu.** (1) Test `security-ui` czerwony, bo
sprawdzałem plik w trakcie edycji przez CS1. (2) **11 z 13 testów step-upu na `INTERNAL_SERVER_ERROR`**,
bo uruchomiłem `-pl security-infrastructure` **bez `-am`** — runtime wziął starą wersję `security-system`
z `~/.m2`, gdzie `consume` ma jeden argument. Z `-am` wszystko zielone. Wniosek na przyszłość, ten sam
oba razy: **weryfikacja musi odtwarzać sposób budowania i uruchamiania, inaczej sprawdza własną
pomyłkę** — dokładnie ta lekcja, którą projekt ma już zapisaną w `feedback` o zielonym buildzie
i martwym serwisie.

**Niespodzianka przy poz. 34 (wartościowa):** było **gorzej**, niż mówił plan. Pakty portal→security nie
były weryfikowane w **żadnym** CI (nie „dopiero w CI drugiego repo"): `shared` CI nie klonuje portalu,
więc `@EnabledIf` pomijał testy providerskie także tam. Jedynym miejscem weryfikacji był dysk
dewelopera. Naprawa okazała się tańsza niż zakładałem — sąsiad już jest klonowany w portal CI,
brakowało **jednego kroku** (~14 s).

**Lekcja operacyjna z tej fali (mój błąd, nie agenta):** weryfikowałem `security-ui` w chwili, gdy CS1
edytował ten sam plik — dostałem czerwony test i przez moment wyglądało to na fałszywy raport CS11.
Po zakończeniu edycji ten sam test daje 12/12. **Nie weryfikować repozytorium, w którym równolegle
pisze agent** — to ta sama zasada „równoległość tylko między repozytoriami", tylko zapomniana po
stronie sprawdzania.

---

## Zoptymalizowany przebieg wykonania

Paczki A–E mówią **co i w jakiej kolejności pod względem ryzyka**. Ta sekcja mówi, **jak to wykonać
najtaniej** — bo naiwne „41 pozycji, każda osobno" to 41 przebiegów `mvn verify` i kilkanaście
kolizji w tych samych plikach.

### Fakt, który wyznacza kształt przebiegu

Rozkład pozycji po modułach (liczone z pola „Plik" każdego znaleziska):

| Moduł | Pozycji |
|---|---|
| `shared/microservice-security` | **25** |
| `portal/microservice-memes` | 7 |
| `portal/microservice-offboarding` | 6 |
| `portal/microservice-user-collections` | 3 |
| `shared/transactional-outbox` | 2 |
| `portal/.github` (bramki CI) | 2 |
| `shared/microservice-email` | 1 |
| `portal/microservice-comments` | 1 |

**61% pracy siedzi w jednym module.** Wniosek: **zrównoleglanie paczek A i B nie ma sensu** —
obie grzebią w tych samych plikach (`BeanFactory`, `DeleteAccountController`), więc dwóch agentów
naraz w security to konflikty i podwójne przebiegi testów. Prawdziwa równoległość jest **między
repozytoriami**, nie między paczkami.

### Zasada 1: łączyć pozycje dzielące plik w JEDEN zestaw zmian

Pozycje, które **muszą** wejść razem, bo inaczej ten sam plik jest edytowany dwa razy i dwa razy
weryfikowany:

| Zestaw | Pozycje | Wspólny plik / powód |
|---|---|---|
| **CS1** | 1, 2, 5, 6, 22, 23, **14**, **17** | `StepUpGuard`, `StepUp`, `SessionElevation`, `StepUpPolicy`, `BeanFactory` — plus 14, bo zmiana sygnatury `requireElevation` i tak przepisuje `DeleteAccountController`, gdzie 14 dokłada walidację `PurgeChoices`; plus 17, bo eksmisja wygasłych wpisów dotyczy m.in. `InMemorySessionElevation` i `InMemoryStepUpStore`, które ten zestaw i tak edytuje (dochodzą `OauthFlowStore`, `InMemoryPendingAuthenticationStore`, `SourceThrottle`) |
| **CS2** | 3, 4 | Oba dodają operacje (`reassign`/`purge`) do **tych samych** portów i ich dwóch adapterów. Rozdzielone = dwukrotna zmiana tych samych interfejsów, migracji i wiringu w `BeanFactory` |
| **CS3** | 9, 21, 10, 24, 27, 36 | Ścieżki hasła/sesji + dwa reapery o **identycznym wzorcu** (retencja z połkniętym wyjątkiem) + higiena danych osobowych w logach i URL-ach |
| **CS4** | 25, 26 | Semantyka `save` i rozjazd dublera in-memory — oba w `persistence`, jedna decyzja o ujednoliceniu |
| **CS5** | 30, 31 | Ta sama zmiana konfiguracji konsumenta w memes i comments (przeniesienie z bliźniaka) |
| **CS6** | 11, 13, 29, 33, 35 | Wszystko w `offboarding` (`JdbcSagaStore` + `EventsRouter`), jedna baza, jeden build |
| **CS7** | 18, 19, 28 | Wszystko w `memes` (`PurgeUserContent`, `ObjectStore`, migracja FK) |
| **CS8** | 12 | `collections` — osobno, bo trzeba przenieść budżet retry i dopisać metrykę |
| **CS9** | 16, 32 | `transactional-outbox` — dwie zmiany w `OutboxPublisher`, jeden szybki build (sekundy) |
| **CS10** | 15 | `microservice-email` |
| **CS11** | 7, 8 | Fronty: logout w memes-ui/collections-ui + nagłówek w security-ui (build npm, nie JVM) |
| **CS12** | 20, 34 | Bramki CI — zero kodu produkcyjnego, można wypuścić kiedykolwiek |
| **CS13** | 37, 38, 39, 40, 41 | **Opus 5** — pozycje niskie, patrz zasada 3 |

**41 pozycji → 13 zestawów zmian → ~9 przebiegów `mvn verify`** (security 4, offboarding 1,
memes 1, collections 1, outbox 1, email 1; fronty i CI bez `mvn`). Zamiast 41.

**Kontrola pokrycia:** CS1=8, CS2=2, CS3=6, CS4=2, CS5=2, CS6=5, CS7=3, CS8=1, CS9=2, CS10=1,
CS11=2, CS12=2, CS13=5 → **41. Każda pozycja w dokładnie jednym zestawie.** (Przy pierwszym
spisaniu tej tabeli zgubiłem poz. 17 — kontrola sumy ją wyłapała; warto ją powtórzyć po każdej
zmianie składu zestawów.)

### Zasada 2: równolegle tylko między repozytoriami

```
FALA 1   Tor S (Fable, sekwencyjnie w security)   │  Tor P (Fable, worktree, portal+libs)
         CS1  ← blokuje k3s, największe ryzyko    │  CS5, CS9, CS11, CS12
                                                  │  (przeniesienia z bliźniaka + libs + fronty + CI)
FALA 2   CS2, potem CS3                           │  CS6, potem CS7, CS8
FALA 3   CS4                                      │  CS10
FALA 4   —                                        │  CS13 (Opus 5)
```

Tor S **musi** być sekwencyjny (jeden moduł, wspólne pliki). Tor P jeździ w `isolation: 'worktree'`,
bo to osobne repozytoria i osobne buildy — tam równoległość jest darmowa.

### Zasada 3: dwie kolizje między właścicielami — nazwane, nie odkryte w trakcie

Podział „wysokie i średnie do Fable'a, niskie do Opusa" **przecina dwa pliki**:

| Plik | Fable | Opus | Rozwiązanie |
|---|---|---|---|
| `collections/.../PurgeCommandsConsumer.java` | poz. **12** (budżet retry, linia 338) | poz. **38** (klucz Kafki, linie 407-408) | Opus **po** wejściu CS8 |
| `memes/.../PurgeUserContent.java` | poz. **18** (polityka z kreatora, linia 40) | poz. **39** (próg `KEEP_POPULAR`, linie 41-52) | Opus **po** wejściu CS7 |

Pozostałe trzy moje pozycje (37 w `KafkaLoop`, 40 w `DeleteAccountDialog.tsx`, 41 w
`OffboardingListenerHealth`) nie kolidują z niczym i mogą wejść od razu, równolegle z Falą 1.

### Zasada 4: weryfikacja przy naprawie, nie w osobnej rundzie

37 z 41 pozycji ma tylko werdykt agenta. **Nie robimy z tego osobnego przebiegu weryfikacyjnego** —
byłby to drugi raz to samo czytanie tych samych plików. Zamiast tego: agent naprawiający otwiera
plik, **potwierdza scenariusz awarii albo zgłasza obalenie i pomija pozycję**, i dopiero wtedy
edytuje. Obalona pozycja wraca do planu z adnotacją, nie do naprawy. To oszczędza całą rundę,
a jakość jest ta sama, bo weryfikacja jest darmowa dla kogoś, kto i tak ma plik otwarty.

### Zasada 5: gdzie test asertuje wadę — najpierw przepisać test na czerwono

Pozycje **1, 2, 11, 12** mają test, który przypina obecne (wadliwe) zachowanie. Kolejność w tych
czterech przypadkach jest odwrotna niż zwykle:
1. przepisać test tak, żeby opisywał zachowanie POPRAWNE → **czerwony**,
2. dopiero wtedy naprawić kod → zielony.

Nie „naprawić i dopasować test", bo wtedy nie wiadomo, czy test jeszcze cokolwiek pilnuje.
Dotyczy: `AdminFactorResetHttpTest:69-70`, `StepUpHttpTest` (helper `enrolEmailFactor`),
`JdbcSagaStoreTest.the_sweep_retries_before_capitulating`, `PurgeCommandsConsumerLoopTest:106`.

### Czego w tym przebiegu NIE robimy

- **Nie ruszamy przekluczowania tabel na `users.id`** (strukturalne rozwiązanie poz. 3 i 4). To
  osobna decyzja i osobna paczka — tu zamykamy wadę operacjami przeniesienia, bo są odwracalne
  i nie wymagają migracji danych produkcyjnych.
- **Nie dodajemy DLQ** nigdzie. Świadomy kompromis projektu (patrz „Czego celowo NIE raportuję").
- **Nie dotykamy `e2e-saga-outage.sh`** ani nie automatyzujemy go w CI — po naprawach poz. 11 i 12
  jego arytmetyka i tak wymaga przeliczenia; to zadanie po Fali 2, nie w jej trakcie.

## KRYTYCZNY

### 1. Elewacja step-up nie jest wiązana z akcją, a nieznana akcja dostaje `SECOND_FACTORS` bez weryfikacji hasła — skradziony access token wystarcza, by usunąć konto i całą treść użytkownika
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/StepUpGuard.java:27-34`
**Także:** `StepUp.java:58-76` (`elevate(accessToken)` bez akcji), `InMemorySessionElevation.java:21,33,38` (mapa kluczowana tokenem), `StepUpPolicy.java:23` (`getOrDefault(action, SECOND_FACTORS)`), `BeanFactory.java:325-328` (mapa zna tylko `delete-account` i `change-password`), `StepUpController.java:38` (akcja z ciała, bez whitelisty), `DeleteAccountController.java:45`
**Co robi kod (sprawdzone ręcznie):** `requireElevation` przyjmuje `action`, ale używa go tylko w ciele 403; test to `elevation.consume(token)`. `StepUp.start` czyta politykę per akcja, a zapisuje elewację ogólnego przeznaczenia. Dla nieznanej akcji polityka to `SECOND_FACTORS`, a gałąź weryfikacji hasła wymaga `FULL_CHAIN` (`StepUp.java:64`), więc hasło nie jest sprawdzane. Przy `factors.findByUser(email).isEmpty()` (stan legalny — podłoga USER to 1, a hasło liczy się jako czynnik) `StepUp.java:69-71` podnosi uprawnienie natychmiast.
**Awaria:** Napastnik ma wyłącznie skradziony access token (XSS, wspólny komputer), nie zna hasła. (1) `POST /account/delete` → 403. (2) `POST /account/step-up {"action":"cokolwiek"}` → `SECOND_FACTORS` → hasło pominięte → brak czynników → **200 ELEVATED**. (3) `POST /account/delete` → 202: sesje unieważnione, konto zablokowane, rozkazy purge wysłane do memes/comments/collections → **nieodwracalna utrata konta i treści**. Wariant windujący dla konta z czynnikiem: elewacja z taniej akcji `admin-reset` również przechodzi na `/account/delete`, a `admin-reset` dla bootstrap-admina bez czynników nie wymaga niczego (`MfaCompliance:47` daje mu na to łaskę) — czyli `PUT /admin/users/{ofiara}/factors/reset` też stoi za pustą bramką.
**Najmniejsza naprawa:** `SessionElevation.elevate(token, action)` / `consume(token, action)` (klucz = token + akcja), akcja przekazywana z `StepUp.start`. `requirementFor` dla nieznanej akcji → `FULL_CHAIN`. Jawny wpis `admin-reset: FULL_CHAIN` w `BeanFactory`. Przy `enrolled.isEmpty()` i wymaganiu innym niż `NONE` **nie podnosić milcząco** — wymagać hasła. Test: elewacja z `action=admin-reset` musi dać 403 na `/account/delete`.
**Uwaga:** `AdminFactorResetHttpTest.java:69-70` **asertuje obecne zachowanie** („bootstrap admin has no factors → step-up elevates on the start alone"), więc ten test trzeba świadomie przepisać, a nie „naprawić pod zielone".
**Wysiłek:** M

### 2. Zapis i usunięcie czynnika MFA nie są za step-upem, a `target` czynnika pochodzi od wołającego — skradziona sesja wstawia ofierze drugi czynnik na adres napastnika i zamyka ją poza kontem
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/FactorsController.java:69`
**Także:** `FactorsController.java:64,74,82` (brak `StepUpGuard` w całej klasie), `AuthorizationFilter.java:59-61` (`/account/factors` zwolnione z podłogi MFA), `EnrolFactor.java:43-44`, `ChangePasswordController.java:36`, `docs/mfa-design.md:150-151,217`
**Co robi kod (sprawdzone ręcznie):** `grep requireElevation` w całym module daje **dwa** trafienia: `DeleteAccountController` i `AdminFactorsController`. `FactorsController` nie wstrzykuje `StepUpGuard` wcale. `target` czynnika: `body.get("target") != null ? body.get("target") : caller.value()` — dowolny adres od wołającego, zapisywany jako `secretMaterial`, czyli **cel wysyłki kodu przy każdym późniejszym logowaniu**.
**Awaria:** Napastnik ze skradzionym tokenem: (1) `POST /account/factors/EMAIL_CODE/enroll/start {"target":"napastnik@evil.com"}` → kod idzie do niego; (2) `enroll/confirm` → czynnik zapisany z jego adresem; (3) `DELETE /account/factors/{stary}` przechodzi, bo `removalWouldBreakFloor` liczy hasło jako czynnik. Od tej chwili **każde** logowanie ofiary kończy się 202 z kodem wysłanym do napastnika: trwały lockout właściciela i stały drugi czynnik napastnika na cudzym koncie. Nic z tego nie wymagało hasła ofiary.
**Najmniejsza naprawa:** `stepUpGuard.requireElevation(request, "enrol-factor")` w `start`/`confirm`, `"remove-factor"` w `remove`, `"change-password"` w `ChangePasswordController`; dopisać te akcje do mapy w `BeanFactory.stepUpPolicy`. Niezależnie: dla `EMAIL_CODE` **nie przyjmować `target` z ciała** — adres konta jest już zweryfikowany.
**Uwaga:** `StepUpHttpTest` używa helpera `enrolEmailFactor(...)` **bez** step-upu, czyli zielony test korzysta z luki jako z udogodnienia. Naprawa zaczerwieni go i to jest poprawny wynik.
**Wysiłek:** M

### 3. Zmiana adresu e-mail porzuca cały stan MFA — czynniki, kody odzyskiwania i flagę passwordless; konto federacyjne traci przy tym możliwość usunięcia siebie na zawsze
**Plik:** `shared/microservice-security/security-system/src/main/java/com/jrobertgardzinski/security/system/account/ConfirmEmailChange.java:38-40`
**Także:** migracje `V11__enrolled_factors.sql`, `V12__passwordless_accounts.sql`, `V13__recovery_codes.sql` (klucz `user_email`, zero FK), `EnrolledFactorRepository.java:16-25`, `RecoveryCodeRepository.java:16-23`, `PasswordlessAccountRepository.java:13-15`
**Co robi kod:** `ConfirmEmailChange` przenosi **dwie** rzeczy: linki federacyjne (`relinkAll`) i wiersz `users` (`updateEmail`). Trzy pozostałe rodziny kluczowane adresem nie mają **żadnej** operacji przeniesienia w żadnym adapterze (`grep` po `moveAll|relinkAll` znajduje tylko federacyjną). Brak FK, więc brak `ON UPDATE CASCADE`. Javadoc klasy tłumaczy, dlaczego linki muszą iść za kontem — czyli problem był rozważony **dla jednej z czterech** takich tabel.
**Awaria:** Konto z TOTP i 10 kodami odzyskiwania zmienia adres. Po zmianie `findByUser(nowy)` zwraca pustą listę → `Authentication` wydaje sesję **po samym haśle**: drugi czynnik zniknął bez śladu i bez komunikatu, `MfaCompliance` uznaje konto za zgodne (podłoga USER = 1, hasło liczy się za czynnik). Kody odzyskiwania przestają działać. Sekret TOTP i hasze kodów zostają pod starym adresem **na zawsze** — a gdy ten adres zarejestruje ktoś inny, `Authentication` znajdzie dla niego czynniki poprzedniego właściciela. Dla konta **federacyjnego**: `passwordless_accounts` zostaje pod starym adresem, więc `isPasswordless(nowy)=false`, `StepUp` przy `FULL_CHAIN` żąda hasła, a hash jest losowy i nieweryfikowalny → **użytkownik nigdy nie usunie swojego konta**.
**Najmniejsza naprawa:** Operacja przeniesienia w trzech portach (`reassign(from,to)`), wołana w `ConfirmEmailChange` w tej samej transakcji co `updateEmail`; w adapterach JDBC przeliczyć sztuczne identyfikatory `email|type` / `email|hash`; dla `EMAIL_CODE` zaktualizować też `secret_material`, bo trzyma stary adres jako cel wysyłki. **Docelowo** (osobna decyzja, nie w tej paczce): przekluczować te tabele na `users.id` z FK — wtedy poz. 3 i 4 znikają strukturalnie.
**Wysiłek:** M

### 4. `DeleteAccount` nie czyści czterech tabel kluczowanych adresem, a niewygasający token resetu przeżywa konto i przejmuje konto następnego właściciela adresu
**Plik:** `shared/microservice-security/security-system/src/main/java/com/jrobertgardzinski/security/system/account/DeleteAccount.java:36-42`
**Także:** `ResetPassword.java:42` (brak sprawdzenia wygaśnięcia), `V3__password_resets.sql` (brak kolumny czasu), `V2__email_verifications.sql`, `V4__email_changes.sql`, `V12__passwordless_accounts.sql`; `grep REFERENCES` po migracjach → **zero trafień**
**Co robi kod:** Javadoc obiecuje *„no trace of its secrets remains"*, a `execute` czyści sesje, czynniki, kody odzyskiwania, linki federacyjne i wiersz `users`. Zostają: `password_resets`, `email_verifications`, `email_changes`, `passwordless_accounts`. Brak kluczy obcych, więc nic nie kaskaduje. `password_resets` **nie ma żadnej kolumny czasowej** — token nie wygasa nigdy, unieważnia go tylko jednorazowe użycie.
**Awaria:** Użytkownik prosi o reset hasła, potem loguje się starym hasłem i zamyka konto. Wiersz `password_resets` zostaje. Adres zwalnia się i rejestruje go inna osoba. Stary właściciel klika link z maila sprzed miesięcy: `consumeReset(token)` zwraca ten adres, `updatePassword` ustawia hasło **na koncie nowej osoby**, `setPasswordless(false)` domyka sprawę → **przejęcie konta**. Niezależnie od ataku: po „usunięciu" konta adres (dana osobowa) zostaje w czterech tabelach.
**Najmniejsza naprawa:** `purge(email)` w portach `EmailVerificationRepository` / `PasswordResetRepository` / `EmailChangeRepository` / `PasswordlessAccountRepository` (dla `email_changes` po obu kolumnach), wołane w `DeleteAccount` **przed** `deleteByEmail`. Niezależnie: kolumna `created_at`/`expires_at` w `password_resets` i odrzucanie w `ResetPassword` tokenu starszego niż TTL.
**Uwaga:** `DeleteAccountTest` ma komentarz *„by the time the row is deleted, nothing of the account survives"* — asertuje obietnicę, której nie sprawdza, bo brakujących repozytoriów nie ma nawet w konstruktorze klasy.
**Wysiłek:** M

---

## WYSOKI

### 5. Step-up nie ma ograniczenia liczby prób ani zapisu do brute-force — darmowa wyrocznia hasła i pompa maili z kodami
**Plik:** `security-system/.../mfa/StepUp.java:64` | **Także:** `StepUpController.java:35` (brak `SourceThrottle`), `BeanFactory.java:78-100` (throttle tylko dla `/register`, `/reset-password/request`, `/verify-email`)
**Co robi kod:** Ścieżka logowania przechodzi przez `_BruteForceGuard` i `_UpdateBruteForceRecords`. `StepUp.start` weryfikuje hasło i przy porażce zwraca `WrongPassword` — **bez** sprawdzenia blokady, **bez** zapisu porażki, **bez** throttlingu. Limit 5 prób z `ChallengeCodeConfig` obowiązuje w obrębie **jednego biletu**, a otwieranie nowych biletów jest nielimitowane.
**Awaria:** Napastnik z żywą sesją zgaduje hasło w pętli w pełnym tempie serwera: nic nie ląduje w `rejected_authentications`, konto nigdy się nie blokuje, właściciel nie widzi ani jednej nieudanej próby. Wariant drugi: dla akcji `SECOND_FACTORS` każde `start()` z czynnikiem `EMAIL_CODE` wysyła nowy kod → nielimitowana mail-bomba na skrzynkę ofiary i obejście limitu 5 prób (nowy bilet = świeży licznik).
**Najmniejsza naprawa:** Przepuścić `start()` przez `_BruteForceGuard`/`_UpdateBruteForceRecords` (albo wystawić wąski port zapisu porażki) i dodać `SourceThrottle` na `/account/step-up` oraz `/account/step-up/factor`. **Wysiłek:** M

### 6. Każde udane logowanie czyści licznik nieudanych prób CAŁEGO adresu IP — ochronę brute-force można zdjąć na żądanie
**Plik:** `security-system/.../authentication/_CleanBruteForceRecords.java:17-20` | **Także:** `Authentication.java:58`, `Source.java:15` (equals tylko po IP), `RejectedAuthenticationDetails.java:11` (brak pola konta)
**Co robi kod (sprawdzone ręcznie):** Nieudane próby są rejestrowane wyłącznie przeciw `Source` (= IP; szczegóły konta nie zawierają atakowanego adresu), a `removeAllFor(source)` jest wołane po **każdym udanym** uwierzytelnieniu. Dowolna znana napastnikowi poprawna para login/hasło jest więc przyciskiem RESET dla licznika z jego IP.
**Awaria:** Napastnik rejestruje własne konto (rejestracja jest otwarta). Z jednego IP: 2 × złe hasło do konta ofiary (przy `maxFailures=3` blokady nie ma), potem 1 × poprawne logowanie na własne konto → **wszystkie** rekordy i blokady dla tego IP wyczyszczone. Pętla daje nieograniczone zgadywanie bez ani jednej blokady. Wariant bez konta napastnika: dowolny współlokator NAT-a, który się poprawnie zaloguje, zeruje licznik atakującemu. Licznika per konto nie ma nigdzie, a `/authenticate` nie ma throttlingu.
**Najmniejsza naprawa:** Czyścić rekordy tylko dla pary (źródło, konto) — czyli dołożyć konto do `RejectedAuthenticationDetails` i zawęzić `removeAllFor`; ewentualnie nie czyścić wcale i zostawić wygasanie w czasie (patrz poz. 33). **Wysiłek:** M

### 7. `POST /logout` z naprawy P12 nigdy nie trafiło do memes-ui ani collections-ui — „sign out" nie kończy sesji serwerowej
**Plik:** `portal/microservice-memes/memes-ui/src/App.tsx:254` | **Także:** `collections-ui/src/App.tsx:45`, `SessionJdbcRepository.java:94` (nieaktualny komentarz „no UI ever calls POST /logout (P12 W1)")
**Co robi kod:** `onLogout` to wyłącznie `setToken(null)`; `session.expired()` też nie woła `/logout`. PLAN-P12 W1 wskazywał **dokładnie te dwa pliki**, a wywołanie dostała tylko `security-ui`, której w planie nie było. Rotujące ciasteczko `HttpOnly` (~doba) zostaje ważne.
**Awaria:** Zmierzone w P12: po „sign out" `POST /refresh` z tym samym ciasteczkiem oddaje 200 ze świeżym tokenem. Na wspólnym komputerze następna osoba robi jeden `fetch(SECURITY+'/refresh',{credentials:'include'})` i ma sesję poprzednika z jego rolami.
**Najmniejsza naprawa:** `logout()` w `memes-ui/api.ts` i w collections-ui (`credentials:'include'`, `keepalive`, błąd sieci ignorowany), wołane w `onLogout` i w `session.expired()`; poprawić komentarz w `SessionJdbcRepository`. **Wysiłek:** S

### 8. security-ui nie wysyła nagłówka `Authorization` na `/account/step-up/factor` — użytkownik z MFA nie może usunąć konta i słyszy „Wrong code."
**Plik:** `shared/microservice-security/security-ui/src/App.tsx:444` | **Także:** `AuthorizationFilter.java:23` (filtr na `/account/**`), `memes-ui/src/DeleteAccountDialog.tsx:71-74` (robi to poprawnie)
**Co robi kod:** `submitDeleteCode()` woła endpoint bez `Authorization: Bearer`. Filtr odrzuca żądanie 401-ką **przed** kontrolerem, a `messageFor` mapuje 401 na „Wrong code." — UI obwinia użytkownika o kod, którego nikt nie sprawdził.
**Awaria:** Konto z czynnikiem: poprawne hasło → 202 `FACTOR_REQUIRED` → poprawny kod z maila → 401 → „Wrong code." w pętli. Usunięcie konta (prawo RODO) przez security-ui jest dla kont z MFA **niemożliwe**.
**Najmniejsza naprawa:** Dodać nagłówek jak w `startDelete`; opcjonalnie scenariusz e2e `delete-with-factor` (obecny jawnie używa konta bez czynników). **Wysiłek:** S

### 9. Reset i zmiana hasła nie unieważniają istniejących sesji — jedyna ścieżka, która to robi, to podmiana hasła w `FederatedSignIn`
**Plik:** `security-system/.../passwordreset/ResetPassword.java:46` | **Także:** `ChangePassword.java:41`, kontrprzykład: `FederatedSignIn.java:116`
**Co robi kod:** Oba use case'y wołają tylko `updatePassword`. Sesje to wiersze niepowiązane z hashem hasła, więc żyją dalej do wygaśnięcia refresh tokenu. `revokeAllSessions` wołają wyłącznie `/sessions`, `StartAccountDeletion` i ścieżka federacyjna — która ma nawet komentarz tłumaczący, że rewokacja jest tu konieczna.
**Awaria:** Napastnik kradnie token. Użytkownik robi to, co każe każdy poradnik — zmienia hasło. Sesja napastnika **nadal autoryzuje** i nadal rotuje się na `/refresh` przez cały okres ważności refresh tokenu.
**Najmniejsza naprawa:** Wstrzyknąć `AuthorizationDataRepository` (albo use case `RevokeAllSessions`) i po udanej podmianie hasła wywołać `revokeAllSessions` w tej samej transakcji; przy zmianie hasła opcjonalnie wystawić od razu nową sesję wołającemu. **Wysiłek:** S

### 10. Adres e-mail zostaje NA ZAWSZE w `outbox_events` i `account_deletion_sagas` po ostatecznym usunięciu konta — zero retencji
**Plik:** `security-infrastructure/.../persistence/OutboxEventJdbcRepository.java:20` | **Także:** `V5__outbox_events.sql`, `V6__account_deletion_saga.sql`, `AccountDeletionSagaJdbcRepository.java`
**Co robi kod:** Opublikowane wiersze są tylko stemplowane, nigdy nie usuwane — w repo nie ma **żadnego** `DELETE` dla tych dwóch tabel. Każdy wiersz outboxu niesie adres jako `event_key` **i** w payloadzie; wiersz sagi w kolumnie. To nie jest świadomy kompromis: dla `processed_offboarding_outcomes` ten sam zespół zrobił retencję 7 dni.
**Awaria:** Rok po „usunięciu" konta w zrzucie bazy nadal są: adres w `account_deletion_sagas`, fakt z adresem i wybranymi regułami purge w `outbox_events`, mail z polem `to`. `outbox_events` jest de facto wieczystym rejestrem adresów wszystkich kiedykolwiek zarejestrowanych użytkowników — dokładnie „obvious prize in a database dump", przed którym ostrzega javadoc `ExpiredSessionReaper`.
**Najmniejsza naprawa:** Reaper wzorem `JdbcProcessedOutcomes`: `DELETE FROM outbox_events WHERE published_at < now()-X` (indeks pod `published_at`) i `DELETE FROM account_deletion_sagas WHERE state <> 'STARTED' AND updated_at < now()-X`, z tym samym wzorcem połkniętego wyjątku i logiem. **Wysiłek:** M

### 11. Zamiatacz liczy przeterminowanie od `created_at`, więc kapituluje ~45 s po progu, a ostatni re-rozkaz ma u uczestnika jeszcze ~120 s budżetu — purge wykonuje się PO ogłoszeniu porażki
**Plik:** `portal/microservice-offboarding/.../infrastructure/JdbcSagaStore.java:152-154` | **Także:** `InMemorySagaStore.java:100`, `application/SweepOverdue.java`, `memes/.../SagaRetryBudget.java` (javadoc z arytmetyką)
**Co robi kod:** `sweepOverdue` filtruje `state='STARTED' AND created_at < cutoff`, gdzie `cutoff = now - purgeTimeout`. Po przekroczeniu progu saga wraca jako kandydat na **każdym** przebiegu (co 15 s), więc trzy ponowienia schodzą w 45 s i kompensacja pada przy ~165 s. `retryDelivered()` celowo przesuwa `updated_at`, ale zapytanie tej kolumny **nie czyta**.
**Awaria:** Baza memes leży od t=0 do t=210 s. Rozkazy: t≈0, re-rozkazy ≈120/135/150 s, kompensacja t≈165 s → security odblokowuje konto i wysyła „usunięcie się nie udało". O t≈210 s baza wraca, ostatni re-rozkaz **wciąż jest w budżecie** → memy skasowane. Potwierdzenie trafia na sagę `COMPENSATED` i jest cicho odrzucone. Użytkownik ma konto i wiadomość, że nic nie usunięto — a treści nie ma.
**Najmniejsza naprawa:** Filtrować sweep po `updated_at` (już przesuwanym przez `retryDelivered`), więc kompensacja nastąpi dopiero, gdy budżet uczestnika na ostatni re-rozkaz na pewno wygasł. Lustrzanie w `InMemorySagaStore` i w arytmetyce javadoca memes.
**Uwaga:** `JdbcSagaStoreTest.the_sweep_retries_before_capitulating` przypina obecną semantykę — trzeba go świadomie przepisać. **Wysiłek:** M

### 12. `user-collections` ponawia purge w nieskończoność i wykonuje go PO kompensacji sagi — kasuje kolekcje konta, które orkiestrator już oddał właścicielowi
**Plik:** `portal/microservice-user-collections/.../infrastructure/PurgeCommandsConsumer.java:338` | **Także:** `comments/.../SagaRetryBudget.java:33-36` (udokumentowana oś czasu, zastosowana tylko u bliźniaków)
**Co robi kod:** Przy każdym błędzie store'u: rewind, backoff do 30 s, ponawianie bez commitu, **bez limitu czasu**. Rodzeństwo ograniczyło retry do 90 s dokładnie z tego powodu i javadoc `SagaRetryBudget` wprost to nazywa: *„A participant that retried without end would purge whenever its store came back — an hour later, a day later — deleting the content of an account the saga has already restored to its owner"*.
**Awaria:** Postgres collections leży 30 minut. Użytkownik żąda usunięcia; rozkaz wiecznie się ponawia. Po ~165 s saga kompensuje: konto przywrócone, mail „deletion failed". Po 30 minutach baza wraca → **wszystkie kolekcje użytkownika usunięte**, potwierdzenie odrzucone, zero sygnału dla użytkownika i operatora.
**Najmniejsza naprawa:** Przenieść semantykę budżetu z `SagaRetryBudget`: deadline zegarowy per rekord (~90–120 s), po którym rekord jest commitowany, porzucany **głośno** i zliczany metryką. Wieczny retry zostawić wyłącznie dla awarii pollowania/commitu, nie dla obsługi rekordu.
**Uwaga:** `PurgeCommandsConsumerLoopTest:106` asertuje obecne zachowanie. **Wysiłek:** M
**Powiązane:** ten sam defekt zgłosili niezależnie dwaj agenci (warstwa aplikacyjna i infrastruktura) — to podnosi zaufanie do pozycji.

### 13. Werdykt sagi nie niesie żadnej korelacji z żądaniem usunięcia — spóźniony `PORTAL_PURGE_FAILED` starej sprawy kompensuje po adresie NOWSZE usunięcie
**Plik:** `portal/microservice-offboarding/.../infrastructure/EventsRouter.java:275` | **Także:** `EventsRouter.java:153-192` (`onDeletionRequested` ignoruje `sagaId` z faktu), `V2__saga_hardening.sql:26-28`, `AccountDeletionOrchestrator.java:108-133` (`complete`/`compensate` po adresie)
**Co robi kod:** Security wkłada w fakt swoje `sagaId`, ale router czyta tylko `id`/`email`/`policy` — uchwyt przepada. Werdykt niesie `id` pochodne od `(sagaId portalu, typ)`; security deduplikuje po tym `id`, ale **działa po adresie**. Każdy niedostarczony wcześniej werdykt starej sprawy ma świeże, nigdy nieclaimowane `id`, więc przechodzi.
**Awaria:** Usunięcie #1: portal kompensuje P1, ogłoszenie nie dociera (awaria brokera albo spauzowana partycja z P16). Siatka security po 5 min odblokowuje konto. Użytkownik prosi ponownie → S2 w security, P2 w portalu rusza purge. Zamiatacz re-ogłasza `PURGE_FAILED(P1)` → claim przechodzi → **kompensuje S2** i odblokowuje konto, gdy P2 właśnie kasuje treści. P2 kończy → `PORTAL_CONTENT_PURGED` → w security nie ma już sagi `STARTED` → log `CONTENT ERASED AFTER COMPENSATION`.
**Najmniejsza naprawa:** Przechować `sagaId` security (albo użyć już przechowywanego `fact_id`) w wierszu sagi i odbić je echem w werdykcie; dopasowywać werdykt do **konkretnej** sagi security, a niepasujący logować jako błąkańca. ADR 0004 pozwala dodać pole w wersji 1. **Wysiłek:** M

### 14. Jeden trwale niepublikowalny wiersz blokuje CAŁY outbox security na zawsze — a rozmiar payloadu jest pod kontrolą użytkownika
**Plik:** `security-infrastructure/.../persistence/OutboxPublisher.java:68-70` | **Także:** `PurgeChoices.java` (brak walidacji), `DeleteAccountController.java:63`, `AccountDeletionOrchestrator.java:91-93`
**Co robi kod:** `drain()` iteruje po **wszystkich** nieopublikowanych wierszach (jedna kolejka dla wszystkich topików) i przy pierwszym wyjątku robi `return` z komentarzem „keep ordering… retry next tick". Komentarz zakłada awarię przejściową (zmienna nazywa się `brokerDown`), ale wyjątek może być **trwały**: `RecordTooLargeException` dla payloadu > `max.request.size`. `PurgeChoices` nie waliduje ani liczby kluczy, ani długości wartości, a kolumna payloadu to `TEXT`.
**Awaria:** Uwierzytelniony użytkownik po step-upie wysyła `POST /account/delete` z `{"purge":{"x":"<2 MB śmieci>"}}`. Od tej chwili **żaden** wiersz nie wychodzi: nikt w systemie nie dostaje maila weryfikacyjnego, resetu hasła, kodu MFA ani faktu usunięcia konta — aż ktoś ręcznie usunie wiersz z bazy. Jedyny ślad to `WARN` sugerujący problem z brokerem.
**Najmniejsza naprawa:** Dwie warstwy: (1) walidacja rozmiaru `PurgeChoices` na wejściu; (2) w `drain()` odróżnić błąd trwały od przejściowego — dla `RecordTooLargeException`/`SerializationException` oznaczyć wiersz jako trwale nieudany (nowa kolumna) z `ERROR` i jechać dalej; globalny stop zostawić dla błędów transportowych. **Wysiłek:** M

### 15. `microservice-email` potwierdza rekord także wtedy, gdy mail nie został ani wysłany, ani zaparkowany — nieudane parkowanie kończy się trwałą utratą maila
**Plik:** `shared/microservice-email/.../mail/boundary/MailRequestsConsumer.java:107-110` | **Także:** `park()` tego samego pliku, linie 272-283
**Co robi kod:** `consume()` ackuje bezwarunkowo (`process(...).chain(settled -> message.ack())`). Komentarz mówi „the ack rides on SETTLED… a parked mail is handled too" — ale gałąź, w której `park()` **nie zdołał** zapisać rekordu na DLQ, połyka błąd, loguje „is lost" i **też ackuje**. Mail nie jest wtedy ani dostarczony, ani zaparkowany, a offset idzie do przodu. Po stronie security wiersz outboxu jest już oznaczony jako opublikowany.
**Awaria:** SMTP w outage'u i publikacja na `mail-requests-dlq` odrzucona (rekord parkowany = oryginał + failure + `parkedId`, więc może przekroczyć `max.request.size`; albo quota/ACL). `PASSWORD_RESET` przepada bezpowrotnie: offset zacommitowany, DLQ pusta, outbox „published". Nie istnieje żaden mechanizm ponowienia.
**Najmniejsza naprawa:** Gdy `park()` zawiedzie — **nie ackować**: zwrócić błąd i nack-ować (z ustawioną `failure-strategy`), żeby rekord wrócił przy redelivery. Utrata zamienia się w opóźnienie. **Wysiłek:** M

### 16. Synchroniczny wyjątek producenta liczy się jako `REJECTED`, więc długi outage brokera nadal trwale zatruwa wiersze — wbrew temu, co javadoc i test twierdzą, że naprawiono
**Plik:** `shared/transactional-outbox/.../outbox/OutboxPublisher.java:179` | **Także:** `OutboxRepublisher.java:100-113`, `memes`/`comments` `application.properties` (`max.block.ms=5000`)
**Co robi kod:** `publishAndWait` łapie `RuntimeException` z `dispatch.send()` i zwraca `REJECTED` z komentarzem *„about the event or about this instance, not about the broker's availability"*. To zdanie jest **nieprawdziwe dla Kafki**: przy niedostępnym brokerze `KafkaProducer.send()` rzuca **synchronicznie** `TimeoutException` po `max.block.ms` (brak metadanych w cache — np. pod zrestartowany w trakcie outage'u) oraz przy pełnym buforze.
**Awaria:** Broker pada w piątek, pod memes restartuje się w trakcie. Każdy przebieg: `send()` blokuje 5 s, rzuca `TimeoutException` → `REJECTED` → `attempts+1`. Po ~19 h `attempts=25` i wiersz **znika z zapytania na zawsze**. Broker wraca w poniedziałek — zdarzenie nigdy nie wychodzi; ratunek to ręczny `UPDATE`.
**Najmniejsza naprawa:** W `catch (RuntimeException sendRefused)` zwracać `TIMED_OUT` (płaski backoff, zero licznika) zamiast `REJECTED`. Nic realnego nie tracimy: serializacja `String` nie rzuca, `RecordTooLarge` przychodzi callbackiem (nadal `REJECTED`), a „producer closed" to problem instancji, nie zdarzenia.
**Uwaga:** `FakeDispatch` modeluje outage wyłącznie jako ciszę, więc zielony test `a_silent_broker_does_not_count_against_the_event` dowodzi czegoś innego niż realna Kafka. **Wysiłek:** S

### 17. `OauthFlowStore` nigdy nie usuwa niedokończonych przepływów — anonimowy, nielimitowany `GET /oauth/{provider}/start` rośnie w pamięci aż do `OOMKilled`
**Plik:** `security-infrastructure/.../OauthFlowStore.java:38` | **Także:** `InMemorySessionElevation.java:32`, `InMemoryStepUpStore.java:26`, `InMemoryPendingAuthenticationStore.java:31`, `SourceThrottle.java:44`
**Co robi kod:** `begin()` wkłada wpis do `ConcurrentHashMap`, a jedyne usunięcie jest w `consume()` (callback providera). TTL 10 minut jest sprawdzane **tylko jako filtr przy odczycie** — reapera nie ma (`grep @Scheduled` w module: tylko `AccountDeletionTimeouts`, `ExpiredSessionReaper`, `OutboxPublisher`, `JdbcProcessedOutcomes`). Javadoc twierdzi „short-lived", co jest fałszem: przepływ, po którym nikt nie wrócił, zostaje do restartu procesu.
**Awaria:** `while true; do curl -s -o /dev/null .../oauth/google/start; done` — bez żadnego uwierzytelnienia. ~0,4 KB na wpis, limit kontenera `768Mi` → `OOMKilled` po ~1–1,5 mln żądań. Przy `replicas: 1` i `strategy: Recreate` to **pełna przerwa w tożsamości** dla portalu i gry naraz.
**Najmniejsza naprawa:** `@Scheduled(fixedDelay="1m")` sweep usuwający wygasłe wpisy (albo `Caffeine`/twardy limit rozmiaru) — analogicznie dla czterech pozostałych map; minimalnie: cap rozmiaru + `SourceThrottle` na `/oauth/{provider}/start`. **Wysiłek:** S

### 18. Domyślna („Recommended") opcja kreatora usuwania konta nie wysyła żadnej polityki, więc override admina cicho ją nadpisuje — wbrew obietnicy „wybór użytkownika wygrywa ze wszystkim"
**Plik:** `portal/microservice-memes/memes-application/.../PurgeUserContent.java:40` | **Także:** `memes-ui/src/DeleteAccountDialog.tsx:38-41,89-91`, `PurgePolicyOverride.java:9-11`, `AdminController.java:21`, `AccountDeletionOrchestrator.java:91-93`
**Co robi kod:** `requested.or(override::current).orElse(defaultRule)` realizuje kolejność „wybór → override → default", a **trzy** javadoci obiecują, że życzenie użytkownika wygrywa ze wszystkim. Dla preselektowanej opcji kreatora to nieprawda: dla `choice === 'default'` UI ustawia `purge = null` i wysyła `{}`, a orkiestrator dokłada pole `policy` tylko gdy mapa nie jest pusta. Jawny wybór „delete my memes" jedzie po drucie jako **brak preferencji**.
**Awaria:** Admin ustawia override `ANONYMIZE_AUTHOR` (np. na czas incydentu moderacyjnego). Użytkownik zostawia zaznaczoną opcję z etykietą „Recommended: delete my memes", potwierdza hasłem i czynnikiem → wszystkie jego memy **zostają** z podmienionym autorem, wątki komentarzy też (bo `MEME_DELETED` nie idzie). Security dostaje `PORTAL_CONTENT_PURGED` i kasuje konto: użytkownik dostaje list pożegnalny mówiący, że stało się to, o co prosił.
**Najmniejsza naprawa:** Kreator musi wysyłać jawne reguły także dla opcji domyślnej (`{memes:'DELETE', comments:'ANONYMIZE_AUTHOR'}` — dokładnie to, co obiecuje etykieta). Alternatywnie rozdzielić w API „brak preferencji" od „preferencja = domyślna wdrożenia". Plus poprawić trzy javadoci. **Wysiłek:** S

### 19. Na filesystem/S3 skasowanie bajtów obrazu po commicie jest ulotne — awaria między commitem a callbackiem zostawia zdjęcia użytkownika na zawsze, choć purge został potwierdzony
**Plik:** `portal/microservice-memes/memes-infrastructure/.../S3ObjectStore.java:74` | **Także:** `FilesystemObjectStore.java:58`, `TransactionAwareDeletes.java:98-112`
**Co robi kod:** Potwierdzenie `USER_CONTENT_PURGED` jest trwałe (wiersz outboxu w transakcji), ale skasowanie bajtów to `Runnable` zaparkowany **w pamięci JVM**. Po commicie: porażka `delete` to tylko `WARN` i osierocony obiekt (nic nie ponawia, nie ma inwentarza kluczy), a restart poda między commitem a fazą after-commit **gubi kasowanie całkowicie** — wiersz `memes` już nie istnieje, więc nikt nie zna klucza.
**Awaria:** `MEMES_BLOB_STORE=s3`, użytkownik z 500 memami i polityką `DELETE`. Transakcja commituje, pod zostaje ubity (deploy `Recreate`/OOM) przed wykonaniem `deleteObject`. Republisher wysyła potwierdzenie, saga domyka się, konto skasowane — a 500 obrazów (często zdjęcia z twarzami, czyli dane osobowe) leży w buckecie bezterminowo i nie istnieje kod, który by je znalazł. Sam projekt nazywa identyczny skutek naruszeniem RODO w javadocu `OrphanedBlobMigration`.
**Najmniejsza naprawa:** Uczynić obowiązek kasowania trwałym: w transakcji zapisywać klucze do `pending_blob_deletes` (jak outbox), po commicie kasować i usuwać wpis po sukcesie, okresowy sweep dokańcza. Minimum: przed commitem zalogować listę kluczy, żeby awaria zostawiła ślad. **Wysiłek:** M

### 20. Uzasadnienie `-DskipTests` w portal CI jest nieprawdziwe dla ośmiu bibliotek bez własnego CI — push do nich nie uruchamia nigdzie ani jednego testu
**Plik:** `portal/.github/workflows/ci.yml:128` | **Także:** `shared/.github/workflows/ci.yml:14-18` (brak `schedule`); repozytoria bez `.github/workflows`: `voting`, `config`, `password`, `email`, `constraint`, `test-starter`, `adjustable-clock`, `infrastructure-micronaut-clock`
**Co robi kod:** Portal CI instaluje kernel z `-DskipTests`, uzasadniając „tests live in workspace-shared's CI". Ale workflow agregatora odpala się tylko przy pushach do **własnego** repo — nie przy pushach do pod-repozytoriów, a osiem z nich nie ma żadnego workflow.
**Awaria:** Regresja zacommitowana do `voting` (przez nie idą głosy memes i comments) trafia na main **bez jednego czerwonego znaku**; ujawni się przy następnym, niezwiązanym pushu do agregatora — z winowajcą sprzed tygodni.
**Najmniejsza naprawa:** Minimalne `ci.yml` (`mvn verify`) w ośmiu repozytoriach, albo `schedule` w CI agregatora + usunięcie mylącego uzasadnienia z nagłówka portal CI. **Wysiłek:** M

### 21. `FederatedSignIn` modyfikuje konto (nadpisuje hasło, rewokuje sesje, oznacza adres jako zweryfikowany, linkuje providera) PRZED sprawdzeniem, czy konto jest w trakcie usuwania
**Plik:** `security-system/.../federation/FederatedSignIn.java:84-91` | **Także:** tamże 106-123
**Co robi kod:** `claimByEmail` (linia 88) wykonuje wszystkie te zmiany, a `isPendingDeletion` (89) jest sprawdzane **dopiero potem** — i wtedy zwracane jest `Refused("ACCOUNT_CLOSING")`.
**Awaria:** Konto z trwającą sagą usunięcia: ktoś (także sam właściciel, przez pomyłkę) klika „zaloguj przez Google" → hasło nadpisane, adres oznaczony jako zweryfikowany, tożsamość providera **dolinkowana do konta, które właśnie znika**. Logowanie odmawia, ale skutki uboczne zostają; przy kompensacji konto wraca w zmienionym stanie.
**Najmniejsza naprawa:** Przenieść sprawdzenie `isPendingDeletion` **przed** `claimByEmail`. **Wysiłek:** S

### 22. `StepUpPolicy` nie waliduje wartości, więc literówka w konfiguracji cicho degraduje `FULL_CHAIN` do „wystarczy żywa sesja"
**Plik:** `security-config/.../mfa/StepUpPolicy.java:23` | **Także:** `BeanFactory.java:325-328`, `StepUp.java:59-63`
**Co robi kod:** Wartości są zwykłymi `String`ami porównywanymi przez `equals`. `security.step-up.delete-account=FULL_CHAN` (literówka) nie jest ani `NONE`, ani `FULL_CHAIN`, więc `StepUp.start` pomija weryfikację hasła i — przy koncie bez czynników — podnosi uprawnienie natychmiast. Start serwisu przechodzi bez ostrzeżenia.
**Awaria:** Literówka w zmiennej środowiskowej zdejmuje ochronę z najbardziej nieodwracalnej operacji w systemie, a jedynym sygnałem jest jej brak.
**Najmniejsza naprawa:** Walidacja w konstruktorze `StepUpPolicy` (wartość musi należeć do zbioru; inaczej wyjątek przy starcie, wzorem `parseParticipants` w offboardingu). **Wysiłek:** S

---

## ŚREDNI

| # | Pozycja | Plik | Skutek / naprawa |
|---|---|---|---|
| 23 | `StepUp.submitFactor` nigdy nie sprawdza wygaśnięcia biletu (klasa nie ma `Clock`), `InMemoryStepUpStore` bez eksmisji | `StepUp.java:78-102`, `InMemoryStepUpStore.java` | Bilet żyje do restartu; bliźniacza `ContinueAuthentication:35` sprawdza to samo pole tej samej klasy. **Waga obniżona z WYSOKIEJ przez weryfikatora i słusznie:** elewacja jest zapisywana pod access tokenem z biletu, więc sam bilet bez tokenu nie wystarcza. Naprawa: `Clock` do `StepUp` + odrzucanie wygasłego biletu + eksmisja w store. |
| 24 | `rejected_authentications` bez żadnego sprzątania w czasie | `RejectedAuthenticationRepository`, `V?__rejected_authentications.sql` | Javadoc `Source` obiecuje, że dane żyją tyle, ile rekordy, które opisują — a nic ich nie usuwa. Reaper wzorem `ExpiredSessionReaper`. |
| 25 | `UserRepository.save` ma dwie semantyki w dwóch adapterach (nadpisanie vs wyjątek) i trzecią, martwą, w `SaveResult` | `UserRepository.java:43`, `SaveResult.java` | Ta sama operacja zachowuje się inaczej z bazą i bez niej; `SaveResult` to martwy kod przy dwóch udokumentowanych modelach awarii. Ujednolicić i usunąć martwy typ. |
| 26 | Nic nie gwarantuje jednej sagi `STARTED` na adres w security; dubler in-memory ma inną semantykę przejść niż JDBC | `AccountDeletionSagaJdbcRepository.java:18-24`, `InMemoryAccountDeletionSagaStore.java:47-67` | JDBC zmienia **wszystkie** pasujące wiersze, in-memory tylko pierwszy → testy dowodzą czegoś innego niż produkcja. Dodać `UNIQUE` częściowy (albo kolumnę-zatrzask jak `running_email` w offboardingu) i zrównać dubler. |
| 27 | Adres e-mail w **ścieżce URL** endpointów admina, a `CorrelationIdFilter` loguje każdą ścieżkę na INFO | `AdminFactorsController`, `CorrelationIdFilter.java:27-36` | Dane osobowe trafiają do logów (i do Loki) przy każdym żądaniu admina. Przenieść adres do ciała żądania albo maskować ścieżkę w logu dostępowym. |
| 28 | `meme_votes` bez klucza obcego: głos oddany w wyścigu z usunięciem mema tworzy sierotę, którą „hot page" promuje | `V?__meme_votes.sql`, `JdbcVoteRepository` | Sierocy wiersz podbija nieistniejący mem na szczyt listy. FK `ON DELETE CASCADE` albo czyszczenie w tej samej transakcji. |
| 29 | `BeginOffboarding` wyrzuca wynik zatrzasku `sagas.complete()` i bezwarunkowo melduje `completedImmediately=true` | `BeginOffboarding.java:36-43` | Przy odtworzeniu faktu werdykt jest ogłaszany ponownie; ratuje to tylko deduplikacja po deterministycznym `id`. Zwrócić wynik zatrzasku i ogłaszać tylko przy `true`. |
| 30 | Poprawka heartbeatu lampy (stemplowanie per rekord) trafiła tylko do comments — readiness memes czerwienieje, **gdy listener pracuje** | `memes/.../SagaParticipantConfig.java:50` vs `comments/.../SagaParticipantConfig.java:232-254` | Rekord jadący na budżecie retry nie daje pustych pollów → brak idle-eventów → `DOWN` po ~160 s i galeria wypada z Service, choć wszystko działa. Przenieść `ContainerCustomizer` + `RecordInterceptor` + `recordDelivered` jeden do jednego. |
| 31 | memes i comments konsumują z domyślnym `auto.offset.reset=latest`; collections jawnie ustawia `earliest` i nazywa to zaletą | `comments/application.properties:18`, `memes/application.properties:56` | Świeże środowisko albo włączenie Kafki po przerwie → rekordy sprzed pierwszego offsetu grupy pomijane **bez śladu w logu**. Ustawić `earliest` (obie strony są idempotentne) + test pinujący właściwość. *(sprawdzone ręcznie: właściwości nie ma nigdzie w `src/main`)* |
| 32 | Potwierdzenie brokera przychodzące PO `patience` w `publishAndWait` jest wyrzucane — wolny broker (ack > 5 s) mnoży duplikaty | `transactional-outbox/.../OutboxPublisher.java` | Zamiast jednej wysyłki powstaje kolejna w każdym przebiegu. Przyjmować spóźnione potwierdzenie (kolejka `confirmedAwaitingMark` już istnieje). |
| 33 | Router loguje „recorded … purge confirmation; saga not complete yet" także dla błąkańców, w których **nic nie zapisano** | `offboarding/.../EventsRouter.java` | Log twierdzi, że potwierdzenie zapisano, gdy zostało odrzucone — mylące przy diagnozie. Rozdzielić dwa komunikaty. |
| 34 | PR w portalu zmieniający pakt konsumencki nie jest weryfikowany przeciw security przed merge | `portal/.github/workflows/ci.yml` | Czerwień pojawia się dopiero w CI drugiego repozytorium (albo nigdy — patrz poz. 20). Sklonować sąsiada w portal CI dla samych testów paktowych. |
| 35 | Zbiór wymaganych uczestników czytany z konfiguracji przy **każdym** potwierdzeniu, nie zapisany z sagą | `offboarding/.../JdbcSagaStore.java:125`, `Main.java:172-176` | Zmiana `OFFBOARDING_PARTICIPANTS` + restart zmienia kryterium kompletności **sag już otwartych**: skrócenie listy domyka trwającą sagę, wydłużenie każe czekać na uczestnika, który nie dostał rozkazu. Zapisywać zbiór z sagą przy starcie. |
| 36 | Pełne adresy e-mail w logach ścieżki usuwania konta — obok funkcji `masked()` zdefiniowanej dwa razy w tych samych plikach | `AccountDeletionOrchestrator.java:132,175` | Gałęzie sukcesu logują pełny adres, gałęzie błędu maskują. Użyć `masked()` wszędzie i wyciągnąć duplikat do jednego miejsca. |

---

## NISKI

| # | Pozycja | Plik | Uwaga |
|---|---|---|---|
| ~~37~~ **ZROBIONE** | ~~Wszystko, co wysyła zamiatacz, idzie **bez** nagłówka `X-Correlation-Id`~~ | `offboarding/.../KafkaLoop.java:341` + `sweepCid()` | **Zamknięte 2026-07-30 (Opus 5).** Zamiatacz nie ma skąd odziedziczyć `cid`, więc **wybija go z identyfikatora sagi**: `saga-<8 znaków>`. Deterministycznie (re-ogłoszenie powtarza ten sam identyfikator, jak jego bajtowo identyczny payload) i **bez danych osobowych** (identyfikator nazywa SPRAWĘ, nie osobę — adres jest już w kluczu). Świadomie NIE odtwarza cid pierwotnego żądania HTTP: to wymaga kolumny na wierszu sagi i migracji, czyli zmiany w `JdbcSagaStore` (paczka C). Dowód: `KafkaLoopIntegrationTest.a_recommand_from_the_sweeper_carries_a_correlation_id_minted_from_its_saga` na prawdziwym brokerze; **test sprawdzony chirurgicznym revertem** — bez poprawki pada na `expected: not <null>`. `mvn clean verify`: **126 testów, 0 porażek**. |
| ~~38~~ **ZROBIONE** | ~~`collections` przepisuje `record.key()` rozkazu (adres leavera) na klucz potwierdzenia~~ | `PurgeCommandsConsumer.java` + nowe `keyFor()` | **Zamknięte 2026-07-30 (Opus 5).** Kluczem potwierdzenia jest teraz **sagaId** (bez niego deterministyczny `nameUUIDFromBytes(email)` — wzorzec skopiowany z comments), więc adres nie leży jawnie na `usercollections-events`. Dowód: `PurgeCommandsConsumerLoopTest.the_confirmation_is_keyed_by_the_saga_never_by_the_leavers_address`; revert → `expected: <s-1> but was: <alice@example.com>`. `mvn clean verify`: **128** testów, 0 porażek. |
| ~~39~~ **ZROBIONE** | ~~`KEEP_POPULAR` liczy próg z głosami samego odchodzącego~~ | `PurgeUserContent.java:47-54` | **Zamknięte 2026-07-30 (Opus 5).** `purgeVoter(author)` przeniesione **przed** pętlę, więc próg mierzy wyłącznie **wynik społeczności** — dotąd odchodzący kupował swoim memom przeżycie głosem, który ta sama metoda zaraz kasowała. Dowód: `PurgeUserContentTest.the_leavers_own_votes_do_not_count_towards_the_threshold`; revert kolejności → `expected: <false> but was: <true>`. `mvn clean verify`: **232** testy, 0 porażek. |
| ~~40~~ **ZROBIONE** | ~~`DeleteAccountDialog`: 429/5xx jako „Wrong password.", błąd sieci blokuje dialog~~ | `memes-ui/src/DeleteAccountDialog.tsx` | **Zamknięte 2026-07-30 (Opus 5).** Lokalne `messageFor(status, wrong)`: 401/403 → komunikat o haśle/kodzie, **429 → „wait a moment"**, reszta → `Security answered <status>`. Trzy przepływy owinięte w `try/catch/finally` — `setBusy(false)` w `finally`, bo wyjątek go przeskakiwał i **jedynym wyjściem z kreatora było odświeżenie strony**. Nabrało wagi po poz. 5: step-up ma teraz limit prób, więc 429 jest odpowiedzią rutynową. Dowód: `deleteAccountDialog.test.tsx` 3/3; **oba reverty sprawdzone** (mapowanie → 2 czerwone, przepuszczenie wyjątku z `catch` → 3 czerwone). Cała suita memes-ui **10/10**, `npm run build` (tsc+vite) zielony. |
| ~~41~~ **ZROBIONE** | ~~Komunikat walidacji progu lampy nazywa zmienną, której nikt nie czyta~~ | `security-infrastructure/src/main/resources/application.yml` + `ListenerStallEnvIsWiredTest` | **Zamknięte 2026-07-30 (Opus 5).** Komunikat kazał ustawić `SECURITY_LISTENER_STALL_SEC`, a wartość szła z właściwości `security.saga.listener-stall-seconds` — **zmiennej nie czytało nic**, więc operator robił dokładnie to, co kazano, i widział ten sam błąd. Dodane jawne mapowanie w `application.yml` (konwencja bliźniaków: memes `MEMES_LISTENER_STALL_SEC`, comments `COMMENTS_LISTENER_STALL_SEC`), domyślna 120 = domyślna z `@Value`, więc zachowanie bez zmian. **Nie zgadywałem** mapowania env→property Micronauta — jawne `${VAR:default}` działa na pewno. Dowód: nowy test **czyta wdrażany plik z dysku i porównuje z STAŁĄ `STALL_ENV`**, więc komunikat i konfiguracja nie mogą się już rozjechać (zmiana nazwy stałej → czerwony test). Sprawdzony revertem: bez mapowania pada. |

---

## Czego celowo NIE raportuję

- **Dobór brokera i frameworków.** Kafka jest przesądzona, projekt jest edukacyjny; pięć różnych
  stosów to zamierzone portfolio, nie niespójność.
- **Brak warstwy domenowej w offboardingu.** Świadomy wybór (choć udokumentowany w przewodniku,
  nie w ADR — to jedyna uwaga, i jest w podręczniku).
- **Stan sagi jako `String` zamiast enuma.** Realna krucha konstrukcja, ale w tym przeglądzie nie
  wygenerowała żadnego osiągalnego scenariusza awarii; zostaje jako dług, nie jako defekt.
- **Brak DLQ w przepływie usuwania konta.** Nazwany kompromis z prawdziwym uzasadnieniem
  (`user-collections`: „No dead-letter queue — on purpose"). Zgłaszam natomiast **skutki** tego
  wyboru tam, gdzie uzasadnienie okazało się nieprawdziwe (poz. 12, 16).
- **„Dodać więcej testów"** jako pozycja sama w sobie. Luki w testach opisuję przy pozycjach,
  których dotyczą — w kilku miejscach (poz. 1, 2, 11, 12) istniejący test **asertuje wadę**,
  i to jest ważniejsze niż liczba testów.
- **`e2e-saga-outage.sh` nie chodzi automatycznie.** Znane z podręcznika §14, świadome (wymaga
  zatrzymywania kontenerów). Zostaje.

---

## Co to zmienia w `PODRECZNIK.md`

Krótko: **nie zmienia szkieletu, koryguje trzy miejsca**. Szczegóły — patrz sekcja „Errata"
dopisana do podręcznika 2026-07-30.

| Rozdział podręcznika | Co się zmienia |
|---|---|
| §4 (step-up) | Podręcznik cytuje javadoc „the thief of a live session would have to pass the chain too" jako opis działającej ochrony. **To zdanie jest nieprawdziwe** (poz. 1). Trzeba to nazwać. |
| §8.4 i §12.2 (przeciwne polityki retry) | Podręcznik opisuje to jako „nazwany kompromis, w którym dwie usługi wybrały przeciwne strony dylematu". Po weryfikacji: w `collections` scenariusz utraty danych jest **osiągalny** (poz. 12), a u koordynatora dochodzi drugi, niezależny mechanizm (poz. 11). To defekt, nie kompromis. |
| §13 (co zostaje po użytkowniku) | Tabela „co zostaje" jest poprawna, ale **niepełna i za łagodna**: brakuje niewygasającego tokenu resetu, który przejmuje konto następnego właściciela adresu (poz. 4), i utraty MFA przy zmianie adresu (poz. 3). |
| §14 (czym to jest udowodnione) | Dodać jeden wiersz do tabeli „co zepsute → co wyłapie": push do ośmiu bibliotek `shared` **nie uruchamia żadnych testów** (poz. 20). |
| Reszta (§1–3, §5–7, §9–11, §15–19) | **Bez zmian.** Opis sagi, outboxu, zatrzasku, kworum, arytmetyki timeoutów i wdrożenia jest zgodny z kodem. |
