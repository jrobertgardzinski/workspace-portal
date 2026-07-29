# Plan pracy po przeglądzie — 2026-07-29

> ## STAN REALIZACJI — czytaj to najpierw
>
> Przegląd: Fable 5, ultracode, 6 recenzentów + 3 przeciwstawne soczewki weryfikacji.
> **26 znalezisk potwierdzonych (każde 3/3 głosy), 1 odrzucone. Po scaleniu duplikatów: 24 pozycje.**
>
> | | |
> |---|---|
> | ZROBIONE | **24 z 24** — paczki A, B, C, D zamknięte 2026-07-29 |
> | ZOSTAJE | **0** |
> | Blokuje wdrożenie na k3s | **nic** |
>
> ### Commity zamykające
>
> | paczka | repo | commit |
> |---|---|---|
> | (przed planem) | portal | `4150cb2` — poz. 1, 4 |
> | A | shared/microservice-security | `48be701` |
> | A | portal/microservice-comments | `30e6dc4` |
> | A | portal/microservice-offboarding | `db8ec20` |
> | B+C | shared/microservice-security | `3f26baa` — poz. 5, 10, 14, 22 |
> | B+C | shared/microservice-email | `48e92fa` — poz. 6, 17, 20, 21 |
> | B+C | portal/microservice-comments | `98e3ab9` — poz. 9, 13, 18, 19 |
> | B+C | portal/microservice-memes | `a60e16d` — poz. 11, 12, 15, 16, 19 |
> | D | portal | `4f3f6cc` — poz. 7, 23, 24 |
>
> ### Czego Fable nie doszacował (wyszło przy wdrażaniu)
>
> - **poz. 2 i 8 — „najmniejsza naprawa" była za mała.** Dopisanie dwóch linii `path:` nie
>   wystarcza: przy PŁASKIM checkoucie samego siebie `../../../portal/…` i tak celuje POWYŻEJ
>   `$GITHUB_WORKSPACE`. Musiał się przenieść również checkout własnego repo
>   (`shared/microservice-security`, `portal/microservice-offboarding`) — dopiero cały układ
>   workspace'owy sprawia, że ścieżki testów mają jedno znaczenie w CI i lokalnie. Przy okazji
>   dwie ścieżki, które w płaskim układzie działały PRZYPADKIEM (`offline-jwt`,
>   `microservice-email`), działają teraz z premedytacją.
> - **poz. 17 — podpowiedź Fable była błędna.** „Konektor in-memory rejestruje kanał w profilu
>   testowym" — nie rejestruje: pod `%test` raport zdrowia nie ma `data` w ogóle, więc asercji
>   o kanale nie da się tam napisać. Powstał `ChannelReadinessTest` z własnym profilem, który
>   podstawia konektor Kafki i wskazuje go na martwy broker — kanał jest wtedy NAZWANY w raporcie
>   (DOWN, i o to chodzi: test jest o enrollmencie, nie o zdrowiu).
> - **poz. 13 — potwierdzone, że to WYSOKI, nie ŚREDNI.** Interceptor rekordów trzeba było wpiąć
>   przez `ContainerCustomizer`, bo goły bean `RecordInterceptor` nie wie, KTÓREGO kontenera
>   dotyczy — a znacznik jest per kontener. Test wiringu (`ListenerHeartbeatTest`) sprawdza, że
>   interceptor faktycznie siedzi na kontenerach; bez tego `recordDelivered` byłoby martwym kodem,
>   czyli dokładnie klasą wady z poz. 10.
> - **Nowy test potrafi zepsuć cudzy.** `DlqLedgerKeysTest` zostawiał wpis we wspólnym (na całą
>   suitę `@QuarkusTest`) ledgerze i wywracał `DlqRedriveTest`. Sprząta po sobie retrakcją, czyli
>   ścieżką produkcyjną.
> - **`npm test` w memes-ui nie idzie na Node 20** (undici/jsdom: `markAsUncloneable`). Działa na
>   Node 22 — tym z `memes-ui/target/node`, czyli tym samym, którego używa CI. Nie jest to regres.
> - **Strażnik manifestu z poz. 18 nie mógł być bezwarunkowy.** `k8s/base/comments.yaml` leży
>   w repo WORKSPACE'u, a własne CI comments klonuje tylko rodzeństwo — bez `assumeTrue` test
>   świeciłby na czerwono przy każdym pushu tam, za plik, którego nigdy nie miało tam być. Biegnie
>   lokalnie i w CI reaktora (który układa repa workspace'owo).
>
> ### Dowód wykonania
>
> - `mvn verify` zielony w każdym ruszanym repo: security 107, email 42, comments 134, memes 157,
>   offboarding — bez pominięć.
> - `memes-ui` vitest: 5/5 (Node 22 z `target/node`).
> - **e2e w przeglądarce na żywym stacku** (`memes-ui/run-e2e.sh`, cucumber-js + Playwright,
>   docker compose): **18 scenariuszy, 166 kroków, wszystko zielone.**
> - **e2e sagi usuwania konta** (`e2e-saga.sh`, HTTP po żywym stacku): **4 scenariusze, 21 kroków,
>   zielone** — to jest realna weryfikacja poz. 5, bo listener wyników biegnie tam z nową strategią
>   offsetów i błędów.
> - Każda naprawa ma test, który sprawdzono, że pada po cofnięciu poprawki (metoda z sekcji „Zasady
>   pracy" niżej).
>
> ### Co zostaje na P16 (nie było znaleziskiem Fable, wyszło przy naprawie)
>
> - Po wyczerpaniu retry `OffboardingOutcomeListener` ZATRZYMUJE kontener (świadomie: lepiej
>   nieprzeczytany rekord, który odtworzy restart, niż po cichu wyrzucony). Ale security nie ma
>   lampy zdrowia listenera — tego, co comments/memes/collections/offboarding już mają. Zatrzymany
>   listener jest tam dziś niewidoczny.
>
> ### Kolejność paczek (ustalona 2026-07-29, priorytet od góry)
>
> **PACZKA A — CI paktów** — poz. **2, 3, 8** (2×WYSOKI, 1×ŚREDNI)
> Pierwsza NIE dlatego, że najcięższa, tylko dlatego, że każda kolejna poprawka przechodzi
> przez bramkę, która dziś niczego nie dowodzi — a `dependabot-auto-merge` tej zieleni ufa.
> Trzy kontrakty (`GET /me`, `ACCOUNT_DELETION_REQUESTED`, kaskada `COMMENTS_DELETED`) nie są
> weryfikowane w żadnym CI, a strażnicy pominięć są ślepi tym samym błędem ścieżki.
> Naprawa jest tania: głównie ścieżki checkoutów. **Dowód wykonania: przebieg CI pokazujący,
> że testy providerowe SIĘ WYKONAŁY, a nie zostały pominięte.**
>
> **PACZKA B — realne zachowanie w runtime** — poz. **5, 13, 6, 12, 21, 19** (1×WYSOKI, 2×ŚREDNI, 3×NISKI)
> Jedyne pozycje z realnym wpływem na użytkownika.
> - poz. 5 — listener wyników jest at-most-once: treść wymazana, konto odblokowane, `ACCOUNT_DELETED` nigdy nie wychodzi.
> - poz. 13 — **podnieść ponad ocenę Fable.** Znacznik lampy zdrowia postępuje wyłącznie przy
>   PUSTYCH pollach. Przy drenażu zaległości po awarii brokera zdrowa pętla zapala readiness
>   na czerwono i przy `replicas: 1` jedyny pod wypada z Service dokładnie wtedy, gdy system
>   się leczy. Fable dał ŚREDNI; operacyjnie zachowuje się jak WYSOKI.
>
> **PACZKA C — uczciwość testów i komentarzy** — poz. **9, 10, 11, 14, 15, 16, 17, 18, 20, 22, 24** (11 pozycji)
> Najliczniejsza, każda mała. Część wymaga DECYZJI „poprawić kod czy poprawić komentarz"
> (poz. 14 — `GET /sessions` pokazuje wygasłe sesje jako aktywne; poz. 22 — kontrakt HTTP
> obiecuje `refreshToken` w ciele, którego kod celowo nie wysyła).
> **Poz. 9 i 16 to testy napisane 2026-07-29 przez Opusa** — `ProbeUrlsTest` sam sobie podaje
> właściwości, które rzekomo weryfikuje.
>
> **PACZKA D — reszta k8s** — poz. **7, 23** (1×ŚREDNI, 1×NISKI). Może jechać razem z wdrożeniem.
>
> ### Zasady pracy nad tymi poprawkami
> - Fable robi przegląd, Opus pisze kod — implementacja zawsze w głównej pętli, nie w subagencie.
> - Każda naprawa to najmniejsza zmiana zamykająca wadę (Fable: „zwykle 1–5 linii plus test,
>   który wcześniej nie mógł zawieść, a teraz może").
> - **Test musi umieć paść** — po napisaniu cofnąć poprawkę i sprawdzić, że świeci na czerwono.
>   Dwie pozycje w tym planie istnieją dlatego, że ten krok pominięto.
> - Po każdej paczce: pełny `mvn verify` + e2e w przeglądarce, i dopiero wtedy push.


**Podsumowanie.** Estate po dniu wielkich migracji jest zaskakująco zdrowy w warstwie domenowej — ani jedno znalezisko nie dotyczy logiki biznesowej; wszystkie 24 pozycje to bramki, które nie strzegą tego, co deklarują, manifesty k8s rozjechane z intencją oraz komentarze obiecujące więcej, niż robi kod. **Dwie rzeczy blokują wyjście na k3s**: ulubione są martwe w klastrze z zerowym śladem (poz. 1) i proxy `/memes/` w collections-ui nie zadziała w żadnym podzie (poz. 5). Reszta nie blokuje wdrożenia, ale dziury w CI paktów (poz. 2–3) trzeba zamknąć, zanim dependabot-auto-merge dostanie kolejną zieleń, której nie wolno ufać.

---

## KRYTYCZNY

### 1. API user-collections jest nieosiągalne z przeglądarki — ulubione martwe w klastrze, zero śladu po żadnej stronie
*(znaleziska 17 + 0 — jedna wada, dwie nogi)*
**Plik:** `portal/k8s/base/ingress.yaml:38` oraz `portal/k8s/base/memes.yaml:89`, `portal/k8s/base/collections-ui.yaml:40-41`
**Co robi kod:** Ingress kieruje host `collections.portal.localhost` wyłącznie na serwis `collections-ui` (statyczny nginx z dwiema lokacjami: `/memes/` i SPA-owe `try_files`). Serwis `user-collections:8092` nie ma żadnej trasy — ani w base, ani w overlay'u dev. Tymczasem `MEMES_UI_COLLECTIONS_URL` i `UI_COLLECTIONS_URL` (commit 8dbeba1) każą OBU bundle'om przeglądarkowym wołać `${COLLECTIONS}/collections/favourites/items` właśnie pod ten martwy host.
**Awaria:** Po deployu na k3s: klik w gwiazdkę → GET dostaje `index.html` jako 200 text/html bez nagłówków CORS, PUT/DELETE dostają 405 — przeglądarka blokuje wszystko po swojej stronie. Ulubione nie zapisują się i nie ładują z żadnego UI, user-collections nie loguje NIC (żaden request tam nie dociera), wszystkie sondy zielone. `COLLECTIONS_ALLOWED_ORIGINS` w `user-collections.yaml:57-58` — ustawione wprost „dla gwiazdki galerii" — to martwa konfiguracja. Dokładnie ten sam kształt awarii co CORS-owy sign-in, którego diagnoza zajęła dzień — odtworzony dla nogi kolekcji w dniu, w którym naprawiono nogę security.
**Najmniejsza naprawa:** Druga reguła path w ingressie pod tym samym hostem: `/collections` (Prefix) → `user-collections:8092`, przed regułą `/` — wtedy obie istniejące zmienne UI i `COLLECTIONS_ALLOWED_ORIGINS` są poprawne bez zmian. Do tego jeden krok e2e na stacku k3s, który klika gwiazdkę.

---

## WYSOKI

### 2. Weryfikacja paktów portalowych security nie biega w ŻADNYM CI — a workflow twierdzi, że biega, i strażnik pominięć jest ślepy tym samym błędem ścieżki
*(znaleziska 22 + 8 — jedna wada)*
**Plik:** `shared/microservice-security/.github/workflows/ci.yml:67,79` (checkouty), `MeIntrospectionPactProviderTest.java:41`, `OffboardingFactsPactProviderTest.java:31`, `SilentlySkippedPactTest.java:35`
**Co robi kod:** CI robi checkout konsumentów PŁASKO (`path: microservice-memes` itd.), a testy providerowe od commitu 533fdb3 szukają paktów pod `../../../portal/...` — co w CI wskazuje na RODZICA `$GITHUB_WORKSPACE`, gdzie nic nie istnieje. `@EnabledIf` pomija obie weryfikacje w każdym przebiegu. `SilentlySkippedPactTest` sprawdza tę samą błędną lokalizację, uznaje „konsument faktycznie niesklonowany" i przechodzi pusto. CI agregatora shared nie ma checkoutu portalu, a CI reaktora portalu instaluje kernel z `-DskipTests` — więc kontrakty `GET /me` i `ACCOUNT_DELETION_REQUESTED` nie są weryfikowane nigdzie. Nagłówek workflow (linie 5-7) twierdzi odwrotnie.
**Awaria:** Zmiana kształtu faktu `ACCOUNT_DELETION_REQUESTED` (np. `id`, na którym `EventsRouter` deduplikuje i który przy braku traktuje jak poison pill) albo `GET /me` przechodzi na zielono wszędzie — łącznie z zielenią, na której merguje dependabot-auto-merge — i sagi usuwania kont po cichu przestają startować (WARN tylko po stronie offboardingu). To reprodukcja awarii z podziału workspace'u 2026-07-12 wewnątrz CI zbudowanego w odpowiedzi na nią.
**Najmniejsza naprawa:** Dwie linie: `path: portal/microservice-memes` i `path: portal/microservice-offboarding` w checkoutach. Potem jeden przebieg CI z dowodem, że oba testy się WYKONAŁY, nie pominęły — od tej chwili SilentlySkippedPactTest zacznie gryźć, i o to chodzi.

### 3. CI comments nigdy nie weryfikuje paktu kaskady COMMENTS_DELETED — provider test zawsze pominięty, strażnik rozbrojony tym samym brakiem
**Plik:** `portal/microservice-comments/.github/workflows/ci.yml:54`, `CommentsDeletedPactProviderTest.java:62-73`
**Co robi kod:** CI robi checkout microservice-offboarding, ale NIE robi checkoutu microservice-user-collections. `@EnabledIf(consumerPactCheckedOut)` na pliku `../microservice-user-collections/pacts/...` jest więc w CI zawsze false → test SKIPPED. `SilentlySkippedPactTest` robi `assumeTrue(katalog istnieje)` — w CI założenie pada i strażnik też jest pominięty. Krok nazywa się „Build and test (including pact provider verification)".
**Awaria:** Zmiana producenta (topic, `commentIds`, `memeId`) przechodzi na zielono, auto-merge merguje, a user-collections przestaje sprzątać zapisane komentarze po skasowanych memach — po cichu, przy zielonych suitach po obu stronach. Dokładnie scenariusz, przed którym miał chronić dzisiejszy commit c33e182.
**Najmniejsza naprawa:** Jeden checkout `jrobertgardzinski/microservice-user-collections` (path: `microservice-user-collections`) — sam katalog `pacts/` wystarczy, bez budowania.

### 4. collections-ui w k8s zachowuje docker-owy resolver 127.0.0.11 — proxy `/memes/` daje 502 na każde żądanie, wykrywanie skasowanych memów nigdy nie działa w klastrze
**Plik:** `portal/k8s/base/collections-ui.yaml:37` (env), `collections-ui/Dockerfile:31-33`, `nginx.conf.template:33`
**Co robi kod:** Deployment ustawia tylko trzy zmienne UI_*; obraz ma domyślne `MEMES_RESOLVER=127.0.0.11` (wbudowany DNS Dockera) i `MEMES_UPSTREAM=http://memes:8083`, a komentarz w samym Dockerfile mówi, że wdrożenie k8s MUSI nadpisać resolver. Manifest nie nadpisuje niczego. Dodatkowo resolver nginxa ignoruje search domains, więc goła nazwa `memes` i tak by się nie rozwiązała.
**Awaria:** W podzie nie ma DNS-a na 127.0.0.11 → każde `/memes/...` kończy się 502 → UI celowo renderuje 502 jako „couldn't check", nigdy jako ofertę usunięcia. Read-repair skasowanych memów po cichu nigdy nie funkcjonuje, sondy zielone, cała racja bytu proxy (odróżnienie „mem zniknął" od „galeria leży") martwa.
**Najmniejsza naprawa:** W `collections-ui.yaml` dodać `MEMES_RESOLVER=kube-dns.kube-system.svc.cluster.local` i `MEMES_UPSTREAM=http://memes.portal.svc.cluster.local:8083` (FQDN — konieczny, bo resolver nie stosuje search domains).

### 5. Listener wyników offboardingu jest at-most-once: wyjątek w handlerze pomija wynik na zawsze i zamienia ukończony purge w kompensację
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/OffboardingOutcomeListener.java:23`
**Co robi kod:** `@KafkaListener(groupId="security", offsetReset=EARLIEST)` bez errorStrategy i strategii offsetów; application.yml konfiguruje tylko bootstrap servers. Domyślne micronaut-kafka: wyjątek z `handle()` (np. rollback `transactionBoundary.execute` przy chwilowej awarii DB) jest logowany, pętla idzie dalej, offset auto-commituje — rekord nigdy nie wraca. Javadoc „Idempotent by way of the saga latch" chroni przed przetworzeniem dwa razy, nie zero razy. Sweeper offboardingu przestaje ponawiać, gdy JEGO outboxowy znacznik siada — niezależnie od sukcesu security.
**Awaria:** Portal ogłasza `PORTAL_CONTENT_PURGED`, DB security ma chwilową czkawkę → wynik przepada. Saga wisi STARTED aż `compensateOverdue` odblokuje konto i wyśle przeprosiny — a portal już wymazał wszystkie memy, komentarze i kolekcje. Użytkownik zostaje z kontem bez treści, `ACCOUNT_DELETED` nigdy nie wychodzi, a dedykowany `LOG.error` „CONTENT ERASED AFTER COMPENSATION" nigdy nie odpala.
**Najmniejsza naprawa:** `errorStrategy = @ErrorStrategy(RETRY_ON_ERROR)` z retry/backoff plus `OffsetStrategy.SYNC` po udanym przetworzeniu — istniejący claim po id już czyni redelivery bezpiecznym.

---

## ŚREDNI

### 6. Parkowane maile bez id łamią kontrakt kluczy ledgera DLQ: kompakcja niszczy starsze rekordy, retrakcje celują w zły klucz
**Plik:** `shared/microservice-email/src/main/java/com/jrobertgardzinski/mail/boundary/MailRequestsConsumer.java:227,243-250`, `ParkedMails.java:52`
**Co robi kod:** `eventId()` zwraca literał `"<no id>"` dla każdego eventu bez id — więc wszystkie takie rekordy dzielą JEDEN klucz Kafki na topicu `cleanup.policy=compact`. Równolegle `ParkedMails` nadaje im INNY, niestabilny klucz w pamięci: `"unidentified-" + ledger.size()` (nienotoniczny — `take()` i eviction go cofają), a `markRedriven` pisze retrakcję pod kluczem ledgera, nigdy pod faktycznym kluczem topicu.
**Awaria:** Dwa maile bez id zaparkowane → kompakcja zostawia tylko ostatni: jedyny trwały zapis pierwszego niedostarczonego maila zniszczony przez brokera bez linii logu — dokładnie ciche niszczenie dowodów, przed którym commit 8a8eada miał chronić. Ponadto: nadpisania w pamięci przy kolizji `unidentified-N` oraz zmartwychwstawanie rozstrzygniętych maili po restarcie (retrakcja nigdy nie trafia) i duplikat maila reset/MFA przy drugim redrive.
**Najmniejsza naprawa:** Stabilna syntetyczna tożsamość nadana RAZ przy parkowaniu (np. `"unidentified-" + UUID`), zapisana do JSON-a i używana jako klucz topicu, ledgera i retrakcji — zawsze ten sam string, zawsze unikalny.

### 7. kafka-topics-init to jednorazowy Job strzegący topicu na brokerze z emptyDir — po każdym odtworzeniu poda ledger DLQ po cichu wraca do delete/7 dni
**Plik:** `portal/k8s/base/kafka.yaml:93`
**Co robi kod:** Broker trzyma dane na `emptyDir: {}`, a komentarz twierdzi, że jedyny wyjątek („utrata stanu brokera akceptowalna, poza mail-requests-dlq") jest pokryty Jobem. Job k8s biega raz; re-apply kustomization nie uruchamia go ponownie, nic nie wiąże go z cyklem życia poda. Compose robi to inaczej — re-asertuje config przy każdym `up`.
**Awaria:** Odtworzenie poda kafki (reboot, ewikcja, k3d stop/start) → topici znikają → `AUTO_CREATE_TOPICS` odtwarza DLQ z domyślnymi `delete`/7 dni → zaparkowane maile weryfikacja/reset/MFA wygasają z ledgera, podczas gdy `/mails/dlq` dalej odpowiada 200 z kurczącą się listą.
**Najmniejsza naprawa:** Przenieść create/alter topicu do initContainera samego Deploymentu kafki (skrypt już jest idempotentny); ewentualnie minimalnie — poprawić komentarz w kafka.yaml, że gwarancja obowiązuje tylko do pierwszego przeplanowania brokera.

### 8. Własne CI offboardingu po cichu pomija pakt wyników security — ten, którego javadoc mówi „kontraktowy test, który nie biega, to najgorsze, czym może być"
**Plik:** `portal/microservice-offboarding/.github/workflows/ci.yml:36`, `SecurityOutcomePactProviderTest.java:41`
**Co robi kod:** Checkout microservice-security jest płaski (`$GITHUB_WORKSPACE/microservice-security`), a test rozwiązuje `../../shared/microservice-security/pacts` — `$GITHUB_WORKSPACE/../shared/...`, które w CI nie istnieje → weryfikacja `PORTAL_CONTENT_PURGED`/`PORTAL_PURGE_FAILED` zawsze SKIPPED, a checkout security nie służy niczemu. Cztery pakty komend treści działają tylko przypadkiem (płaski layout pokrywa się z sąsiedztwem portalowym o poziom wyżej).
**Awaria:** Push do offboardingu zmieniający kopertę wyniku (`id`/`type`/`email`, na których routuje `EventsRouter.outcome()`) przechodzi na zielono we WŁASNYM CI; łapie go dopiero — o ile w ogóle — CI reaktora portalu, czyli bramka w innym repozytorium niż push.
**Najmniejsza naprawa:** Jedna linia: `path: shared/microservice-security` w checkoutcie. Opcjonalnie offboardingowy bliźniak SilentlySkippedPactTest.

### 9. ProbeUrlsTest (comments) strzeże adresów sond własnymi propertiesami — wysyłana linia exposure.include nie jest przypięta żadnym testem
**Plik:** `portal/microservice-comments/src/test/java/com/jrobertgardzinski/comments/infrastructure/ProbeUrlsTest.java:42`
**Co robi kod:** Test podaje `management.endpoints.web.exposure.include=health,prometheus` jako WŁASNĄ właściwość kontekstu (komentarz: „as shipped"). Domowy wzorzec dwóch połówek przypina inne linie z `src/main/resources/application.properties`, ale linii exposure (linia 78) nie czyta ŻADEN test. „Verified these tests can fail" z commitu 5ef327c dotyczyło listy podanej testowi, nie pliku jadącego do obrazu.
**Awaria:** Ktoś zmienia wysyłaną linię na np. samo `prometheus` → suita w całości zielona, a w klastrze `/actuator/health/readiness` i `/liveness` odpowiadają 404: startupProbe nigdy nie przechodzi, pod nigdy nie jest Ready — klasa awarii, którą ten test wg własnego javadocu miał zamykać.
**Najmniejsza naprawa:** Połówka nr 1 wg istniejącego wzorca: wczytać wysyłany `application.properties` i zaasertować, że exposure.include zawiera `health`.

### 10. Race wokół rotacji w in-memory adapterze: monitor z javadocu nie istnieje, zadeklarowane pole `sessionLineage` nigdy nie użyte
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/InMemoryAuthorizationDataRepository.java:44`
**Co robi kod:** Commit 384b6a7 (P14-13) i javadoc twierdzą, że wspólny monitor czyni z create/markRotated/revokeFamily transakcję. W rzeczywistości metody są tylko per-metoda `synchronized(this)` (każda i tak atomowa przez ConcurrentHashMap), pole `sessionLineage` — „One monitor over the three operations" — jest zadeklarowane i nigdy nieużyte, `revokeAllSessions` nie jest synchronized wcale, a `RefreshSession.execute` woła `markRotated` i `create` jako dwa osobne wywołania bez locka pomiędzy (pod NoTransactionBoundary).
**Awaria:** W każdym uruchomieniu bez DataSource (to produkcyjny wiring, nie testowy): T1 rotuje sesję S, T2 z kradzionym już-zrotowanym tokenem tej rodziny dostaje ReuseDetected i wykonuje `revokeFamily` MIĘDZY dwoma wywołaniami T1 — T1 tworzy następcę: żywa sesja w rodzinie, którą serwis uważa za w całości unieważnioną. Dokładnie wynik, który commit ogłasza jako naprawiony.
**Najmniejsza naprawa:** Jedna metoda repozytorium rotate-and-create pod `synchronized(sessionLineage)`, z revokeFamily/revokeAllSessions na tym samym monitorze. Minimum: usunąć martwe pole i sprostować javadoc/commit do tego, co per-metoda synchronized faktycznie daje.

### 11. Krok e2e „signing in with that account is refused" nie może zawieść dla scenariusza MFA — konto z faktorem nigdy nie odpowiada 200
**Plik:** `portal/microservice-memes/memes-ui/e2e/steps/identity.steps.mjs:133`
**Co robi kod:** Krok asertuje wyłącznie `expect(r.status).not.toBe(200)`. Własny `world.mjs` harnessu dokumentuje, że konto z faktorem odpowiada 202 z ticketem, a bliźniaczy krok „still works" przyjmuje `[200, 202]` jako dowód życia. Scenariusz 5 (`A second factor is asked for on the way out too`) kończy się na tym kroku jako JEDYNYM dowodzie, że usunięcie się dokonało — a dla konta z faktorem `/authenticate` zwraca 202 niezależnie od tego, czy konto żyje, jest zablokowane czy usuwane.
**Awaria:** Regresja ścieżki step-up (potwierdzenie kodem pokazuje „started", fakt nigdy nie doappendowany): scenariusze bez faktora zielone zasłużenie, scenariusz 5 — jedyne pokrycie wyjścia z MFA — zielony niezasłużenie, bo w pełni żywe konto już spełnia `.not.toBe(200)` przy pierwszym pollu.
**Najmniejsza naprawa:** Asertować jawną odmowę: brak `accessToken` I brak `mfaTicket` w ciele, albo przypiąć konkretne statusy (`[401, 403]`).

### 12. Kafelek ulubionych: „cache-busting" `?wall=favourites` to stały URL cache'owany godzinę — stan pamiątki po skasowanym memie po cichu nigdy się nie włącza
**Plik:** `portal/microservice-memes/memes-ui/src/App.tsx:400`, `MemeController.java:172-173`
**Co robi kod:** Komentarz obiecuje „a distinct URL forces a real answer", ale URL `/memes/${memeId}/thumbnail?wall=favourites` jest stały per mem, a MemeController stempluje KAŻDĄ odpowiedź thumbnail — query włącznie — `Cache-Control: public, max-age=3600`. Query odróżnia tylko wpis cache ściany od galerii. Test „asks for a thumbnail URL the browser cache cannot answer from" asertuje wyłącznie literał stringa src — nie może zawieść z powodu, który nazywa.
**Awaria:** Ściana otwarta o 12:00, mem skasowany o 12:05, sweep MEME_DELETED opóźniony (dokładnie okno, dla którego pamiątka istnieje): powrót o 12:10 maluje miniaturę z cache bez żadnego requestu, `onError` nie odpala, kafelek nigdy nie staje się pamiątką, a klik otwiera dialog, którego każdy fetch 404uje.
**Najmniejsza naprawa:** Uczynić twierdzenie prawdziwym (krótki/`no-store` Cache-Control dla `?wall=favourites` w MemeController albo fetch z `cache: 'no-cache'`) — lub uczciwym: przeredagować komentarz i nazwę testu, że mechanizm działa raz na godzinę.

### 13. Lampa SagaListenersHealth czyta ciągły ruch jako martwą pętlę — znacznik postępuje wyłącznie przy PUSTYCH pollach, wbrew własnemu javadocowi
**Plik:** `portal/microservice-comments/src/main/java/com/jrobertgardzinski/comments/infrastructure/SagaListenersHealth.java:57,118-126,161-166`
**Co robi kod:** Jedyne źródła znacznika to `ListenerContainerIdleEvent` (tylko puste polle) i `NoLongerIdleEvent` (tylko przejście idle→ruch). Podczas nieprzerwanej konsumpcji nie ma żadnych zdarzeń, znacznik stoi, a po 150 s health() orzeka DOWN. Javadoc „the marker advances while the loop polls, whether or not there is anything to consume" jest fałszywy dla pętli, która stale MA co konsumować; tolerancja 150 s wyprowadzona z retry JEDNEGO rekordu.
**Awaria:** Drenaż zaległości >150 s bez jednego pustego polla (burza duplikatów po awarii brokera — scenariusz opisany we własnych manifestach przy `strategy: Recreate`) → zdrowa, mieląca pętla zapala readiness na czerwono; przy `replicas: 1` jedyny pod wypada z Service dokładnie wtedy, gdy system się leczy. Fałszywy alarm dla operatora uczonego, że czerwona lampa = martwy listener.
**Najmniejsza naprawa:** Stemplować znacznik także przy przetworzonych rekordach (RecordInterceptor/BatchInterceptor w kontenerach jako heartbeat). Do czasu poprawki — sprostować javadoc.

### 14. GET /sessions raportuje wygasłe sesje jako aktywne przez pełne okno grace, choć javadoc reapera przedstawia to kłamstwo jako naprawione
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/persistence/JdbcAuthorizationDataRepository.java:84`
**Co robi kod:** `listActiveSessions` filtruje wyłącznie po `status='ACTIVE'` — bez predykatu wygaśnięcia (adapter in-memory tak samo). ExpiredSessionReaper — którego javadoc mówi „status is not the truth, the expiry column is" i opisuje kłamstwo listingu w czasie przeszłym — celowo trzyma wygasłe wiersze max(okno refresh, 24h) po wygaśnięciu, plus do 1h interwału, a listing dalej ufa samemu statusowi.
**Awaria:** Użytkownik po password-scare sprawdza „gdzie jestem zalogowany": przez ~24-25h widzi urządzenie-widmo z `expiresAt` w przeszłości, którego żaden revoke nie usunie wcześniej niż reaper — martwa sesja prezentowana jako aktywna.
**Najmniejsza naprawa:** Predykat wygaśnięcia w ścieżce listingu: `findByEmailAndStatusAndRefreshTokenExpirationAfter(email,'ACTIVE',now)` + odpowiednik zegarowy w in-memory.

### 15. Javadoc testu twierdzi, że RequireSignInFilter biega PRZED filtrem rozmiaru („anonimowy oversized dostaje 401") — kolejność filtrów dowodzi odwrotności
**Plik:** `portal/microservice-memes/memes-infrastructure/src/test/java/.../RejectOversizedUploadFilterTest.java:24`, `RejectOversizedUploadFilter.java:41`
**Co robi kod:** Filtr rozmiaru ma `@Order(HIGHEST_PRECEDENCE+10)`; RequireSignInFilter nie ma `@Order` wcale → rejestrowany na LOWEST_PRECEDENCE, więc filtr rozmiaru biega PIERWSZY. Anonimowy POST /memes z deklaracją 20 MB dostaje 413 `TOO_LARGE` — nigdy 401.
**Awaria:** Czytelnik komentarza wnioskuje z odwróconego łańcucha (i dokładnie na tej podstawie klasa testowa pomija test anonimowego wołającego). Klasa wady nr 3 wg standardu tego projektu — z precedensem na tym samym filtrze, poprawianym już 2026-07-29.
**Najmniejsza naprawa:** Sprostować zdanie do faktycznej kolejności (anonimowy oversized → 413, nie 401) — albo dać RequireSignInFilter jawny `@Order` poniżej filtra rozmiaru i przypiąć twierdzenie testem.

---

## NISKI

### 16. ProbeUrlsTest w memes — ten sam wzór co w comments: test dostarcza sobie właściwości, które ma weryfikować
**Plik:** `portal/microservice-memes/memes-infrastructure/src/test/java/.../ProbeUrlsTest.java:38-42`
**Co robi kod:** `@SpringBootTest` sam podaje `probes.enabled=true`, obie definicje grup ORAZ `exposure.include=health,prometheus` — duplikując `application.properties:124-138` zamiast je czytać; adnotacyjne properties nadpisują wysyłany plik. Bliźniak w comments dodatkowo siedzi za testowym `application.properties`, który całkiem przesłania wysyłany.
**Awaria:** Usunięcie `health` z wysyłanej listy exposure → wszystkie testy memes i comments zielone, w klastrze readiness 404, pod nigdy nie Ready. „Commit, który zweryfikował, że testy umieją zawieść" (d18b134) weryfikował usunięcie z listy testu, nie z pliku.
**Najmniejsza naprawa:** Usunąć z properties testu wszystko poza collapse `management.server.port=`, by test ćwiczył wysyłaną konfigurację; w comments raz przekopiować trzy linie management do testowego pliku. (Robić razem z poz. 9.)

### 17. Test readiness maila nie asertuje tego, co twierdzi nazwa — kanał mail-requests nigdy nie jest sprawdzany
**Plik:** `shared/microservice-email/src/test/java/com/jrobertgardzinski/mail/boundary/HealthEndpointTest.java:35`
**Co robi kod:** `@DisplayName` mówi „names the mail-requests channel among its checks", komentarz nalega „The channels have to be what is being reported on" — a jedyna asercja to `checks.name` zawiera „SmallRye Reactive Messaging - readiness check": nazwa parasola, żadnego kanału. String „mail-requests" nie występuje w teście.
**Awaria:** Kanał po cichu wypada z raportu readiness (rename, zmiana enrollmentu przy upgradzie SmallRye — ta sama klasa cichej zmiany defaultu co CORS w Micronaut 5) → test zielony, martwy pipeline mailowy raportuje się zdrowy — sytuacja, dla której wg własnego javadocu test powstał.
**Najmniejsza naprawa:** Doasertować dane checka: klucz `mail-requests` z wartością UP (in-memory connector rejestruje kanał w profilu testowym).

### 18. CorsOriginsTest deklaruje „exactly what k8s/base/comments.yaml sets", a manifest ustawia inną wartość — strażnik nie czyta manifestu, którego pilnuje
**Plik:** `portal/microservice-comments/src/test/java/.../CorsOriginsTest.java:77`, `portal/k8s/base/comments.yaml:58-59`
**Co robi kod:** Test używa listy `http://localhost:8083,http://memes.portal.localhost:9080`; manifest ustawia POJEDYNCZĄ wartość `http://memes.portal.localhost:9080`. Test nie czyta yaml-a — dziś rozjazd czysto dokumentacyjny (wartość w klastrze działa).
**Awaria:** Ktoś czyści env w manifeście (np. przenosiny do configMapy) i usuwa `UI_ORIGIN` → suita zielona, wraca dokładnie ta bezśladowa awaria CORS, dla której test powstał: przeglądarka odmawia, zero logów serwisu, pod Ready.
**Najmniejsza naprawa:** Zsynchronizować wartości i poprawić komentarz; jeśli ma być strażnikiem manifestu — asercja czytająca `../k8s/base/comments.yaml` (wzorzec SilentlySkippedPactTest).

### 19. Szczegóły lampy zdrowia nigdy nie docierają do operatora — show-details ma domyślne „never" w każdym realnym wdrożeniu
**Plik:** `portal/microservice-comments/src/main/java/.../SagaListenersHealth.java:133` + brak `show-details` w application.properties / compose / k8s
**Co robi kod:** Javadoc obiecuje szczegóły („The details name container ids, states and ages", rozróżnienie „thread died" vs „stalled poll"), a cały projekt szczegółów bez PII zakłada czytelnika. Nigdzie nie ustawiono `management.endpoint.health.show-details` → domyślne Boot „never" → każda odpowiedź health w compose i klastrze to gołe `{"status":"..."}`. Dowód w repo: ProbeUrlsTest musiał sam dodać `show-details=always`, by zobaczyć `$.components`.
**Awaria:** Listener staje → readiness 503 z `{"status":"DOWN"}` i niczym więcej; diagnoza, którą lampa miała dawać z półki, nie istnieje w żadnym środowisku, w którym lampa działa.
**Najmniejsza naprawa:** `management.endpoint.health.show-details=always` w application.properties (szczegóły są celowo wolne od PII) + przypiąć testem połówki nr 1; albo sprostować javadoc.

### 20. Javadoc RateLimitFilter twierdzi „AFTER the API key", ale oba filtry dzielą priorytet 5000 — kolejność niespecyfikowana
**Plik:** `shared/microservice-email/src/main/java/.../RateLimitFilter.java:16`, `ApiKeyFilter.java:17-18`
**Co robi kod:** RateLimitFilter ma `@Priority(USER)`=5000; ApiKeyFilter nie ma `@Priority` → domyślne to samo 5000. Kolejność przy równych priorytetach spec pozostawia niezdefiniowaną — nic nie wymusza udokumentowanej sekwencji.
**Awaria:** Tie-break kontenera (może się odwrócić przy upgradzie Quarkusa) stawia limiter pierwszy → 120 bezkluczowych żądań/min trzyma okno pełne: zaufani wołający z poprawnym X-Api-Key dostają 429 zamiast 202, a log nie odróżnia tego od legalnego ruchu.
**Najmniejsza naprawa:** `@Priority(Priorities.AUTHENTICATION)` na ApiKeyFilter — udokumentowana kolejność staje się wymuszona.

### 21. Redrive wpisu ledgera bez pola „event" NPE-uje 500 i po cichu usuwa wpis z okna operatora
**Plik:** `shared/microservice-email/src/main/java/.../DlqResource.java:48`
**Co robi kod:** `redrive()` wykonuje `parked.take(id)` — USUWA wpis — a potem `record.get("event").toString()` bez null-checka. ParkedMails celowo wpuszcza rekordy bez „event" (czyta id null-safe ścieżką właśnie dlatego), więc są listowalne i redrive'owalne.
**Awaria:** Operator POSTuje redrive „unidentified-0" na rekordzie bez „event" (obcy/ręcznie opublikowany na wiecznym topicu): NPE → 500, a wpis — już zabrany — znika z ledgera do następnego restartu. Endpoint zbudowany, by martwe listy były widoczne, czyni jedną z nich niewidzialną.
**Najmniejsza naprawa:** Sprawdzić kształt przed konsumpcją: `event == null` → odłożyć rekord do ledgera i zwrócić 422 z surowym JSON-em.

### 22. Udokumentowany kontrakt HTTP AuthenticationController obiecuje refreshToken w ciele 200, którego kod celowo nigdy nie wysyła
**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/AuthenticationController.java:33`
**Co robi kod:** Javadoc: `Authenticated → 200 OK, {"accessToken": ..., "refreshToken": ...}`. Implementacja zwraca `Map.of("accessToken", ...)`, refresh token idzie wyłącznie HttpOnly cookie — co jest przesłanką całej dzisiejszej naprawy CORS allow-credentials.
**Awaria:** Autor klienta implementujący z kontraktu czyta `refreshToken` z ciała, dostaje null i buduje refresh flow, który nie może działać — a faktyczny mechanizm (credentialed cookie) wymaga opcji fetch, o których dokument milczy.
**Najmniejsza naprawa:** Poprawić linię 33 na `200 OK, {"accessToken": ...}` + rotowane cookie w Set-Cookie, wzorem trafnego opisu RefreshController.

### 23. Nagłówek email.yaml i README k8s twierdzą „sondy TCP-only, obraz bez health extension" — obala je własny manifest i pom
**Plik:** `portal/k8s/base/email.yaml:2-3`, `k8s/README.md:126-127`
**Co robi kod:** Nagłówek: „No /health endpoint in the image ... so the k8s probes are TCP as well" — a 40 linii niżej ten sam plik deklaruje sondy httpGet na `/q/health/started|ready|live` z komentarzem, czemu porzucono tcpSocket; pom ma quarkus-smallrye-health, compose GETuje `/q/health/ready`. Commit 82c26bd przeniósł sondy i zostawił oba dokumenty w tyle.
**Awaria:** Czytelnik wnioskuje, że martwy kanał Kafki jest dla klastra niewidzialny, i może „naprawić" manifest wstecz do tego, co opisują dokumenty.
**Najmniejsza naprawa:** Przepisać nagłówek email.yaml i linię README na stan faktyczny (HTTP `/q/health/*`).

### 24. Komentarze healthchecków compose dla memes i comments twierdzą, że grupy readiness/liveness „404 poza k8s" — oba serwisy pinują probes.enabled=true, więc grupy istnieją i w compose
**Plik:** `portal/docker-compose.yml:73` (memes) i `:162-164` (comments)
**Co robi kod:** Komentarze twierdzą, że `/actuator/health/readiness` odpowiada tu 404 — ale oba serwisy ustawiają `management.endpoint.health.probes.enabled=true` bezwarunkowo (własne komentarze w properties mówią wprost, że po to), więc endpointy grup odpowiadają 200/503, nigdy 404.
**Awaria:** Sondy działają, ale uzasadnienie jest fałszywe: debugujący flap zdrowia w compose wyklucza próbkowanie grup albo „naprawia" manifesty k8s w przekonaniu, że grupy są warunkowe względem k8s.
**Najmniejsza naprawa:** Poprawić dwa komentarze: grupy SĄ wystawione, a gołe `/actuator/health` wybrano, bo agregat (DB/broker) to właściwe pytanie dla healthchecka compose.

---

## Czego celowo NIE raportuję

- **Jackson 3 i Flyway 13** — świadome, udokumentowane odroczenia z pisemnym uzasadnieniem; nie są znaleziskami z definicji.
- **Dobór Kafki jako brokera** — decyzja zamknięta; wszystkie uwagi powyżej dotyczą wyłącznie poprawnego użycia (klucze kompakcji, strategie offsetów, konfiguracja topiców), nie doboru.
- **Observability w manifestach k8s** — celowo poza zakresem, udokumentowane; brak metryk/tracingu w yaml-ach nie jest brakiem.
- **formula/** — nie czytany, poza zakresem; ten raport nie mówi o nim nic, ani dobrego, ani złego.
- **Styl, nazewnictwo, „warto wyekstrahować", spekulatywna skalowalność** — poniżej progu znaleziska w tym projekcie; nic z tej kategorii nie było notowane, więc ich nieobecność to decyzja, nie przeoczenie.
- **Konwencja `_ClassName`** — celowa (ADR 0002), nie była zgłaszana jako problem.
- Nie proponuję żadnych nowych frameworków, warstw ani przepisań — każda naprawa powyżej to najmniejsza zmiana zamykająca konkretną wadę, zwykle 1–5 linii plus test, który wcześniej nie mógł zawieść, a teraz może.