# PLAN P13 — ślepe plamy PLAN-P12

Materiał: siedem niezależnych przeglądów obszarów, o których PLAN-P12 §5 sam napisał, że ich NIE sprawdził.
Analiza: Fable 5 (7 agentów), każdy raport przepuszczony przez adwersaryjnego weryfikatora (Fable 5).
Potwierdzono **39 znalezisk**, obalono **2**.

> **Ostrzeżenie o kalibracji.** Współczynnik obalania to 5%% (P12 miał 26%%). Weryfikatorzy nie stemplowali —
> prostowali liczby i ścinali przesadę w polu `korekta` zamiast obalać. Ale weryfikacja szła **per raport,
> nie per znalezisko**. Traktuj tę listę jako mocną, nie jako pewną.

Zakres: repozytoria `portal/` i `shared/`. Nic nie modyfikowano poza wskazanymi niżej naprawami.

---

## Testy — czego P12 nie zrobił ani razu

**shared/microservice-email (Quarkus) + shared/email (email-domain, email-security)**

Uruchomione: (1) `./mvnw -pl microservice-email test` w /home/robert/Documents/git/shared — ZIELONE: 30 testów, 0 porażek (MailResourceTest 5, MailRequestsConsumerTest 4, MailRequestsContractTest 6 — pakt pokrywa wszystkie 6 typów zdarzeń, DeadLetterTest 1, DlqRedriveTest 1, SmtpRetryTest 2, RateLimitTest 1, RunCucumberTest 10 = 5 scenariuszy). (2) `sh mvnw test` w /home/robert/Documents/git/shared/email — ZIELONE: 33 testy, 0 porażek (jqwik property-based). Żaden plik w repo nie został zmodyfikowany (git status czysty). Obserwacja z runu: JVM surefire na JDK 25 sypie przy zamykaniu `IllegalAccessError ... --add-opens java.base/java.lang` z jboss-threads — hałas w logu testów, nie obala suity i nie występuje w kontenerze prod (grep logów pusty), więc nie liczę jako znalezisko. Żywy stack odpytany read-only: /q/health → 404 (brak rozszerzenia health), /q/metrics → 200 bez klucza, /mails/dlq → 401 bez klucza; kafka-consumer-groups pokazał grupy microservice-email (lag 0 po dojściu e2e) i mail-dlq-ledger; kafka-dump-log potwierdził, że payloady AUTH_CODE niosą jawny kod (np. "code":"486858").

**shared/microservice-idp, shared/microservice-sms, shared/microservice-push (Python, stdlib-only) + ich styk z microservice-security (HttpSmsCodeChannel, OidcClient, FactorsController), compose i k8s tych trzech usług. P12 pkt 5.4: zerowe pokrycie — przejrzane od zera.**

Uruchomiłem wszystkie trzy suity (pytest nie jest zainstalowany na hoście — `python3 -m pytest` → "No module named pytest"; suity są na stdlib `unittest`, więc odpaliłem `python3 -m unittest -v` w każdym katalogu, Python 3.12.3):
- microservice-idp: Ran 5 tests — OK (0.001s)
- microservice-sms: Ran 4 tests — OK
- microservice-push: Ran 4 tests — OK
Wszystkie zielone, żadna nie jest pominięta. To dobra wiadomość, ale patrz znalezisko o CI i o zakresie tych suit — nikt ich nie uruchamia poza człowiekiem, a nie dotykają warstwy HTTP, w której siedzą wszystkie poważne błędy poniżej.
Poza tym odpytałem żywy stack (tylko odczyt + zapytania HTTP do stubu na 8091, żadnego restartu/rebuildu): `curl` na /authorize i /token, `docker logs security-idp-1 / -sms-1 / -push-1`, `docker inspect` (Config.User). Log SMS-owy z kodem MFA odtworzyłem lokalnie (`python3 -c "import server; server.send(...)"`), żeby nie dopisywać linii do logów działającego kontenera.

**shared/transactional-outbox, shared/infrastructure-spring-outbox, shared/infrastructure-micronaut-clock, shared/adjustable-clock (+ konfrontacja z użyciem w portal/microservice-memes i microservice-comments)**

Uruchomione: `./mvnw -pl transactional-outbox,infrastructure-spring-outbox,adjustable-clock,infrastructure-micronaut-clock test` w /home/robert/Documents/git/shared — BUILD SUCCESS, 63 testy w 10 klasach, 0 porażek, 0 pominiętych (transactional-outbox: 41, spring-outbox: 8, adjustable-clock: 8, micronaut-clock: 6). Suita trwa sekundy. Abstrakcja zegara FAKTYCZNIE eliminuje czas ścienny: progi minAge/retention dowodzone SteerableClock/Clock.fixed + backdate SQL, zero Thread.sleep; jedyne Instant.now() w testach to przedziałowe asercje reset()/frozenNow() (nie-flaky, testują z definicji zegar realny). Test SpringOutboxTest dowodzi rollback/commit na prawdziwym DataSourceTransactionManager, ClockEnvironmentTest dowodzi obecność/nieobecność kontrolera czasu per środowisko.

**shared/microservice-security — cykl życia sesji i refresh tokenów; shared/offline-jwt — rdzeń weryfikatora JWT**

Uruchomione wszystkie suity w obszarze, wszystkie ZIELONE: offline-jwt 8/8 (OfflineJwtVerifierTest 7 + JwksContractTest 1); microservice-security: security-domain+security-system 55/55, security-application 31/31, security-config 17/17, security-infrastructure 93 uruchomionych / 0 porażek / 2 POMINIĘTE — oba pominięcia to testy providera Pact (MeIntrospectionPactProviderTest, OffboardingFactsPactProviderTest), co samo w sobie jest znaleziskiem nr 2 (pomijają się na zawsze od podziału workspace'ów). Żadna suita nie pokrywa współbieżnego refreshu (refresh-session.feature i reuse-detection.feature są czysto sekwencyjne, RefreshSessionTest to mocki). Dodatkowo odpytany żywy Postgres (tylko SELECT): tabela sessions ma 541 wierszy, z czego 512 dawno wygasłych — dowód do znaleziska nr 4.

**portal/microservice-comments i portal/microservice-user-collections: suity BDD (feature'y + step definitions), testy jednostkowe/integracyjne, migracje Flyway (odwracalność, utrata danych, pokrycie indeksami, determinizm), weryfikacja żywych baz przez psql (tylko odczyt)**

Uruchomione: (1) `./mvnw -pl microservice-comments test` — ZIELONE, 30 suit, 0 failures/0 errors/0 skipped; w tym RunCucumberTest 16/16 scenariuszy (wszystkie z comment-thread.feature), PostgresDialectTest odpalił się naprawdę na postgres:16-alpine (Testcontainers, nie został pominięty). (2) `./mvnw -pl microservice-user-collections test` — ZIELONE; ApplicationBddTest tests=10/skipped=2 i HttpBddTest tests=10/skipped=2 — skipy to celowe filtry tagów (`not @http` / `not @saga`), bilans 8+8 przebiegów pokrywa wszystkie 10 scenariuszy z collections.feature w co najmniej jednym wejściu. (3) psql do żywych baz (odczyt): flyway_schema_history w comments = V1..V4 success=t, w collections = V1..V2 success=t; indeksy w Postgresie zgodne 1:1 z migracjami; w danych dev (161 komentarzy) zero remisów (meme_id, created_at). Werdykt migracyjny: kolejność deterministyczna (Flyway versioned), indeksy pokrywają realne zapytania purge/cascade (idx_comments_author, idx_comment_votes_voter, idx_collection_items_user, V2 (item_type,item_id) — ten ostatni jest wręcz przypięty testem ItemReferenceAxisTest czytającym katalog indeksów), jedyna destrukcyjna operacja (DELETE osieroconych głosów w V3 comments) jest udokumentowana i dotyczy wierszy nieosiągalnych; jedyna luka indeksowa (ORDER BY created_at bez pokrycia i tie-breaka) to znane N3 z P12 — nie powtarzam. Ogólny werdykt: obie suity są dziś zielone i — wbrew hipotezie z P12 5.4 — poziom uczciwości kroków jest wysoki (kroki maskowania PII, kaskady i tombstone'ów asertują dokładnie to, co obiecują); znalezione odpowiedniki N5 są punktowe, poniżej.

**e2e (portal/e2e, memes-ui/e2e, security-ui/e2e), workflowy CI w portal/, shared/ i sub-repach, skrypty e2e-saga.sh / e2e-saga-outage.sh (P12 pkt 5.4)**

Uruchomione na żywym stacku (26 kontenerów healthy, nic nie restartowano): (1) ./e2e-saga.sh — 4 scenariusze / 21 kroków, ZIELONE w 13.4s; (2) memes-ui/run-e2e.sh (Playwright, pełna suita przeglądarkowa) — 18 scenariuszy: 17 zielonych, 1 CZERWONY („A favourite outliving its meme shows as an unavailable keepsake"), powtórzony pojedynczo — czerwony deterministycznie, nie flake; (3) security-ui/run-e2e.sh (własny jar na :8180 + vite, nie dotyka compose) — 36 scenariuszy / 162 kroki, ZIELONE w 32s (przeciwko jarowi z 2026-07-20; brak nowszych commitów źródeł, więc aktualny). @outage NIE uruchomione — zatrzymuje kontener user-collections, co jest zakazane w tych zasadach. Suit Mavenowych nie uruchamiano (reaktor >> 5 min budżetu). Dodatkowo zweryfikowano przez gh: ostatni przebieg CI workspace-portal to 2026-07-15, workspace-shared 2026-07-20, a `gh repo view jrobertgardzinski/transactional-outbox` → repo nie istnieje.

**Ścieżka uploadu obrazu: microservice-memes (memes-image, memes-application, memes-infrastructure/MemeController + ConcurrencyGuardedImageOptimizer) oraz microservice-image (image-encoder). Rozstrzygnięcie punktu 5.8 z PLAN-P12 (semafor vs file.getBytes()) plus reszta ścieżki.**

URUCHOMIONE (P12 nie uruchomił żadnej suity):
1) `./mvnw -o -pl microservice-memes/{memes-image,memes-config,memes-application} -am test` → BUILD SUCCESS, 9 + 7 + 2 + 44 testów, 0 błędów, 0 pominiętych.
2) `./mvnw -o -pl microservice-memes/memes-infrastructure -am test` → BUILD SUCCESS, **142 testy, 0 failures, 0 errors, 0 skipped** (w tym DecodeConcurrencyTest, UploadRateLimitTest, WebErrorHandlerTest, ThumbnailCacheTest, WebpNegotiationTest, 22 scenariusze Cucumbera). Suita memes jest DZIŚ ZIELONA i nic nie jest pomijane.
   UWAGA metodologiczna: pierwsze uruchomienie BEZ `-am` dało 89 błędów (`NoClassDefFoundError: com.jrobertgardzinski.memes.domain.MemeMetadata` — stary artefakt memes-domain w ~/.m2). To artefakt mojego wywołania, nie usterka repo; z `-am` wszystko przechodzi.
3) `python3 -m unittest test_server` w microservice-image → **Ran 30 tests, OK**. (Lokalne Pillow 10.2.0, a `requirements.txt` przypina 12.3.0 — suita przeszła na starszym.)
4) POMIARY LOKALNE (osobne JVM/procesy, ZERO ruchu na żywym stacku, żadnego pliku w repo nie zmieniono):
   - `ImageIO.read` 16-bitowego RGBA PNG → 8,0 bajta/piksel (DataBufferUShort, 4 banki), czyli 8000x8000 = 488 MiB.
   - wygenerowany 8000x8000 16-bit RGBA PNG waży **486 kB**; jego dekode zajmuje **462 MiB** sterty (zmierzone delta used heap przy -Xmx700m).
   - ten sam plik na `java -Xmx358m` (= 1075 MiB / 3 permity, budżet z komentarza k8s) → `OutOfMemoryError: Java heap space`.
   - PRAWDZIWY `WebImageOptimizer` z `memes-image/target/classes` na tym pliku przy -Xmx358m → `InvalidImageException: unreadable image: the bytes are truncated or corrupt`, cause: `IIOException`, cause: `OutOfMemoryError`.
   - Pillow: świeży proces, plik 92 kB o wymiarach 5000x5000 (= dokładnie MAX_IMAGE_PIXELS=25 mln) → szczyt RSS **326 MB**; ten sam pomiar dla 1024x1024 → 34,7 MB (baseline 17,4 MB).
5) Odpytanie żywego stacku tylko do odczytu: `curl http://localhost:8083/actuator/prometheus` → `jvm_memory_max_bytes{area="heap"} = 8.39e9` (compose nie ma limitu pamięci, więc domyślne 25% z 32 GB hosta). Potwierdza, że OOM-u NIE dałoby się odtworzyć na compose i że problem jest wyłącznie k8s-owy — dlatego rozstrzygnąłem to z kodu i pomiaru offline, zgodnie z poleceniem.
6) Weryfikacja domyślnych wartości frameworka w źródłach z ~/.m2: `MultipartProperties.fileSizeThreshold = DataSize.ofBytes(0)`, `resolveLazily = false`, `ServerProperties.Tomcat.Threads.max = 200`, `FileCopyUtils.copyToByteArray(InputStream) { return in.readAllBytes(); }`, `StandardMultipartFile.getBytes()` = `FileCopyUtils.copyToByteArray(part.getInputStream())`.

---
## Znaleziska


### KRYTYCZNY

#### P13-01. Dwa moduły jądra (transactional-outbox, infrastructure-spring-outbox) istnieją wyłącznie na tym dysku — pierwszy push zacommitowanego stanu wywala wszystkie 5 bramek Mavena w CI, a jedyna kopia biblioteki sag nie ma backupu

**Plik:** `/home/robert/Documents/git/shared/pom.xml:38-39`

**Objaw:** Zacommitowany (ef1cce9, 2026-07-26) agregator shared wymaga bezwarunkowo modułów transactional-outbox i infrastructure-spring-outbox, ale żaden workflow ich nie checkoutuje — i nie może, bo te repozytoria NIE ISTNIEJĄ na GitHubie (oba lokalne repa nie mają nawet skonfigurowanego remote). Lokalne mainy są przed originem: shared ahead 2, portal ahead 9, microservice-memes ahead 11, microservice-comments ahead 7. Po pushu: shared ci.yml (joby reactor i e2e), portal ci.yml (joby reactor i e2e) oraz e2e-saga.yml padają na 'Child module .../transactional-outbox does not exist' przy każdym `./mvnw` w shared/ (portal ci.yml:118 i infra-up.sh:35 budują jądro), a sub-repo CI memes i comments padają na nierozwiązywalnym SNAPSHOT (memes ci.yml instaluje tylko voting+offline-jwt, a memes-infrastructure/pom.xml:132-136 i comments/pom.xml:169-174 zależą od obu modułów). Do czasu pushu CI 'zielone' waliduje kod sprzed 2 tygodni. Osobno: kod outboxu — fundament trwałości sag memes i comments — ma zero kopii poza jednym dyskiem.

**Dowód:** shared/pom.xml:38-39: `<module>transactional-outbox</module><module>infrastructure-spring-outbox</module>` (bez profili, git show HEAD potwierdza commit); `git remote -v` w obu modułach: puste; `gh repo view jrobertgardzinski/transactional-outbox` → "Could not resolve to a Repository"; lista checkoutów shared/.github/workflows/ci.yml:29-83 kończy się na 11 repach bez obu outboxów; portal/microservice-memes/.github/workflows/ci.yml:41-43 instaluje tylko `voting` i `offline-jwt`; gh run list: ostatni przebieg shared CI 2026-07-20, portal CI 2026-07-15

**Naprawa:** Utworzyć repa jrobertgardzinski/transactional-outbox i jrobertgardzinski/infrastructure-spring-outbox, dodać remote'y i wypchnąć; dopisać po dwa kroki checkout w shared ci.yml (oba joby), portal ci.yml (oba joby) i e2e-saga.yml; w CI memes i comments dodać checkout + `mvn -f .../pom.xml install -DskipTests` obu modułów przed `clean verify`; potem wypchnąć zaległe mainy i obejrzeć jeden pełny zielony przebieg

#### P13-02. Sufit 8000 px to 488 MiB, nie 256 MB: trzy permity semafora dopuszczają 1,4 GiB dekodowania przy 1075 MiB sterty — plik ważący 486 kB

**Plik:** `microservice-memes/memes-image/src/main/java/com/jrobertgardzinski/memes/image/WebImageOptimizer.java:26-30 (oraz ConcurrencyGuardedImageOptimizer.java:13-16, k8s/base/memes.yaml:62-75,93-103)`

**Objaw:** Cała arytmetyka pojemności memes stoi na liczbie „~256 MB na dekode", wyprowadzonej z 4 bajtów na piksel. Dla 16-bitowego PNG-a (RGBA, 16 bitów na kanał — format, którego ImageIO czyta bez mrugnięcia) BufferedImage ma DataBufferUShort z 4 pasmami, czyli 8 bajtów na piksel. Zmierzone: 8000x8000 = 488 MiB, realna delta sterty 462 MiB. Semafor przepuszcza 3 takie dekody naraz = ~1464 MiB, a k8s daje 70% z 1536Mi = 1075 MiB sterty. Nawet POJEDYNCZY dekode nie mieści się w budżecie 358 MiB, który komentarz w manifeście przypisuje jednemu permitowi — sprawdzone: `java -Xmx358m` na tym pliku kończy się OutOfMemoryError. Wejście: JEDEN zweryfikowany użytkownik, trzy równoległe uploady pliku o wielkości 486 kB (limit 12/min go nie dotyka).

**Dowód:** WebImageOptimizer.java:26-29: „ImageIO.read allocates ~4 bytes per pixel BEFORE we can downscale … 8000px per side (~256 MB worst case, once) is far beyond any legitimate meme yet small enough that one hostile upload cannot take the JVM down". ConcurrencyGuardedImageOptimizer.java:14-15: „still admits ~256 MB of heap PER DECODE at 16-bit depth". k8s/base/memes.yaml:93-97: „sized for MEMES_DECODE_CONCURRENCY=3: three worst-case decodes are ~768Mi of heap on their own". Pomiar (java Probe.java): „type=0 dataType=1 elemBytes=2 size=16384 banks=1 / bytes per pixel = 8.0 / => 8000x8000 would be 488 MiB". Pomiar na wygenerowanym pliku: „file size = 497904 bytes (486 kB)" i „decoded 8000x8000 heap used delta = 462 MiB" przy -Xmx700m; przy -Xmx358m: „Caused by: java.lang.OutOfMemoryError: Java heap space".

**Naprawa:** Liczyć sufit z realnej głębi bitowej, nie z 4 B/px. Minimum: `MAX_DECODE_DIMENSION` z 8000 na 4000 (16-bit RGBA = 122 MiB, 3 permity = 366 MiB, mieści się w 1075 MiB i nadal jest 4x ponad `memes.image.max-dimension=1024`, więc żaden legalny mem nie ucierpi). Docelowo: w `rejectDeclaredDimensionsAbove` odczytać z nagłówka także `reader.getRawImageType(0)`/liczbę bitów i odrzucać po ILOCZYNIE `width*height*bytesPerPixel` przeciw jawnemu `memes.image.max-decode-bytes`, zamiast po samym boku. Zaktualizować oba komentarze i komentarz w `k8s/base/memes.yaml:62-75` — dziś podają liczbę, która jest 2x za mała, a S5 z P12 cytuje ją jako wzorzec dla security.

**Korekta weryfikatora:** Merytorycznie wszystko stoi; dwie drobne poprawki faktograficzne w opisie:
(a) „budżet 358 MiB, który komentarz w manifeście przypisuje jednemu permitowi” — manifest NIE podaje 358 MiB, podaje „~256Mi na dekode” i „3 x ~256Mi”. 358 MiB to wyliczenie weryfikujące (1075/3). Obie liczby są mniejsze od zmierzonych 488 MiB, więc wniosek się nie zmienia.
(b) Warto dopisać, że guard porównuje `>` (WebImageOptimizer.java:81), więc obraz dokładnie 8000x8000 — najgorszy możliwy przypadek — przechodzi bez zastrzeżeń.
(c) Dodatkowy argument do naprawy: `mvn -pl memes-image test` jest dziś zielony, a jedyny test bomby (`WebImageOptimizerTest:121`) buduje nagłówek z `bit depth 8`, więc obniżenie `MAX_DECODE_DIMENSION` nie zepsuje żadnej asercji i nic nie pilnuje regresji przy 16 bitach.


### WYSOKI

#### P13-03. Awaria SMTP >60 s z zaległością zamyka konsumenta Kafki NA STAŁE — maile przestają wychodzić, a sondy dalej zielone

**Plik:** `shared/microservice-email/src/main/resources/application.properties:32`

**Objaw:** Retry na SMTP trwa ~7 s na rekord (MailRequestsConsumer: SMTP_RETRIES=3, backoff 1→8 s), a domyślna strategia commitów `throttled` ma limit `throttled.unprocessed-record-max-age.ms=60000` i zgłasza go jako błąd FATALNY. Przy padniętym Mailpicie/SMTP i ≥~9 zaległych zdarzeniach najstarszy pobrany rekord przekracza 60 s wieku → TooManyMessagesWithoutAckException → KafkaSource zamyka klienta konsumenta. Od tej chwili ŻADEN mail (weryfikacja, reset hasła, kod MFA — czyli logowanie) nie wychodzi aż do ręcznego restartu, a nic tego nie sygnalizuje: pom nie ma quarkus-smallrye-health (/q/health → 404, sprawdzone na żywo), sondy w k8s/base/email.yaml:45-57 i healthcheck compose (docker-compose.identity.yml:122) to gołe TCP na 8080, które pozostaje otwarte.

**Dowód:** MailRequestsConsumer.java:42-43 `SMTP_RETRIES = 3; SMTP_BACKOFF = Duration.ofSeconds(1)`; źródła smallrye-reactive-messaging-kafka 4.28.0 (z ~/.m2): KafkaConnector.java:81 `"throttled.unprocessed-record-max-age.ms" ... defaultValue = "60000"`, KafkaThrottledLatestProcessedCommit.java:302-308 `this.reportFailure.accept(exception, true)`, KafkaSource.java:326-329 `if (fatal) { if (client != null) { client.close(); } }`; application.properties:32-34 nie ustawia ani commit-strategy, ani max-age; `curl localhost:8082/q/health` → 404.

**Naprawa:** Do application.properties dodać `mp.messaging.incoming.mail-requests.throttled.unprocessed-record-max-age.ms=0` (wyłącza fatalny licznik; wolumen jest mały) albo wartość >> (rozmiar bufora poll × budżet retry). Do pom.xml dodać `io.quarkus:quarkus-smallrye-health` i przełączyć sondy k8s (email.yaml) na httpGet /q/health/live i /q/health/ready oraz healthcheck compose na curl /q/health — wtedy śmierć kanału Kafki w ogóle staje się widoczna.

#### P13-04. Ledger DLQ znika po restarcie serwisu — zaparkowane maile stają się nieosiągalne dla operatora, wbrew obietnicy w javadocu

**Plik:** `shared/microservice-email/src/main/resources/application.properties:39-41`

**Objaw:** Kanał `mail-requests-dlq-in` (grupa mail-dlq-ledger) nie ma ustawionego `auto.offset.reset`, więc obowiązuje domyślne `latest`, a offsety są commitowane. Scenariusz: SMTP leży → maile parkują na topicu DLQ → w trakcie tej samej awarii ktoś restartuje serwis (najbardziej prawdopodobny moment restartu!) → in-memory ledger startuje pusty, a konsument wznawia od zacommitowanego offsetu i NIE odczytuje ponownie wcześniej skonsumowanych rekordów. GET /mails/dlq zwraca [], POST /mails/dlq/{id}/redrive → 404. Javadoc ParkedMails ("the topic itself remains the durable record; this ledger is the window onto it") i MailRequestsConsumer ("nothing is silently lost ... an operator finds the whole story in one place") kłamią: rekord jest na topicu, ale żadne narzędzie w systemie go już nie zobaczy. Dodatkowo listy zaparkowane PRZED pierwszym startem konsumenta grupy są pomijane przez `latest`.

**Dowód:** application.properties: linia 34 ustawia `mp.messaging.incoming.mail-requests.auto.offset.reset=earliest` tylko dla mail-requests; linie 39-41 dla mail-requests-dlq-in ustawiają wyłącznie connector/topic/group.id. Źródła smallrye 4.28.0, KafkaConnector.java:78: `"auto.offset.reset" ... defaultValue = "latest"`. ParkedMails.java:20-21 cytowany javadoc; ledger to `Collections.synchronizedMap(new LinkedHashMap<>())` (ParkedMails.java:32) — czysty RAM.

**Naprawa:** Dla mail-requests-dlq-in ustawić `auto.offset.reset=earliest`, `group.id` unikalny per start (np. `mail-dlq-ledger-${quarkus.uuid}`) i `commit-strategy=ignore` — ledger odbudowuje się z całego topicu przy każdym starcie, zgodnie z obietnicą "window onto the durable record". Żeby replay nie wskrzeszał załatwionych rekordów, po udanym redrive publikować na DLQ marker `{"redriven": id}` (albo kompaktować topik kluczem id + tombstone), a w onParked usuwać wpis o tym id.

#### P13-05. Kody MFA, linki resetu hasła i adresy e-mail lecą do logów (i do Loki)

**Plik:** `shared/microservice-email/src/main/java/com/jrobertgardzinski/mail/boundary/MailRequestsConsumer.java:141`

**Objaw:** Ścieżki błędów logują PEŁNY payload zdarzenia: przy niesparsowalnym JSON (linia 93, WARN), przy padnięciu topicu DLQ (linia 141, ERROR — payload w tym momencie jest poprawny i zawiera `link` z tokenem resetu hasła albo `code` — jednorazowy kod logowania MFA) oraz w ParkedMails.java:46. Do tego każde zdarzenie loguje adres e-mail odbiorcy na INFO (linia 103) i na ERROR przy parkowaniu (126-127). Logi zbiera Loki (wspólny format z cid, application.properties:47-51), więc każdy z dostępem do logów może przejąć konto w trakcie resetu hasła albo przepisać kod MFA. Zweryfikowane na żywo: `docker logs security-email-1` pokazuje np. "mail request received (AUTH_CODE to gallery-1785229317873-7@example.com)", a payload tego typu (kafka-dump-log) zawiera jawne "code":"486858".

**Dowód:** MailRequestsConsumer.java:93 `LOG.warnf("dropping malformed mail request: %s", payload)`; :103 `LOG.infof("mail request received (%s to %s)", type, to)`; :141 `LOG.errorf(dlqDown, "the dead-letter topic is down too; the event is lost: %s", payload)`; ParkedMails.java:46 `LOG.warnf("unreadable dead letter: %s", payload)`.

**Naprawa:** Logować wyłącznie `id` i `type` zdarzenia: w 93 i 141 zastąpić `payload` przez `id`/długość payloadu (rekord i tak jest na topicu — to on jest dowodem, nie log); w 103 i 126 usunąć `to` albo maskować (np. pierwsze 2 znaki + domena). W ParkedMails.java:46 logować tylko rozmiar/przyczynę parse-błędu.

**Korekta weryfikatora:** Opis jest prawdziwy, z jednym doprecyzowaniem wagi: ścieżki logujące SEKRETY (token resetu, kod MFA) to linie 93 (payload niesparsowalny — sekret tylko jeśli garbage go zawiera), 141 i 145 (wymagają podwójnej awarii: SMTP + emit na DLQ). Codzienny, bezwarunkowy wyciek to adres e-mail odbiorcy na INFO przy każdym zdarzeniu (:103) i na ERROR przy parkowaniu (:126). Naprawa bez zmian.

#### P13-06. idp: rozszczepienie odpowiedzi HTTP (CRLF injection) przez redirect_uri w GET /authorize — potwierdzone na żywym stubie

**Plik:** `shared/microservice-idp/server.py:177-183`

**Objaw:** Parametr `redirect_uri` nie jest ani sprawdzany wobec listy zarejestrowanych URI (open redirect), ani sanityzowany z CR/LF, a `BaseHTTPRequestHandler.send_header` w Pythonie nic nie waliduje. Wejście: link do http://localhost:8091/authorize?...&redirect_uri=http%3A%2F%2Fevil.example%2Fcb%0d%0aX-Injected%3A%20yes%0d%0aSet-Cookie%3A%20pwned%3D1. Atakujący wstrzykuje dowolne nagłówki i dowolne ciało odpowiedzi. Ciasteczka nie są izolowane portem: `Set-Cookie` wstrzyknięte z :8091 obowiązuje dla całego hosta `localhost`, czyli także dla security na :8080 i UI na :8083/:8093 — a przeglądarki traktują http://localhost jako secure context, więc można ustawić także ciasteczko z flagą `Secure` o nazwie `refresh_token` (fiksacja sesji). Ten sam kod jedzie do klastra (k8s/base/idp.yaml).

**Dowód:** Kod: `location = query["redirect_uri"] + ("&" if "?" in query["redirect_uri"] else "?") + urlencode({...})` (server.py:178-179), potem `self.send_header("Location", location)` (181). Żadnej walidacji redirect_uri poza `any(not query.get(name) ...)` (161-162). Wynik curl-a na żywym kontenerze security-idp-1:
HTTP/1.0 302 Found
Location: http://evil.example/cb
X-Injected: yes
Set-Cookie: pwned=1?code=x2IwOZZf2F8hG2I4NcbTQw5Y-vqe3ghV&state=s1

**Naprawa:** W `_authorize`, zanim cokolwiek się wydarzy: (1) porównać `redirect_uri` z listą dozwolonych URI dla `client_id` (nowa zmienna `IDP_REDIRECT_URIS`, domyślnie wartość używana przez security — `http://localhost:8080/oauth/callback`), odrzucać 400 `invalid_request` przy braku dopasowania; (2) niezależnie od tego odrzucać każdą wartość zawierającą `\r` lub `\n` — `if any(c in value for c in "\r\n") for value in query.values()`. Punkt (1) jest właściwą naprawą, (2) to pas bezpieczeństwa dla pozostałych nagłówków.

**Uwaga:** brak werdyktu weryfikatora

#### P13-07. sms: jednorazowy kod MFA i pełny numer telefonu lądują czystym tekstem w logu i w Loki

**Plik:** `shared/microservice-sms/server.py:45`

**Objaw:** Stub loguje pierwsze 60 znaków treści wiadomości. Treść budowana przez security dla czynnika SMS to `"Sign-in code: Your sign-in code is <6 cyfr>"` — 41 znaków, czyli mieści się w całości. Numer odbiorcy nie jest w ogóle skracany (push przynajmniej ucina token do 12 znaków). Promtail zbiera stdout każdego kontenera projektu `security` i wypycha do Loki, więc każdy, kto ma wgląd w Grafanę (w dev anonimowy admin), czyta cudze kody logowania i numery telefonów. Kod MFA żyje `codeTtlMinutes` — czytelnik loga wyprzedza SMS-a.

**Dowód:** server.py:45 → `log("INFO", f"stub delivery to {to}: {text[:60]!r}")`. Treść z HttpSmsCodeChannel.java:40-41 → `"{\"to\":\"" + target + "\",\"subject\":\"Sign-in code\",\"body\":\"Your sign-in code is " + code + "\"}"`. Odtworzone lokalnie:
`2026-07-28T11:00:46.440+02:00 INFO  [cid=-] [trace=-] sms - stub delivery to +48555123456: 'Sign-in code: Your sign-in code is 481923'`
observability/promtail-config.yml: `docker_sd_configs` + `regex: security / action: keep` — „Ships every compose container's stdout/stderr to Loki”.

**Naprawa:** W `send()` logować wyłącznie metadane: zamaskowany odbiorca (ostatnie 3 cyfry, np. `***456`), długość tekstu i id wiadomości, nigdy samej treści. Jeśli treść ma zostać dla wygody dev-a, ukryć ją za osobnym przełącznikiem `SMS_LOG_BODY=false` domyślnie wyłączonym. Analogicznie w push (`{title!r} / {text[:40]!r}`, server.py:48) — tytuł i treść powiadomienia to też dane użytkownika.

**Uwaga:** brak werdyktu weryfikatora

#### P13-08. Trucizna zatyka relay: brak licznika prób i backoffu, a poll 'najstarsze najpierw z LIMIT' daje głodzenie nowszych zdarzeń

**Plik:** `/home/robert/Documents/git/shared/transactional-outbox/src/main/java/com/jrobertgardzinski/outbox/TransactionalOutbox.java:79-80`

**Objaw:** Zdarzenie, którego nigdy nie da się wysłać (payload > max.request.size brokera — scenariusz, który biblioteka sama przewiduje kanarkiem payloadu; skasowany topic; błąd ACL), jest ponawiane co 15 s w nieskończoność: DDL nie ma kolumny attempts, nie ma backoffu, dead-letter ani metryki (biblioteka zależy tylko od slf4j). Gorzej: pendingOlderThan sortuje ORDER BY created_at, id LIMIT 500, więc trwale niedostarczalne wiersze na zawsze okupują CZOŁO batcha — gdy uzbiera się ich >= resendBatchRows (500), żadne nowsze niepotwierdzone zdarzenie nie zostanie już nigdy wybrane i gwarancja 'WILL eventually reach the broker' umiera po cichu przy zielonej suicie. Poniżej 500: każdy wiersz kończący się timeoutem kosztuje 5 s patience sekwencyjnie na pass (180 wierszy = 15-minutowy pass).

**Dowód:** selectPendingSql = "SELECT ... WHERE published = FALSE AND created_at <= ? ORDER BY created_at, id LIMIT ?" (TransactionalOutbox.java:79-80); pętla sekwencyjna z publishAndWait(event, settings.confirmationPatience()) w OutboxRepublisher.java:75-79; ddl() w OutboxTable.java nie zawiera żadnej kolumny prób ani statusu poza published boolean; jedyny ślad dla operatora to LOG.warn("re-sending {} unconfirmed event(s)") — nieodróżnialny od jednorazowej czkawki brokera.

**Naprawa:** Dodać do DDL kolumny attempts int not null default 0 i last_attempt_at timestamp; w publishAndWait po porażce inkrementować attempts; w selectPendingSql filtrować backoffem (last_attempt_at <= now - f(attempts), np. wykładniczo z capem 1 h), dzięki czemu trucizna spada z czoła kolejki; po N (np. 25) próbach logować ERROR z pełnym payloadem i wykluczać wiersz z polla (published pozostaje FALSE — status 'poisoned'), plus wystawić licznik outbox_poisoned do przyszłej reguły alertu.

**Korekta weryfikatora:** Istota stoi, dwie precyzje. (1) Wieczne ponawianie niepublikowanych wierszy jest udokumentowaną, celową postawą biblioteki ('age is not expiry, and an old unpublished row still carries an obligation') — usterką nie jest retry bez końca, tylko to, że trwale niedostarczalne wiersze okupują czoło ORDER BY created_at i przy >= resendBatchRows (500) trucizn nowsze zdarzenia przestają być w ogóle wybierane, co po cichu unieważnia gwarancję 'WILL eventually'. (2) Poniżej 500 trucizn nie ma głodzenia, jest inflacja czasu passa: koszt 5 s patience na wiersz dotyczy tylko porażek timeoutowych — odmowa synchroniczna albo szybkie failed() z brokera (np. RecordTooLargeException) kosztuje mniej. Rachunek '180 wierszy = 15 min' to górna granica dla samych timeoutów, nie każdy scenariusz trucizny.

#### P13-09. markPublished wykonuje JDBC na wątku I/O producenta Kafki — awaria/zator bazy zamraża cały ruch Kafki serwisu; javadoc twierdzi, że to bezpieczne

**Plik:** `/home/robert/Documents/git/shared/transactional-outbox/src/main/java/com/jrobertgardzinski/outbox/TransactionalOutbox.java:151-160`

**Objaw:** Po potwierdzeniu przez broker OutboxPublisher.markDelivered → markPublished woła connections.get() (w SpringOutbox to dataSource::getConnection, Hikari) i UPDATE — a callback w KafkaMemeDispatch (whenComplete) biegnie na wątku sender producenta Kafki. Gdy pula połączeń jest wyczerpana albo baza muli, getConnection blokuje domyślnie do 30 s TEN JEDEN wątek, który obsługuje całe I/O producenta: wszystkie wysyłki serwisu stają, niepowiązane rekordy łapią delivery.timeout (30 s), co produkuje kolejne niepublikowane wiersze outboxu — sprzężenie zwrotne dokładnie w chwili, gdy baza już cierpi. Problem DB awansuje do problemu dostępności Kafki.

**Dowód:** TransactionalOutbox.java:145: "One short UPDATE on its own autocommit connection, so it is safe from the producer's I/O thread" oraz DispatchOutcome.java:5-6: "whichever thread the producer's callback runs on is fine — the library's reaction to either is a single short statement or a log line" — obie tezy fałszywe przy zatkanej puli; KafkaMemeDispatch.java:53: kafka.send(toRecord(event)).whenComplete(...) → outcome.confirmed() → markPublished, bez przełączenia wątku; SpringOutbox.java:60: new TransactionalOutbox(table, dataSource::getConnection, clock).

**Naprawa:** W publishWithoutWaiting nie wykonywać marka na wątku callbacku: markDelivered ma wrzucać eventId do nieblokującej kolejki (ConcurrentLinkedQueue) opróżnianej przez pass republishera (runOnce najpierw markuje zaległe potwierdzenia, potem reap/re-send) albo przez dedykowany jednowątkowy executor; w publishAndWait (wątek schedulera) można markować synchronicznie jak dziś. Poprawić oba cytowane javadoci.

#### P13-10. Rotacja refresh tokenu to check-then-act bez warunku — wyścig z P12 pkt 5.9 doprowadzony do źródła

**Plik:** `shared/microservice-security/security-system/src/main/java/com/jrobertgardzinski/security/system/session/RefreshSession.java:30-45 oraz security-infrastructure/src/main/java/com/jrobertgardzinski/persistence/SessionJdbcRepository.java:21-22`

**Objaw:** Dwa naprawdę równoczesne POST /refresh z tym samym cookie: obie transakcje (READ COMMITTED, zwykłe @Transactional w TransactionalBoundary) czytają wiersz ze statusem ACTIVE, obie wykonują bezwarunkowy UPDATE (drugi nadpisuje ROTATED→ROTATED bez błędu) i obie robią INSERT nowej sesji w tej samej rodzinie — dwa 200, sesja rozwidla się na dwa żywe łańcuchy, więc złodziej tokenu, który odświeży równocześnie z ofiarą, dostaje własny niewykrywalny łańcuch (detekcja kradzieży ominięta). Drugi kierunek tej samej wady: przy ~50 ms przesunięcia (dwie karty przeglądarki) druga transakcja widzi już ROTATED → ReuseDetected → revokeFamily KASUJE świeżo zrotowany łańcuch ofiary — niewinny podwójny refresh wylogowuje użytkownika z całej rodziny sesji. To dokładnie oba zachowania zmierzone w P12 pkt 5.9.

**Dowód:** RefreshSession.java:32-41: `if (session.status() == SessionStatus.ROTATED) { authorizationDataRepository.revokeFamily(session.family()); return new RefreshSessionResult.ReuseDetected(); } ... authorizationDataRepository.markRotated(refreshToken);` — odczyt statusu i rotacja to dwa osobne kroki. SessionJdbcRepository.java:21-22: `@Query("UPDATE sessions SET status = :status WHERE refresh_token_hash = :refreshTokenHash") void updateStatus(...)` — brak warunku `AND status = 'ACTIVE'`, zwraca void, więc nikt nie wie, czy rotacja cokolwiek zrotowała. V1__init.sql:23-32: żaden indeks nie wymusza jednego ACTIVE na family_id. Testy: RefreshSessionTest (mocki, sekwencyjnie), refresh-session.feature i reuse-detection.feature — zero scenariuszy współbieżnych.

**Naprawa:** Uczynić rotację atomową i warunkową: w SessionJdbcRepository zamienić updateStatus na `@Query("UPDATE sessions SET status = 'ROTATED' WHERE refresh_token_hash = :refreshTokenHash AND status = 'ACTIVE'") int rotateIfActive(String refreshTokenHash)`; w AuthorizationDataRepository dać `boolean markRotated(...)`; w RefreshSession.execute: jeśli rotateIfActive zwróci 0 wierszy — potraktować jak gałąź ROTATED (revokeFamily + ReuseDetected). Pod READ COMMITTED drugi UPDATE czeka na lock wiersza i po commicie pierwszego ponownie ewaluowany warunek daje 0 wierszy (EvalPlanQual) — dokładnie jeden zwycięzca, zero forka. Opcjonalny pas i szelki: migracja `CREATE UNIQUE INDEX uq_sessions_one_active_per_family ON sessions (family_id) WHERE status = 'ACTIVE'` jako niezmiennik bazy. Osobna decyzja produktowa: gałąź 0-wierszy przy delay ~50 ms (dwie karty) można złagodzić zwracając 401 bez revokeFamily tylko gdy prezentowany token zrotowano w ostatnich N sekundach (grace window), ale minimum bezpieczeństwa to warunkowy UPDATE.

#### P13-11. Weryfikacja paktów po stronie providera martwa od podziału workspace'ów — pomija się po cichu na zawsze

**Plik:** `shared/microservice-security/security-infrastructure/src/test/java/com/jrobertgardzinski/MeIntrospectionPactProviderTest.java:34-44 oraz OffboardingFactsPactProviderTest.java:24-30`

**Objaw:** Jedyna weryfikacja kontraktu GET /me (bramka autoryzacji memes) i kontraktu faktów offboardingu po stronie security NIGDY się nie wykonuje: guard @EnabledIf szuka konsumenta pod ../../microservice-memes/pacts-http, czyli w shared/ — a microservice-memes i microservice-offboarding mieszkają od podziału 2026-07-12 w sąsiednim workspace portal/. Suita raportuje zielono (Skipped, nie Failed), więc łamiąca zmiana kształtu odpowiedzi /me przeszłaby przez pełny `mvn test` security bez jednego czerwonego testu — test kłamie, że kontrakt jest strzeżony.

**Dowód:** MeIntrospectionPactProviderTest.java:34-36: `@PactFolder("../../microservice-memes/pacts-http") @EnabledIf(value = "consumerPactsCheckedOut", disabledReason = "microservice-memes is not checked out next to this repo")`; wynik uruchomienia: surefire `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1` dla OBU klas; `ls /home/robert/Documents/git/shared/microservice-memes` → No such file or directory, podczas gdy `/home/robert/Documents/git/portal/microservice-memes/pacts-http/microservice-memes-microservice-security.json` i `/home/robert/Documents/git/portal/microservice-offboarding/pacts` istnieją.

**Naprawa:** Poprawić obie ścieżki na `../../../portal/microservice-memes/pacts-http` i `../../../portal/microservice-offboarding/pacts` (w @PactFolder i w metodzie consumerPactsCheckedOut), a docelowo sparametryzować katalog paktów właściwością systemową ustawianą w CI, żeby kolejna zmiana layoutu znów nie uśpiła weryfikacji bez śladu.

#### P13-12. Suita przeglądarkowa memes-ui ma trwale czerwony scenariusz: „unavailable keepsake" zakłada zachowanie sprzed kaskady 44e8616, która teraz usuwa ref z ulubionych

**Plik:** `/home/robert/Documents/git/portal/microservice-memes/memes-ui/e2e/features/favourites.feature:25`

**Objaw:** Scenariusz „A favourite outliving its meme shows as an unavailable keepsake" oczekuje, że po usunięciu mema ref przetrwa w ulubionych i wyrenderuje się jako 'unavailable'. Od commitu 44e8616 w user-collections (2026-07-26, „Ulubione dowiaduja sie, ze mem zniknal") kaskada MEME_DELETED usuwa ref w sekundy, więc ściana ulubionych jest pusta i tekst 'unavailable' nigdy się nie pojawia — test przegrywa wyścig z Kafką deterministycznie. Ta sama suita gate'uje job e2e w portal ci.yml, więc po wypchnięciu byłby czerwony na każdym PR. Suita portal/e2e w tym samym przebiegu asertuje ZAPRZECZENIE („it eventually disappears from my favourites" — zielone): dwie suity e2e żądają dziś sprzecznych zachowań.

**Dowód:** Dwa uruchomienia: `18 scenarios (1 failed, 17 passed)` oraz pojedynczo `1 scenario (1 failed)`; błąd: `Expect "to.be.visible" ... waiting for getByText('unavailable')` w favourites.steps.mjs:72; nagłówek feature: „A ref outlives its meme by design — the favourites wall then shows an unavailable keepsake"; git log user-collections: `44e8616 Ulubione dowiaduja sie, ze mem zniknal: druga os dostepu, wlasna petla i read-repair...`

**Naprawa:** Przepisać Then na zgodne z kaskadą oczekiwanie (przez this.eventually: ref znika z ulubionych / ściana pokazuje 'No favourites yet' — dokładnie jak portal/e2e/deletion-cascade), poprawić kłamiący nagłówek feature; stan przejściowy 'unavailable' (okno między DELETE a dojazdem kaskady) testować jednostkowo w UI na zamockowanym 404, nie w e2e ścigającym się z brokerem

**Korekta weryfikatora:** Jedyny retusz: „przegrywa wyścig z Kafką deterministycznie" to lekka hiperbola — formalnie to wyścig (okno między DELETE a dojazdem kaskady vs 5 s timeoutu asercji), ale przegrany we wszystkich znanych uruchomieniach (2 znalazcy + 1 moje, 3/3 czerwone), więc w praktyce trwale czerwony. Istota i naprawa bez zmian.

#### P13-13. security-ui nie ma modelu błędu: każde 5xx z security czyta się jako „Wrong e-mail or password"/„Wrong password"/„Wrong code", a awaria sieci to cisza — dokładnie klasa błędu naprawiona w memes-ui i collections-ui

**Plik:** `/home/robert/Documents/git/shared/microservice-security/security-ui/src/App.tsx:163-165`

**Objaw:** signIn: gałąź else zbiera wszystko poza 202/2xx/403/429 — 500, 502 i 504 są meldowane jako „Wrong e-mail or password." (użytkownik idzie resetować poprawne hasło przy awarii backendu). startDelete (App.tsx:402): każdy nie-2xx/202 przy usuwaniu konta → „Wrong password."; submitDeleteCode (418) → „Wrong code."; confirmEnrol (305) → „Wrong code — enrolment not completed.". Wszystkie handlery to gołe `await fetch` wywoływane jako `void signIn()` itd. — zerwane połączenie kończy się unhandled rejection i ekranem bez żadnego komunikatu. submitFactor (184) robi `await r.json()` w gałęzi błędu bez `.catch` — nie-JSON body (typowy 502 z proxy) zabija handler po cichu. W całym src/ są dokładnie 2 try/catch (oba dla passkey), zero ErrorBoundary, zero odpowiednika request()/HttpError z memes-ui. P12 nie czytał security-ui (sekcja 5.4), a W3 opisuje ten sam defekt w memes-ui.

**Dowód:** App.tsx:163-165: `} else { setNotice('Wrong e-mail or password.'); }`; App.tsx:184: `const body ... = await r.json();` w gałęzi !r.ok; App.tsx:402: `} else { setNotice('Wrong password.'); }`; grep -rn "ErrorBoundary|HttpError|renewAccessToken|try {" src/ → tylko App.tsx:195 i :275 (passkey)

**Naprawa:** Przenieść wzorzec z collections-ui/memes-ui: w signIn rozdzielić 401 („Wrong e-mail or password.") od reszty („Security answered {status}."), owinąć fetch w try/catch z komunikatem „Security service unreachable.", dodać `.catch(() => ({}))` do każdego r.json() w gałęziach błędów, analogicznie w startDelete/submitDeleteCode/confirmEnrol; docelowo jeden helper request() zamiast 20 gołych fetchy

**Korekta weryfikatora:** Dwie poprawki opisu. (1) Waga: dziś SREDNI, nie WYSOKI — security-ui wg własnego README to trzeci runner specyfikacji uruchamiany przez vite w e2e, NIE jest wdrożone nigdzie (grep po docker-compose.yml, k8s/, docker-compose.identity.yml: zero wystąpień), więc „użytkownik idzie resetować hasło" dotyczy dziś dewelopera, nie klienta. Waga rośnie do WYSOKIEJ dokładnie w momencie, gdy ktoś wykona naprawę P12-S13 wariantem „wdrożone security-ui" (martwy RESET_LINK_BASE) — warto to odnotować przy S13. (2) „Zero modelu błędu" jest lekko zawyżone: completeReset (App.tsx:335) i changePassword (:354) już mają wzorcowe `await r.json().catch(() => ({}))`, a signOut (:426) ma `.catch` — defekt polega na tym, że ten wzorzec jest w 3 z ~20 miejsc, a nie w żadnym.

#### P13-14. Semafor zaczyna się DOPIERO w optimize(): 200 wątków Tomcata może trzymać po 10 MB surowych bajtów, zanim cokolwiek je ograniczy (rozstrzygnięcie P12 pkt 5.8)

**Plik:** `microservice-memes/memes-infrastructure/src/main/java/com/jrobertgardzinski/memes/infrastructure/MemeController.java:80`

**Objaw:** Kolejność na ścieżce POST /memes jest taka: (1) filtr auth, (2) DispatcherServlet parsuje multipart — przy `fileSizeThreshold=0` Tomcat pisze go NA DYSK, więc sterty to nie kosztuje (tu P12 mylił się co do mechanizmu, ale nie co do wniosku), (3) `uploadRate.tryAcquire` — licznik 12/min na użytkownika, NIE limiter współbieżności, (4) `file.getBytes()` — TU powstaje tablica do 10 MB na stercie, (5) `publishMeme.execute` → `optimizer.optimize` → `permits.tryAcquire(5s)` — DOPIERO TU zaczyna działać semafor. Surowe bajty są trzymane przez cały czas oczekiwania na permit (do 5 s) i przez cały dekode. Nic nie ogranicza liczby wątków w kroku (4): `server.tomcat.threads.max` nie jest ustawiony nigdzie w repo, więc obowiązuje domyślne 200. ARYTMETYKA: 200 x 10 MB = 2000 MB samych surowych bajtów wobec 1075 MiB sterty w k8s — 1,9x cała sterta, zanim doliczymy cokolwiek innego. Nawet skromnie: 3 permity x 488 MiB (patrz znalezisko poprzednie) + 46 czekających x 10 MB = 460 MiB zjada CAŁY „~460Mi headroom", który manifest deklaruje jako zapas. Budżet w `k8s/base/memes.yaml` nie ma ANI JEDNEGO członu na kolejkę surowych uploadów. Limit 12/min nie broni: `RateLimit.tryAcquire` to licznik okna, więc jedno konto może mieć 12 żądań RÓWNOCZEŚNIE w locie (12 wywołań, liczniki 1..12, wszystkie <= 12), cztery konta = 48.

**Dowód:** MemeController.java:76-80: `if (!uploadRate.tryAcquire(uploader)) { return ResponseEntity.status(429)…; }` a linię niżej `String id = publishMeme.execute(file.getBytes(), uploader);`. PublishMeme.java:34: `OptimizedImage optimized = optimizer.optimize(rawImage);`. ConcurrencyGuardedImageOptimizer.java:43-44 i 52-55: `public OptimizedImage optimize(byte[] input) { return guarded(() -> delegate.optimize(input)); }` → `acquired = permits.tryAcquire(patience.toMillis(), …)`. MemesConfig.java:47: `decodeConcurrency, java.time.Duration.ofSeconds(5)`. application.properties:5-6: `spring.servlet.multipart.max-file-size=10MB`. Domyślne z ~/.m2: `MultipartProperties:75 private DataSize fileSizeThreshold = DataSize.ofBytes(0)`, `:81 private boolean resolveLazily = false`, `ServerProperties:974 private int max = 200`. `StandardMultipartHttpServletRequest.java:258-259: return FileCopyUtils.copyToByteArray(this.part.getInputStream());` → `FileCopyUtils.java:148-150: try (in) { return in.readAllBytes(); }`. Grep po obu repo za `server.tomcat.threads` — zero trafień. Osobna oś, dyskowa: `ImageIO.getUseCache()` = true i `ImageIO.createImageInputStream(ByteArrayInputStream)` zwraca `javax.imageio.stream.FileCacheImageInputStream` (zmierzone), więc każdy dekode dokłada kopię pliku w `java.io.tmpdir` — obok pliku tymczasowego multipartu; `spring.servlet.multipart.location` nie jest ustawione, a `k8s/base/memes.yaml` nie ma ani emptyDir, ani limitu `ephemeral-storage`.

**Naprawa:** Trzy zmiany, każda samodzielnie zmniejsza szkodę. (1) Odrzucać po ZADEKLAROWANYM rozmiarze zanim cokolwiek powstanie: `OncePerRequestFilter` przed DispatcherServletem, który dla `POST /memes` czyta `getContentLengthLong()` i przy przekroczeniu `memes.upload.max-bytes` odpowiada 413 — to zamyka też S10 z P12 (dziś przekroczenie limitu multipartu daje 500 „internal error"). (2) Przenieść bajty POD semafor: zmienić sygnaturę `PublishMeme.execute` na `InputStream`/`MultipartFile` i czytać je wewnątrz `guarded()`, albo — wariant minimalny, bez ruszania modułu application — pobrać permit w kontrolerze PRZED `file.getBytes()`. Wtedy liczba żądań trzymających obrazy w pamięci = liczba permitów, czyli dokładnie to, co manifest już policzył. (3) `server.tomcat.threads.max` ustawić na wartość, którą sterta udźwignie (np. 32) — dziś sufitem współbieżności jest przypadkowa domyślna 200. Dodatkowo: `spring.servlet.multipart.location` na dedykowany katalog + `emptyDir` z `sizeLimit` i `resources.limits.ephemeral-storage` w k8s.

**Korekta weryfikatora:** Znalezisko jest prawdziwe, ale dwie liczby w opisie trzeba ustawić uczciwiej:
(a) „200 x 10 MB = 2000 MB” to sufit teoretyczny wymagający ~17 zweryfikowanych kont (12 równoczesnych na konto wynika z semantyki `RateLimit`, potwierdzonej w kodzie). Ścieżka jest UWIERZYTELNIONA: `RequireSignInFilter:49-55` odrzuca POST bez tokenu 401 **przed** parsowaniem multipartu przez DispatcherServlet, więc anonim nie zaalokuje ani bajtu. To nadużycie przez zalogowanych, nie anonimowy DoS.
(b) Realny człon to ten skromniejszy, i on wystarcza: 3 permity x 488 MiB (znalezisko 1) + kilkadziesiąt wątków po 10 MB zjada cały deklarowany „~460Mi headroom” z `k8s/base/memes.yaml:70-73`.
(c) Naprawa (2) w wariancie „pobrać permit w kontrolerze przed `file.getBytes()`” ma pułapkę do nazwania: `toPngWithin` (miniatury) też bierze permit, więc pobranie permitu w kontrolerze uploadu przy niezmienionym `optimize()` da podwójne zajęcie tego samego semafora przez jeden wątek — trzeba albo przenieść bajty pod `guarded()`, albo wprowadzić osobny semafor „wejściowy”.

#### P13-15. OutOfMemoryError jest przedstawiany uploadującemu jako „twój plik jest uszkodzony" (400) i nie zostawia ANI JEDNEJ linii w logu

**Plik:** `microservice-memes/memes-image/src/main/java/com/jrobertgardzinski/memes/image/WebImageOptimizer.java:57-62`

**Objaw:** `PNGImageReader.read` łapie `Throwable` i opakowuje go w `IIOException`, która dziedziczy po `IOException`. `WebImageOptimizer` ma `catch (IOException e)` i zamienia to na `InvalidImageException("unreadable image: the bytes are truncated or corrupt")`. `WebErrorHandler.refusedImage` odpowiada 400 i — jako jedyny handler w tej klasie — NIE loguje niczego. Skutek: wyczerpanie sterty JVM-a jest raportowane jako błąd wejścia użytkownika. Operator nie zobaczy nic: żadnego ERROR/WARN, żadnego 5xx (reguła `Http5xxBurst` z observability nie zadziała, bo to 4xx), żadnego licznika (grep po `meterRegistry` w memes-infrastructure: jedyny licznik to `memes.kafka.records.dropped` w SagaParticipantConfig — autor umie je dodawać, na ścieżce obrazów nie ma ani jednego), a `JDK_JAVA_OPTIONS` w k8s to wyłącznie `-XX:MaxRAMPercentage=70`, więc nie ma ani `HeapDumpOnOutOfMemoryError`, ani `ExitOnOutOfMemoryError`. Liveness to `livenessState`, który OOM-u nie widzi, więc pod nie zostanie zrestartowany — będzie się dławił GC i oddawał 400 „twój obrazek jest uszkodzony" ludziom, których obrazki są w porządku, podczas gdy równoległe żądania (głosy, galeria, listener sagi) dostają OOM w miejscach bez handlera.

**Dowód:** Odtworzone na PRAWDZIWEJ klasie z `memes-image/target/classes` przy -Xmx358m: „THROWN: com.jrobertgardzinski.memes.image.InvalidImageException : unreadable image: the bytes are truncated or corrupt / cause: javax.imageio.IIOException : Caught exception during read: / cause: java.lang.OutOfMemoryError : Java heap space". Kod: WebImageOptimizer.java:57-62 `catch (IOException e) { … throw new InvalidImageException("unreadable image: the bytes are truncated or corrupt", e); }` z komentarzem „everything read here is the CALLER's bytes (both streams are in-memory), so an IOException means their image died mid-parse" — założenie „to na pewno wina wołającego" jest tu fałszywe. WebErrorHandler.java:30-34: `ResponseEntity<Map<String,String>> refusedImage(InvalidImageException refused) { return ResponseEntity.badRequest().body(…); }` — bez `LOG`, w odróżnieniu od czterech pozostałych handlerów w tej samej klasie, które wszystkie logują.

**Naprawa:** W `toPngWithin` rozdzielić dwa przypadki zamiast łapać `IOException` hurtem: `catch (IIOException e) { if (hasCause(e, OutOfMemoryError.class) || hasCause(e, Error.class)) throw new ImageDecodeOverloadedException(...); throw new InvalidImageException(...); }` — OOM to nie jest zły upload, to 429/503 i linia ERROR. Niezależnie: dodać `LOG.info`/licznik w `WebErrorHandler.refusedImage` (`memes_image_rejected_total{reason}`), żeby fala odrzuceń w ogóle była widoczna, oraz dopisać `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError` do `JDK_JAVA_OPTIONS` w `k8s/base/memes.yaml:74-75` — dziś jedyny skutek OOM-u to cicha degradacja bez restartu.

**Korekta weryfikatora:** Jedna nieścisłość do poprawienia: „nie zostawia ANI JEDNEJ linii w logu” jest o pół kroku za mocne. `CorrelationIdFilter.java:41` loguje na INFO każde żądanie: `log.info("cid={} {} {}", cid, request.getMethod(), request.getRequestURI())`. Ta linia POWSTANIE — ale nie niesie ani kodu odpowiedzi, ani wyjątku, więc upload zakończony OOM-em jest w logu bajt w bajt nieodróżnialny od udanego. Poprawne sformułowanie: „nie zostawia ani jednej linii mówiącej, że cokolwiek poszło źle — zero ERROR/WARN, a jedyna linia dostępowa nie zawiera statusu”. Reszta znaleziska, łącznie z wagą WYSOKI, stoi.


### SREDNI

#### P13-16. Klucz API ma produkcyjny default "changeme" — zapomniany MAIL_API_KEY otwiera relay na znany klucz zamiast zatrzymać start

**Plik:** `shared/microservice-email/src/main/resources/application.properties:13`

**Objaw:** `mail.api-key=${MAIL_API_KEY:changeme}` obowiązuje we WSZYSTKICH profilach, w tym prod. Deploy bez ustawionego MAIL_API_KEY (np. literówka w sekrecie k8s `mail-api-key`) nie przewraca startu — serwis rusza i akceptuje każdego, kto przyśle nagłówek `X-Api-Key: changeme`, czyli publicznie znany z repo klucz do POST /mails (dowolna treść na dowolny adres z domeny no-reply@jrobertgardzinski.com) i do redrive DLQ. To jawnie sprzeczne z filozofią opisaną 4 linie niżej dla SMTP hosta: "REQUIRED at deploy (startup fails fast if unset, rather than silently...)".

**Dowód:** application.properties:13 `mail.api-key=${MAIL_API_KEY:changeme}` oraz komentarz :17-18 "The host is REQUIRED at deploy (startup fails fast if unset...)". Kompose ustawia własny default (docker-compose.identity.yml:113 `MAIL_API_KEY: ${MAIL_API_KEY:-local-dev-key}`), więc dev nie ucierpi.

**Naprawa:** Zmienić linię 13 na `mail.api-key=${MAIL_API_KEY}` (bez defaultu — prod fail-fast jak SMTP host), zostawić istniejące `%dev.mail.api-key=dev-key` i `%test.mail.api-key=test-key`.

**Korekta weryfikatora:** Waga NISKI, nie SREDNI. Oba istniejące tory wdrożeniowe blokują opisany scenariusz piętro wyżej: (1) k8s/base/email.yaml:31-35 wstrzykuje MAIL_API_KEY z secretKeyRef BEZ `optional: true` — brak sekretu mail-api-key albo literówka w nazwie klucza daje CreateContainerConfigError i pod w ogóle nie startuje (fail-fast dostarczony przez manifest, dokładnie ten, którego żąda znalazca), a overlay dev generuje sekret (k8s/overlays/dev/kustomization.yaml:36-38); (2) compose ustawia default local-dev-key (docker-compose.identity.yml:113). 'changeme' zadziałałby dopiero przy przyszłym, trzecim torze wdrożenia (goły jar z profilem prod poza tymi manifestami). Naprawa pozostaje słuszna i tania: usunąć default z linii 13 — spójność z filozofią SMTP hosta i defense-in-depth.

#### P13-17. sms i push: POST /send bez żadnego uwierzytelnienia i bez limitu, mimo że bliźniaczy serwis email wymaga X-Api-Key

**Plik:** `shared/microservice-sms/server.py:58-72`

**Objaw:** Endpoint wysyłkowy przyjmuje żądanie od kogokolwiek, kto dosięgnie portu — nie ma klucza API, nie ma limitu częstotliwości, nie ma limitu rozmiaru żądania. Dziś powierzchnia to sieć compose (każdy kontener: memes, comments, nginx z UI, cadvisor), bo porty nie są publikowane na host — ale README wprost instruuje: „Point SMS_PROVIDER at a real gateway (e.g. twilio) and give it credentials to send for real”. Po tym kroku ten sam nieuwierzytelniony endpoint zamienia się w pompę płatnych SMS-ów i wektor phishingu („Twój kod logowania to …” z numeru portalu). Asymetria jest w tym samym compose: email dostaje `MAIL_API_KEY` i wymaga nagłówka, sms i push nie dostają nic.

**Dowód:** server.py:58-72 — cała `do_POST`: sprawdza tylko ścieżkę i JSON, ani jednego odczytu `self.headers.get("Authorization")` / `X-Api-Key`. Identycznie push/server.py:61-75. Dla kontrastu docker-compose.identity.yml:113 `MAIL_API_KEY: ${MAIL_API_KEY:-local-dev-key}` i nagłówek pliku, linia 17: „8082 microservice-email (POST /mails*, X-Api-Key required)”. Dodatkowo `self.rfile.read(int(self.headers.get("Content-Length", 0)))` (server.py:63) czyta deklarowaną długość bez górnego progu.

**Naprawa:** Dodać w obu usługach ten sam wzorzec co w email: `API_KEY = os.environ.get("SMS_API_KEY")` i na starcie `do_POST` porównanie `hmac.compare_digest(self.headers.get("X-Api-Key",""), API_KEY)` → 401 przy niezgodności; gdy zmienna nie jest ustawiona, wypisać WARN i (dla prowajdera innego niż `stub`) odmówić startu. Do tego twardy limit `Content-Length` (np. 8 KiB) przed `read`.

**Uwaga:** brak werdyktu weryfikatora

#### P13-18. idp: POST /token z uszkodzonym Content-Length albo ciałem spoza UTF-8 zabija wątek obsługi — klient dostaje pustą odpowiedź zamiast 400

**Plik:** `shared/microservice-idp/server.py:150`

**Objaw:** Odczyt ciała jest poza jakimkolwiek try. `int("abc")` rzuca ValueError, `.decode()` na bajtach spoza UTF-8 rzuca UnicodeDecodeError — obie lecą do socketservera, który zamyka połączenie bez żadnej odpowiedzi HTTP i wypisuje traceback. Klient (microservice-security w kroku wymiany kodu) nie dostaje ani 400, ani 500 — dostaje „empty reply”, czyli błąd transportu zamiast błędu protokołu, więc po stronie security nie da się tego odróżnić od padniętego dostawcy. Uwaga: sms i push mają ten sam odczyt, ale u nich `int(...)` jest wewnątrz `try/except ValueError`, więc odpowiadają 400 — idp jest tu odstępstwem od własnego rodzeństwa.

**Dowód:** server.py:150 → `body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()`, a `try:` zaczyna się dopiero w linii 152. Na żywym kontenerze:
`curl -i -X POST http://localhost:8091/token -H "Content-Length: abc" -d code=x` → `curl: (52) Empty reply from server`
`docker logs security-idp-1` → `File "/app/server.py", line 150, in do_POST ... ValueError: invalid literal for int() with base 10: 'abc'` oraz `UnicodeDecodeError: 'utf-8' codec can't decode byte 0xff in position 0` dla ciała binarnego.

**Naprawa:** Wciągnąć odczyt do bloku try i zwracać 400: `try: length = int(self.headers.get("Content-Length", 0)) except ValueError: return self._json(400, {"error": "invalid_request"})`, plus `if length > 8192: return self._json(413, ...)` i `.decode("utf-8", "replace")` zamiast twardego `.decode()`.

**Uwaga:** brak werdyktu weryfikatora

#### P13-19. idp: _codes i _tokens rosną bez końca — nieuwierzytelnione GET /authorize alokuje wpis, którego nic nigdy nie usuwa

**Plik:** `shared/microservice-idp/server.py:46`

**Objaw:** Kod autoryzacyjny wygasa logicznie po 300 s, ale wpis znika ze słownika WYŁĄCZNIE wtedy, gdy ktoś go wymieni (`_codes.pop` w `exchange`). Kod nigdy niewymieniony (porzucone logowanie, skan, atak) zostaje na zawsze. `_tokens` nie ma nawet tego — access token nie jest usuwany nigdy, ani po wygaśnięciu, ani w ogóle. Każde `GET /authorize?...&email=x` — bez uwierzytelnienia, na porcie opublikowanym na hoście — to trwały przyrost pamięci procesu. W klastrze `limits.memory: 128Mi` (k8s/base/idp.yaml:55) + `livenessProbe` oznacza OOMKill i restart, a restart czyści pamięć, więc wszystkie wydane access tokeny przestają działać w połowie logowań (ścieżka USERINFO providera „github” chodzi po /userinfo).

**Dowód:** server.py:46 `_codes = {}` / `_tokens = {}` — w całym pliku nie ma ani jednego `del`, `clear()`, wątku sprzątającego ani sprawdzenia rozmiaru; jedyne usunięcie to `pending = _codes.pop(code, None)` (94), a `_tokens[access_token] = {...}` (103) tylko dokłada. Wygaśnięcie jest sprawdzane przy odczycie (95, 115) i nic z tego nie wynika dla pamięci. k8s/base/idp.yaml:54-55: `limits: memory: 128Mi`.

**Naprawa:** Przy każdym `issue_code`/wpisie tokenu przejść po słowniku i usunąć wygasłe (`for k, v in list(_codes.items()): if now > v["expires"]: del _codes[k]`) oraz nałożyć twardy sufit liczby wpisów (np. 10 000, po przekroczeniu odmowa `temporarily_unavailable`). Dwa słowniki, kilkanaście linii — alternatywnie `collections.OrderedDict` z eksmisją FIFO.

**Uwaga:** brak werdyktu weryfikatora

#### P13-20. Brak jakiegokolwiek timeoutu na wywołaniach security → idp i security → sms; wiszący dostawca wiesza żądanie użytkownika na zawsze

**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/OidcClient.java:57`

**Objaw:** To odpowiedź na pytanie „co się dzieje przy timeoucie dostawcy”: nic się nie dzieje, bo timeoutu nie ma. `HttpClient.newHttpClient()` nie ustawia `connectTimeout`, a żaden `HttpRequest.newBuilder(...)` nie ustawia `.timeout(...)` — ani w OidcClient (token endpoint, userinfo, emails), ani w HttpSmsCodeChannel. Dostawca, który przyjmie połączenie TCP i nigdy nie odpowie (zawieszony stub, blackhole sieciowy, wolny gateway telco), blokuje wątek obsługi `GET /oauth/callback` — endpointu anonimowego, wołanego z przeglądarki po powrocie z IdP — bez żadnego górnego ograniczenia. Nic tego nie przerwie: brak konfiguracji egzekutorów w application.yml, brak circuit breakera, brak retry z budżetem. Odpowiedź jest do tego czytana jako `BodyHandlers.ofByteArray()` bez limitu rozmiaru, więc wrogi/uszkodzony dostawca może dosypać pamięci.

**Dowód:** OidcClient.java:57 `private final HttpClient http = HttpClient.newHttpClient();`, wywołania w 213 (`postForm`) i dwa dalsze `http.send(HttpRequest.newBuilder(URI.create(url))...)` — `grep -n "timeout\|connectTimeout" OidcClient.java` nie zwraca nic. To samo w HttpSmsCodeChannel.java:26 i 43-47. `grep -rn "executors\|read-timeout\|idle-timeout" application*.yml` — brak wyników.

**Naprawa:** W obu klasach: `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()` oraz `.timeout(Duration.ofSeconds(5))` na każdym `HttpRequest`. Dla OidcClient dodatkowo `BodyHandlers.ofByteArray()` zamienić na wariant z limitem (np. `BodySubscribers.ofByteArray` opakowany w limit 256 KiB) i mapować `HttpTimeoutException` na `OauthDanceFailed("provider did not answer")`, żeby użytkownik dostał czytelne 502 zamiast wiszącej karty.

**Uwaga:** brak werdyktu weryfikatora

#### P13-21. Numer telefonu do SMS nie jest walidowany na żadnej warstwie, JSON jest sklejany ze stringów, a status odpowiedzi sms jest wyrzucany — security twierdzi, że wysłał kod, którego nie wysłał

**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/HttpSmsCodeChannel.java:40`

**Objaw:** Trzy rzeczy naraz. (1) `POST /account/factors/sms-code/enroll/start` bierze `target` z ciała żądania bez żadnej walidacji (dowolny string zalogowanego użytkownika; endpoint jest celowo zwolniony z bramki MFA). (2) Ten string wchodzi wprost do ręcznie sklejanego JSON-a — cudzysłów w numerze rozwala dokument i sms odpowiada 400 „invalid JSON”, a wstrzyknięcie `","to":"+48999…` przekierowuje kod na inny numer, bo `json.loads` zachowuje OSTATNI klucz. (3) `HttpResponse.BodyHandlers.discarding()` bez sprawdzenia `statusCode()` — 400 REJECTED jest nieodróżnialne od 202 SENT, więc security zapisuje challenge i odpowiada użytkownikowi tak, jakby kod poszedł. Konkretny scenariusz, w którym to boli bez żadnego atakującego: w klastrze `SECURITY_SMS_URL` jest świadomie pominięte, więc obowiązuje default `http://localhost:8088` wewnątrz poda security → connection refused → wyjątek połknięty → KAŻDA próba enrolmentu SMS w k8s kończy się „wysłaliśmy kod”, a kod nie istnieje i użytkownik nie ma jak dokończyć.

**Dowód:** FactorsController.java:69: `String target = body != null && body.get("target") != null ? body.get("target") : caller.value();` — i nic więcej. HttpSmsCodeChannel.java:40-41: `String body = "{\"to\":\"" + target + "\",\"subject\":\"Sign-in code\",...`; 43-47: `http.send(..., HttpResponse.BodyHandlers.discarding())` bez odczytu statusu; 48-52: `catch (Exception smsDown) { /* best-effort */ }`. Sprawdzone w Pythonie: `{"to":"+48111111111","to":"+48999999999",...}` → `json.loads` daje `{'to': '+48999999999', ...}`; `ala"ma` → `ValueError: Expecting ',' delimiter`. Reguła po stronie sms: `E164 = re.compile(r"^\+[1-9]\d{7,14}$")` (sms/server.py:30) — odrzuci jedno i drugie, tylko nikt tego nie słucha. k8s/base/security.yaml:8-10: „SECURITY_SMS_URL is omitted on purpose”.

**Naprawa:** (a) W `HttpSmsCodeChannel` budować ciało przez `JsonMapper`/`Map.of("to", target, ...)` zamiast konkatenacji; (b) sprawdzić `response.statusCode() / 100 != 2` i rzucić wyjątek, który `EnrolFactor.start` zamieni na 502 „nie udało się wysłać kodu” zamiast cichego sukcesu (dziś challenge zapisuje się mimo nieudanej wysyłki); (c) walidować `target` dla `SMS_CODE` tym samym wyrażeniem E.164 po stronie security, zanim powstanie challenge; (d) albo ustawić `SECURITY_SMS_URL: http://sms:8088` w k8s i dodać brakujące manifesty sms/push, albo wyłączyć czynnik SMS z `FactorRegistry` w tym profilu — komentarz „nikt w świeżym klastrze nie ma tego czynnika” to założenie o danych, a nie gwarancja, bo endpoint enrolmentu jest włączony.

**Uwaga:** brak werdyktu weryfikatora

#### P13-22. Trzy zielone suity, których nic nie uruchamia — CI nie zna tych repozytoriów, a same testy nie dotykają warstwy HTTP

**Plik:** `shared/.github/workflows/ci.yml:21-93`

**Objaw:** Suity istnieją i przechodzą (uruchomiłem: 5/4/4 testy, OK), ale w CI nie ma po nich śladu: workflow ma dwa joby (`reactor`, `e2e`), checkoutuje 11 repozytoriów javowych i nie checkoutuje microservice-idp/sms/push; jedyne kroki `run:` to `./mvnw clean install`, `mvnw package -DskipTests`, `npm ci`, `playwright install`, `./run-e2e.sh`. Nie ma ani `python`, ani `unittest`, ani `pytest`. Regresja w tych usługach przechodzi przez zielone CI. Do tego zakres samych suit jest wąski: importują wyłącznie czyste funkcje (`send`, `exchange`, `issue_code`, `userinfo`) i ani jeden test nie tworzy żądania HTTP przez `Handler`. Niepokryte jest dokładnie to, gdzie siedzą znaleziska: budowanie nagłówka `Location` w `_authorize` (wstrzyknięcie CRLF), walidacja parametrów `/authorize`, ścieżka formularza HTML, `do_POST` i parsowanie `Content-Length`, kody odpowiedzi (400/401/404/202), brak uwierzytelnienia `/send`, sprzątanie `_codes`/`_tokens`.

**Dowód:** `grep -n "repository:\|run:" .github/workflows/ci.yml` → 22 linie `repository:` (adjustable-clock … microservice-email), zero z nich to idp/sms/push; `run:` tylko w liniach 93, 166, 175, 178, 181 — wszystkie mavenowe/npm-owe. `grep -n "python\|pytest\|unittest" .github/workflows/ci.yml` → jedyne trafienie to `push:` jako trigger (linia 15). W testach: `from server import send` (sms/test_server.py:5, push:5), `from server import CLIENT_ID, ..., exchange, issue_code, ..., userinfo` (idp/test_server.py:9) — `Handler` nie jest importowany nigdzie.

**Naprawa:** Dodać do ci.yml job `python-stubs`: checkout tych trzech repo, `actions/setup-python@v5` z 3.12 i trzy kroki `python -m unittest discover` (bez zależności, bez pytest — suity są na stdlib). Osobno dopisać testy na warstwę HTTP: `http.server` da się uruchomić w wątku na porcie 0 i odpytać `urllib.request` — minimum to test, że `/authorize` z `\r\n` w `redirect_uri` daje 400, że `/send` bez klucza daje 401 i że `/token` z uszkodzonym `Content-Length` daje 400.

**Uwaga:** brak werdyktu weryfikatora

#### P13-23. minAge liczony od append (przed commitem) i domyślnie RÓWNY delivery.timeout.ms — re-send potrafi ścigać się z pierwszą próbą wciąż w locie, wbrew javadocowi; komentarz w comments twierdzi 'comfortably above' o wartościach równych

**Plik:** `/home/robert/Documents/git/shared/transactional-outbox/src/main/java/com/jrobertgardzinski/outbox/TransactionalOutbox.java:133`

**Objaw:** created_at jest stemplowane w append(), czyli w ŚRODKU transakcji biznesowej — zegar 'wieku' rusza zanim pierwsza próba w ogóle się zacznie (ta startuje po commicie). Oba serwisy używają RepublisherSettings.defaults (minAge=30 s) przy delivery.timeout.ms=30000: margines wynosi 0 minus czas trwania transakcji. Transakcja kasująca wątek/cascade trwająca T sekund + wolny broker (producent retryuje do pełnych 30 s) = republisher rutynowo wysyła drugą kopię, gdy pierwsza wciąż siedzi w akumulatorze producenta. Duplikaty konsumenci przeżyją, ale javadoc obiecuje, że to niemożliwe, a nie że rzadkie.

**Dowód:** TransactionalOutbox.java:133: insert.setTimestamp(7, Timestamp.from(clock.instant())) w append (przed commitem); TransactionalOutbox.java:163-165: "old enough that a first, after-commit attempt has CLEARLY either crashed or failed, so re-sending them CANNOT race an attempt still in flight"; RepublisherSettings.java:69: defaults = Duration.ofSeconds(30); memes application.properties:68: delivery.timeout.ms=${MEMES_KAFKA_DELIVERY_TIMEOUT_MS:30000}; CommentsOutboxConfig.java:77: "a 30s minimum age (comfortably above the 30s delivery timeout below, so a re-send never races an attempt still in flight)" — 30 nie jest 'comfortably above' 30.

**Naprawa:** Podnieść default minAge do 60 s (2× delivery.timeout serwisów) w RepublisherSettings.defaults i w javadocu pendingOlderThan zastąpić 'cannot race' uczciwym 'races only when a transaction outlives minAge − delivery.timeout'; w CommentsOutboxConfig.java:77 poprawić kłamiący komentarz (memes ma go już poprawnie: 'below ... would routinely duplicate').

**Korekta weryfikatora:** Waga do obniżenia w opisie skutku: wyścig materializuje się tylko, gdy producent retryuje niemal pełne 30 s ORAZ transakcja trwała T>0, a jego jedynym skutkiem jest duplikat — który projekt i tak jawnie akceptuje wszędzie indziej ('The re-send leg accepts duplicates by design', OutboxRepublisher.java:52-56). Realna usterka to nie zachowanie, tylko dwa kłamiące komentarze ('cannot race' w bibliotece, 'comfortably above' w comments) i zerowy margines domyślnych, którego nikt nie nazwał. Naprawa dokumentacyjna + podniesienie defaultu — nie bug wymagający zmiany mechanizmu.

#### P13-24. Obietnica kolejności per aggregate jest fałszywa: nieudana pierwsza próba + udana następna odwraca kolejność zdarzeń na tej samej partycji

**Plik:** `/home/robert/Documents/git/shared/transactional-outbox/src/main/java/com/jrobertgardzinski/outbox/OutboxEvent.java:29-30`

**Objaw:** Javadoc klucza partycji obiecuje, że konsument 'never sees a later hop of a cascade before an earlier one'. Ścieżka łamiąca: E1 dla agregatu X — pierwsza próba pada (czkawka brokera, wiersz zostaje niepublikowany); 2 s później E2 dla X — pierwsza próba przechodzi. Na partycję trafia E2, a E1 dopiero po >= minAge+interval (>= 30-45 s) z republishera. Konsument widzi późniejszy hop przed wcześniejszym — a zdarzenia estate nie niosą pola version (P12 N15), więc nie ma jak tego naprawić po stronie odbiorcy. Klucz partycji gwarantuje kolejność WYSYŁEK, nie kolejność ZDARZEŃ, gdy ścieżki best-effort i republishera się przeplatają.

**Dowód:** OutboxEvent.java:29-30: "@param key partition key: everything about one aggregate stays on one partition, so a consumer never sees a later hop of a cascade before an earlier one"; OutboxPublisher.publishWithoutWaiting (linie 53-75) wysyła każde zdarzenie natychmiast, bez sprawdzenia, czy dla tego samego event_key istnieje starszy wiersz z published = FALSE; republisher dosyła E1 dopiero po minAge (OutboxRepublisher.java:67-68).

**Naprawa:** W publishWithoutWaiting (lub w SpringOutbox.announce przed zaparkowaniem akcji) sprawdzić jednym SELECT-em, czy istnieje starszy niepublikowany wiersz o tym samym event_key — jeśli tak, pominąć pierwszą próbę i zostawić OBA wiersze republisherowi, który wysyła oldest-first i czeka na potwierdzenie każdego (kolejność zachowana). Alternatywnie minimalnie: usunąć fałszywą obietnicę z javadocu i udokumentować reordering jako możliwy.

**Korekta weryfikatora:** Drobna precyzja zakresu: obietnica mówi o 'hops of a cascade', a kolejne hopy kaskady wychodzą z RÓŻNYCH serwisów przez różne outboxy i topiki — tam klucz partycji jednego producenta nigdy nie mógł niczego gwarantować. Łamliwy i dowodliwy z kodu jest przypadek dwóch zdarzeń tego samego agregatu w JEDNYM outboksie (padła pierwsza próba E1, udana E2). Naprawa minimalna (uczciwy javadoc) jest adekwatniejsza niż SELECT po event_key przy każdej publikacji.

#### P13-25. offline-jwt: sfałszowany kid wymusza refetch JWKS przy każdym żądaniu — anonimowy wzmacniacz ruchu do security

**Plik:** `shared/offline-jwt/src/main/java/com/jrobertgardzinski/offlinejwt/OfflineJwtVerifier.java:128-145`

**Objaw:** keyFor() robi refetch JWKS zawsze, gdy kid nie jest w cache — bez względu na świeżość cache i bez żadnego dolnego limitu częstotliwości. Ponieważ świeży fetch nigdy nie będzie zawierał zmyślonego kid-a, KAŻDE anonimowe żądanie z nagłówkiem `Authorization: Bearer <token z losowym kid>` do dowolnego serwisu z bramką offline zamienia się w jeden HTTP GET na /.well-known/jwks.json security. Nieuwierzytelniony klient steruje więc 1:1 ruchem do serwisu tożsamości (amplifikacja na wszystkie serwisy-konsumery naraz); test `an_unknown_kid_triggers_one_refetch...` pokrywa wyłącznie kid, który po refetchu ISTNIEJE — wariant wrogi jest nietestowany.

**Dowód:** OfflineJwtVerifier.java:130-136: `boolean stale = clock.get().isAfter(fetchedAt.plus(keysMaxAge)); if (!stale && known.containsKey(kid)) { return known.get(kid); } // unknown kid ... — refetch; Map<String, PublicKey> fresh = jwksFetcher.get();` — jedynym warunkiem pominięcia refetchu jest obecność kid-a w cache; po refetchu fetchedAt się aktualizuje, ale następne żądanie z innym (lub tym samym) zmyślonym kid znów wpada w gałąź refetch, bo containsKey nadal false.

**Naprawa:** Zapamiętywać czas ostatniej PRÓBY refetchu i przy nieznanym kid odpuszczać refetch, jeśli od ostatniej próby minęło mniej niż krótki floor (np. 5–30 s): `if (!stale && (known.containsKey(kid) || clock.get().isBefore(lastRefetchAttempt.plus(refetchFloor)))) return known.get(kid);` plus aktualizacja lastRefetchAttempt przed jwksFetcher.get(). Prawdziwa rotacja kluczy nadal propaguje się w sekundach, a zalew śmieciowych kid-ów kosztuje najwyżej jeden fetch na floor.

#### P13-26. Tabela sessions nigdy nie jest sprzątana — 95% wierszy na dev to martwe sesje z adresami e-mail

**Plik:** `shared/microservice-security/security-infrastructure/src/main/java/com/jrobertgardzinski/persistence/SessionJdbcRepository.java:24-26`

**Objaw:** Jedyne operacje usuwające sesje to deleteByFamilyId (logout / reuse) i deleteByEmail (kasacja konta). Wygaśnięcie refresh tokenu nie zmienia nawet statusu (Expired w RefreshSession niczego nie zapisuje), a skoro — jak ustalił P12 W1 — żadne UI nie woła POST /logout, rodziny praktycznie nigdy nie są kasowane. Każde logowanie i każdy refresh to nowy wiersz z e-mailem, który zostaje na zawsze: rekonesans po zrzucie bazy dostaje pełną historię logowań per użytkownik, a listActiveSessions filtruje po statusie ACTIVE, więc dawno wygasłe wiersze wciąż udają aktywne (status nie jest flagą prawdy — prawdą jest kolumna z datą).

**Dowód:** Żywy stack (tylko SELECT): `SELECT count(*) FILTER (WHERE refresh_token_expiration < now()), count(*), count(DISTINCT email) FROM sessions` → 512 wygasłych z 541 wszystkich, 418 różnych e-maili; najstarszy wiersz ACTIVE ma refresh_token_expiration 2026-07-03 (25 dni po wygaśnięciu, status wciąż ACTIVE). Grep po @Scheduled w security: tylko OutboxPublisher.java:55 i AccountDeletionTimeouts.java:24 — żadnego jobu sprzątającego sesje; w migracjach V1–V17 brak jakiegokolwiek DELETE/TTL dla sessions.

**Naprawa:** Dodać w security-infrastructure job `@Scheduled(fixedDelay = "1h") void purgeExpiredSessions()` wykonujący `DELETE FROM sessions WHERE refresh_token_expiration < now() - interval '24 hours'` (bufor = pełne okno ważności refresh, żeby replay świeżo wygasłego zrotowanego tokenu wciąż trafiał w detekcję reuse zanim wiersz zniknie), plus metoda `deleteByRefreshTokenExpirationBefore(LocalDateTime)` w SessionJdbcRepository.

#### P13-27. Suita cucumber comments zjada 18 z 20 żetonów wspólnego rate-limitera, a krok „aCleanSlate" nie czyści limitera — dwa dopisane komentarze w dowolnym nowym scenariuszu wywrócą niepowiązany scenariusz na 429

**Plik:** `/home/robert/Documents/git/portal/microservice-comments/src/test/java/com/jrobertgardzinski/comments/infrastructure/cucumber/CommentThreadSteps.java:48`

**Objaw:** Wszystkie 16 scenariuszy dzieli jeden kontekst Springa (CucumberSpringConfiguration), więc i jeden bean RateLimit z produkcyjnym domyślnym 20/min na autora — test application.properties nie nadpisuje comments.rate-limit.per-minute, a cała suita biegnie w ~2 s, czyli w JEDNYM oknie minutowym. Policzone po feature: alice wykonuje 18 POST-ów przechodzących przez tryAcquire (2× „she comments" — w tym „Halo?" pod nieistniejący mem, bo tryAcquire biegnie PRZED addComment.execute; 10× „her comment"; 5× „5 comments"; 1× komentarz 2000 znaków — 2001-znakowy odpada wcześniej na walidacji długości). Margines to 2 żetony: dodanie scenariusza z trzema komentarzami (albo powiększenie paginacji z 5 do 8) sprawia, że któryś PÓŹNIEJSZY scenariusz dostaje 429 w kroku `herComment` asertującym 201 — czerwony test w miejscu niemającym nic wspólnego z przyczyną. Hook @Before nazywa się i komentuje jako „a clean slate", ale czyści tylko wątek (kaskadą MEME_DELETED) i vote store — licznika limitera nie.

**Dowód:** CommentThreadSteps.java:48-57: `@Before public void aCleanSlate() { voteStore.outage(false); memesEventsAnnouncer.accept(...MEME_DELETED...); cascadeAnnouncements.forget(); }` — zero kontaktu z RateLimit. CommentsConfig.java:45: `RateLimit commentRate(@Value("${comments.rate-limit.per-minute:20}") int perMinute)`. src/test/resources/application.properties: brak jakiegokolwiek wpisu rate-limit (całość: datasource H2, kafka-enabled=false, dwa URL-e stubów). CommentController.java:77-84: najpierw walidacja długości (`> Comment.MAX_LENGTH` → 400), dopiero potem `if (!commentRate.tryAcquire(author)) return 429`. Surefire: RunCucumberTest time=2.089s, tests=16, failures=0.

**Naprawa:** Dopisać `comments.rate-limit.per-minute=0` do src/test/resources/application.properties (zachowanie „zero disables the guard" jest już przypięte w RateLimitTest), a kontrakt 429 (status, Retry-After: 60, body RATE_LIMITED) pokryć osobnym testem kontrolera z własnym `new RateLimit(1)` — dziś ścieżka 429 nie ma ŻADNEGO testu na poziomie HTTP (grep za 429/RATE_LIMITED po src/test: zero trafień).

**Uwaga:** brak werdyktu weryfikatora

#### P13-28. Pacty security wobec portalu (ACCOUNT_DELETION_REQUESTED, introspekcja /me) są po cichu skipowane na dysku dewelopera i w CI jądra — @EnabledIf zamienia zły relatywny path w zielony przebieg, a security nie ma strażnika SilentlySkippedPactTest

**Plik:** `/home/robert/Documents/git/shared/microservice-security/security-infrastructure/src/test/java/com/jrobertgardzinski/OffboardingFactsPactProviderTest.java:24`

**Objaw:** @PactFolder("../../microservice-offboarding/pacts") i @PactFolder("../../microservice-memes/pacts-http") (MeIntrospectionPactProviderTest.java:34) rozwiązują się do shared/microservice-*, a od podziału na trzy workspace'y (2026-07-12) ci konsumenci mieszkają w ../portal — katalog nie istnieje, więc @EnabledIf wyłącza oba testy przy każdym `./mvnw install` w shared na dysku ORAZ w jobie „Build and test the shared kernel" (shared ci.yml nie checkoutuje żadnego portalowego konsumenta). Komentarz w memes ci.yml „The reactor CI (in the aggregator repo) still validates everything together" jest dla tych paktów nieprawdziwy. Jedyne miejsce, gdzie biegną, to CI własnego repo security — przeciwko zdalnym mainom konsumentów, które są 7-11 commitów za dyskiem. Literówka w temacie/kształcie faktu startującego sagę usuwania konta przechodzi więc lokalny build i CI jądra bez jednej czerwonej kropki — dokładnie klasa dziury, dla której memes i comments dostały SilentlySkippedPactTest.

**Dowód:** OffboardingFactsPactProviderTest.java:24-31: `@PactFolder("../../microservice-offboarding/pacts") @EnabledIf(value = "consumerPactsCheckedOut" ...) { return Files.isDirectory(Path.of("../../microservice-offboarding/pacts")); }`; `ls /home/robert/Documents/git/shared/microservice-offboarding` → "No such file or directory"; lista checkoutów shared/.github/workflows/ci.yml:29-83 bez microservice-memes/offboarding; grep po security-infrastructure/src/test: brak SilentlySkippedPactTest

**Naprawa:** Dodać w security odpowiednik SilentlySkippedPactTest (lista wymaganych katalogów konsumentów, assert zamiast skip) i nauczyć ścieżki układu trzech workspace'ów (fallback ../../../portal/microservice-offboarding/pacts obok ../../ — jak SilentlySkippedPactTest w memes rozwiązuje NEIGHBOURS_ROOT), albo dodać checkout obu portalowych konsumentów do shared ci.yml; w obu wariantach poprawić kłamiący komentarz o reaktorze w sub-repo ci.yml

**Korekta weryfikatora:** Dwa retusze opisu. (1) Path nie jest „zły" bezwzględnie — jest CELOWO skrojony pod układ sibling-checkout CI własnego repo security, gdzie działa; dziura polega na tym, że w układzie trzech workspace'ów (dysk dewelopera + oba joby CI jądra) nigdy się nie rozwiązuje, a javadoc nazywa tylko „skip when not checked out", nie fakt, że lokalnie to skip PERMANENTNY. (2) Komentarz w memes/comments ci.yml:6 („The reactor CI ... still validates everything together") jest napisany o providerowym teście memes wobec paktu offboardingu — nie o paktach security; wobec paktów security żaden reaktor niczego nie waliduje (portal ci.yml instaluje shared z -DskipTests), ale nazywanie tego komentarza „nieprawdziwym dla tych paktów" to naciąganie cudzego zdania. Naprawa bez zmian: strażnik + fallback ../../../portal/... albo checkout konsumentów w shared ci.yml.

#### P13-29. Job e2e w portal ci.yml na porażce wysyła glob logów, których nic nie tworzy — pierwsza awaria 26-kontenerowego stacka na hostowanym runnerze będzie niediagnozowala; job nie ma też timeout-minutes

**Plik:** `/home/robert/Documents/git/portal/.github/workflows/ci.yml:282`

**Objaw:** Krok „Upload service/UI logs on failure" uploaduje `/tmp/*-e2e-*.log`, ale memes-ui/run-e2e.sh od przejścia na pełny stack (2026-07-20) nie pisze żadnych logów do /tmp — wzorzec został skopiowany z security-ui, którego run-e2e.sh faktycznie loguje do /tmp/security-ui-e2e-*.log. Nie ma też kroku `docker compose logs` (e2e-saga.yml ma go i tłumaczy dlaczego). Nagłówek e2e-saga.yml sam przyznaje, że ten stack nigdy nie wstał na hostowanym runnerze — czyli pierwszy realny przebieg jobu najpewniej będzie czerwony, a artefakt diagnostyczny będzie pusty. Do tego job bez `timeout-minutes` może wisieć do 6h na zaklinowanym stacku (e2e-saga.yml ustawia 60 z uzasadnieniem).

**Dowód:** portal/.github/workflows/ci.yml:277-282: `if: failure() ... name: e2e-logs path: /tmp/*-e2e-*.log`; grep -n "tmp|.log" po infra-up.sh, memes-ui/run-e2e.sh, memes-up.sh → zero trafień; e2e-saga.yml:254-268 ma krok `docker compose logs ... > /tmp/portal-stack.log` + upload, ci.yml go nie ma

**Naprawa:** Skopiować do jobu e2e w ci.yml parę kroków z e2e-saga.yml: `docker compose ps -a; docker compose logs --no-color --timestamps > /tmp/portal-stack.log` (working-directory: portal, if: failure()) i upload tego pliku zamiast martwego globa; dodać `timeout-minutes: 60` na jobie

**Uwaga:** brak werdyktu weryfikatora

#### P13-30. image-encoder: jedno żądanie na własnym zadeklarowanym suficie 25 mln pikseli bierze 326 MB RSS, a kontener ma limit 256Mi i nieograniczoną liczbę wątków

**Plik:** `microservice-image/server.py:63`

**Objaw:** Serwis deklaruje w docstringu trzy „guard-rails" (MAX_UPLOAD_BYTES 12 MiB, MAX_IMAGE_PIXELS 25 mln, SOCKET_TIMEOUT 30 s) i żaden z nich nie został skonfrontowany z pamięcią kontenera. Zmierzone w czystym procesie: obraz 5000x5000 (dokładnie 25 mln pikseli, plik 92 kB) przechodzi przez `Image.open` + `load()` + `save(WEBP)` ze szczytem **326 MB RSS** — przy `limits.memory: 256Mi` to OOMKill przy JEDNYM żądaniu, bez żadnej współbieżności. Drugą osią jest brak jakiegokolwiek odpowiednika semafora z memes: `ThreadingHTTPServer` tworzy wątek na połączenie bez sufitu, a wołający ma 200 wątków Tomcata i wywołuje encoder z publicznego, nieuwierzytelnionego i nieobjętego rate-limitem `GET /memes/{id}` z `Accept: image/webp` przy każdym pudle cache'a (`ServeMeme.java:58`, brak single-flight — N równoczesnych żądań o ten sam mem to N równoczesnych enkodowań). Dla obrazów, które memes faktycznie wysyła (max 1024 px), koszt to zmierzone ~17 MB na żądanie ponad baseline, czyli ~13 równoczesnych enkodowań wyczerpuje 256Mi. Skutek jest niewidoczny, bo `HttpImageEncoder` łyka każdą awarię (`catch (Exception down) { return Optional.empty(); }`) i serwuje PNG — to samo cichnięcie, które P12 opisuje w S17.

**Dowód:** server.py:62-63: `MAX_UPLOAD_BYTES = int(os.environ.get("MAX_UPLOAD_BYTES", str(12 * 1024 * 1024)))`, `MAX_IMAGE_PIXELS = int(os.environ.get("MAX_IMAGE_PIXELS", "25000000"))`. server.py:233: `ThreadingHTTPServer(("", port), Handler).serve_forever()` — bez puli, bez semafora. k8s/base/image-encoder.yaml:38-43: `requests: memory: 64Mi` / `limits: memory: 256Mi`. Pomiar (świeży proces, Pillow): „RSS after read MB 17.6 body bytes 91973 / big.png peak RSS MB 326.2" oraz „small.png peak RSS MB 34.7" dla 1024x1024. ServeMeme.java:58: `return encoder.toWebp(png).map(webp -> { cacheBestEffort(memeId, webpKey, webp); … })` — cache zapisywany dopiero PO enkodowaniu. HttpImageEncoder.java:56-57: `catch (Exception down) { return Optional.empty(); }`.

**Naprawa:** (1) Zejść z `MAX_IMAGE_PIXELS` do wartości, którą limit kontenera udźwignie przy zakładanej współbieżności, albo podnieść `limits.memory` — te dwie liczby muszą być ustawione razem i tak opisane (dziś nie ma między nimi żadnego związku). Dla obecnego użycia (memes wysyła max 1024x1024) uczciwym domyślnym jest ~2 mln pikseli. (2) Ograniczyć współbieżność w samym encoderze: `threading.Semaphore(N)` wokół `encode()` z odmową 429 po timeoucie — dokładnie wzorzec, który memes już ma w `ConcurrencyGuardedImageOptimizer`, i który tu został pominięty. (3) Ustawić `MAX_IMAGE_PIXELS`/`MAX_UPLOAD_BYTES` jawnie w `docker-compose.yml` i `k8s/base/image-encoder.yaml` — dziś obie wartości są niewidocznymi domyślnymi w kodzie. (4) Single-flight na `.webp` w `ServeMeme` (klucz mema → jedno enkodowanie, reszta czeka), żeby pudło cache'a nie mnożyło żądań przez liczbę wątków Tomcata.

**Korekta weryfikatora:** Tytuł i pierwsza oś są ZA MOCNE — nagłówkowy scenariusz („jedno żądanie = OOMKill”) jest przy realnym przepływie nieosiągalny i to trzeba w planie napisać, bo inaczej Robert naprawi nie to, co trzeba:

(a) Encoder jest wyłącznie wewnętrzny. Sprawdzone na żywym stacku: `docker ps` → `security-image-encoder-1  8087/tcp` (BEZ mapowania na hosta; `docker-compose.yml:114` „internal only — not published to the host”). W k8s `image-encoder.yaml` to Deployment + ClusterIP Service bez żadnego wpisu w `ingress.yaml`. Do tego jedyny wołający — memes — wysyła wyłącznie obrazy już zoptymalizowane do `memes.image.max-dimension=1024` (`PublishMeme.java:34` → `optimize()`) albo miniatury 256 px. Obraz 5000x5000 nie może dojść do encodera z produktu; wymaga dostępu do sieci podów/compose.
  → Pierwsza oś to **niespójność konfiguracji** (zadeklarowany sufit 25 mln px nie ma nic wspólnego z limitem 256Mi), a nie osiągalny wektor. Sama w sobie: NISKI.

(b) Osią, która NAPRAWDĘ jest osiągalna publicznie, jest współbieżność, i to ona powinna być tytułem: anonimowy `GET /memes/{id}` z `Accept: image/webp` na memie bez zbuforowanego `.webp` odpala enkodowanie ~17 MB, bez rate-limitu, bez single-flight, przy 200 wątkach Tomcata i braku semafora w encoderze — ~13 równoczesnych to całe 256Mi. Wektor jest POWTARZALNY, i to jest najmocniejszy argument, którego znalazca nie użył: OOMKill w trakcie enkodowania oznacza, że `cacheBestEffort` nigdy nie zapisze `.webp`, więc te same id pozostają pudłami cache'a i ten sam strzał można powtarzać w kółko. Skutek jest niewidoczny (`HttpImageEncoder` łyka wyjątek, zero Loggera, zero metryki).

(c) Naprawy (2) semafor + (4) single-flight zostają jako właściwe; (1) obniżenie `MAX_IMAGE_PIXELS` do ~2 mln i (3) jawne ustawienie obu zmiennych w compose/k8s to higiena spójności, nie zamknięcie dziury. Waga SREDNI po tej korekcie jest adekwatna.


### NISKI

#### P13-31. Test deduplikacji dowodzi mniej, niż obiecuje nazwa — duplikat wysłany po await nie obali testu

**Plik:** `shared/microservice-email/src/test/java/com/jrobertgardzinski/mail/boundary/MailRequestsConsumerTest.java:89`

**Objaw:** `a_redelivered_event_is_deduplicated_and_garbage_is_dropped` wysyła dwa zdarzenia o tym samym id, czeka `await().until(size == 1)` i sprawdza tylko temat pierwszego maila. Await kończy się w chwili dostarczenia PIERWSZEGO maila; jeśli deduplikacja pęknie i drugi mail wyjdzie milisekundę później, test nadal jest zielony — nigdy nie asertuje, że rozmiar POZOSTAJE 1. To dokładnie klasa wady N5 z P12 (krok dowodzi mniej, niż mówi nazwa) — a to jedyny test dedupu w suicie.

**Dowód:** MailRequestsConsumerTest.java:89-90: `await().until(() -> mailbox.getMailsSentTo("dup@example.com").size() == 1); assertEquals("Reset your password", ...getSubject());` — po await nie ma żadnej dalszej asercji rozmiaru.

**Naprawa:** Po duplikacie wysłać trzecie zdarzenie-zamykacz do INNEGO adresata, poczekać aż ono dotrze (kanał jest sekwencyjny, więc duplikat został już przetworzony) i dopiero wtedy `assertEquals(1, mailbox.getMailsSentTo("dup@example.com").size())`.

#### P13-32. Trzy kontenery pythonowe chodzą jako root, w odróżnieniu od serwisu, którym się w dokumentacji przedstawiają

**Plik:** `shared/microservice-sms/Dockerfile:1-6`

**Objaw:** Żaden z trzech Dockerfile'i nie tworzy użytkownika ani nie ustawia `USER`, więc proces działa jako root — także w k3s, gdzie idp nie ma `securityContext` (brak `runAsNonRoot`, `allowPrivilegeEscalation`, `readOnlyRootFilesystem`). Nie jest to samo w sobie luka, ale to dokładnie ta warstwa, w której siedzi wykonywalne wstrzyknięcie nagłówków z pierwszego znaleziska, a docstring sms mówi „the same shape as the image encoder” — image encoder akurat prawa zrzuca. Rozjazd jest niezamierzony, nie decyzją.

**Dowód:** microservice-sms/Dockerfile w całości: `FROM python:3.12-slim / WORKDIR /app / COPY server.py . / EXPOSE 8088 / CMD ["python", "server.py"]` — brak `USER`; identycznie idp i push. Kontrola na żywo: `docker inspect -f '{{.Name}} user=[{{.Config.User}}]'` → `/security-idp-1 user=[]`, `/security-sms-1 user=[]`, `/security-push-1 user=[]`, `/security-image-encoder-1 user=[encoder]`. Wzorzec do skopiowania: portal/microservice-image/Dockerfile:7-8 `RUN useradd --system --no-create-home encoder` + `USER encoder`.

**Naprawa:** Dopisać w każdym z trzech Dockerfile'i przed `CMD`: `RUN useradd --system --no-create-home svc` i `USER svc`. W k8s/base/idp.yaml dodać `securityContext: {runAsNonRoot: true, allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: [ALL]}}`.

**Uwaga:** brak werdyktu weryfikatora

#### P13-33. Dwie instancje relaya: brak SKIP LOCKED/leasingu i brak słowa dokumentacji, a Deploymenty memes/comments nie mają strategy: Recreate

**Plik:** `/home/robert/Documents/git/shared/transactional-outbox/src/main/java/com/jrobertgardzinski/outbox/TransactionalOutbox.java:79-80`

**Objaw:** pendingOlderThan to zwykły SELECT bez FOR UPDATE SKIP LOCKED i bez leasingu — dwa relaye naraz wybierają te same <=500 wierszy i oba je wysyłają (po awarii brokera: podwójna burza duplikatów całego backlogu, każdy pass). K8s: memes.yaml i comments.yaml mają replicas: 1, ale BEZ strategy: Recreate, więc domyślny RollingUpdate gwarantuje okno z dwoma podami przy każdym deployu (dokładny analog S6 z P12 dla sweepera offboardingu). Konsumenci są idempotentni, więc to 'tylko' duplikaty — ale tabela gwarancji w package-info i README nie wspominają trybu wielo-instancyjnego ani słowem, więc następny serwis z replicas: 2 wdroży to w ciemno.

**Dowód:** selectPendingSql (TransactionalOutbox.java:79-80) bez klauzuli blokującej; package-info.java — tabela 'Who guarantees what' (a)-(h) nie ma wiersza o współbieżnych relayach; portal/k8s/base/memes.yaml:14 'replicas: 1' bez pola strategy (comments.yaml:12 tak samo), podczas gdy security-postgres.yaml:24-25 pokazuje, że konwencja Recreate jest w repo znana.

**Naprawa:** Minimalnie: dopisać do package-info/README wiersz gwarancji 'wiele instancji = duplikaty proporcjonalne do liczby instancji' i dodać strategy: {type: Recreate} do memes.yaml i comments.yaml. Docelowo: w pendingOlderThan użyć FOR UPDATE SKIP LOCKED w transakcji obejmującej wysyłkę batcha (na H2 w MODE=PostgreSQL składnia przechodzi) albo lease przez UPDATE ... SET claimed_until.

#### P13-34. Okno forensyczne retencji liczone od created_at, nie od dostarczenia — po awarii dłuższej niż retention dowód dostarczenia znika w 15 s po dosłaniu

**Plik:** `/home/robert/Documents/git/shared/transactional-outbox/src/main/java/com/jrobertgardzinski/outbox/TransactionalOutbox.java:222`

**Objaw:** Javadoc retention obiecuje okno forensyczne 'did we really send that, and when?' mierzone w godzinach. Cutoff reapera liczony jest jednak od created_at: wiersz, który przeleżał niepublikowany dłużej niż retention (awaria brokera > 24 h, albo trucizna, która w końcu przeszła) po dostarczeniu i zamarkowaniu jest starszy niż okno OD RAZU — następny pass (15 s później) go kasuje. Dokładnie po masowej awarii, gdy pytanie 'co i kiedy naprawdę dosłaliśmy' jest najcenniejsze, ślad żyje jeden interwał zamiast 24 h.

**Dowód:** TransactionalOutbox.java:222: Timestamp cutoff = Timestamp.from(clock.instant().minus(retention)) porównywany z created_at (deletePublishedSql:84-86: WHERE published = TRUE AND created_at <= ?); RepublisherSettings.java:25-27: "retention how long a DELIVERED row is kept before being reaped. It buys a forensic window (»did we really send that, and when?«), so it is measured in hours" — a boolean published (OutboxTable.ddl) nie niesie czasu dostarczenia, co javadoc DDL uzasadnia odrzuceniem published_at.

**Naprawa:** Zamienić published boolean na published_at timestamp null (NULL = niedostarczone) i reapować po published_at <= now - retention; predykaty polla zmieniają się mechanicznie (published_at IS NULL). Jeśli kolumna ma zostać booleanem świadomie — poprawić javadoc RepublisherSettings, żeby nie obiecywał okna liczonego od dostarczenia.

**Korekta weryfikatora:** Znalezisko jest w istocie SŁABSZE i szersze zarazem: pytanie 'and when?' jest nieodpowiadalne ZAWSZE, nie tylko po awarii — tabela przechowuje boolean, żaden wiersz nigdy nie niesie czasu dostarczenia, więc okno forensyczne w kształcie obiecanym przez RepublisherSettings nie istniało od początku. Przypadek 'awaria > retention' (domyślnie 24 h) to rzadki skrajny scenariusz, w którym dodatkowo znika samo 'did we really send that'. Waga NISKI słuszna; naprawa minimalna to korekta javadocu RepublisherSettings, nie migracja kolumny.

#### P13-35. offline-jwt: kontrola exp używa Instant.now() zamiast wstrzykniętego zegara — rozjazd z mint-em w środowiskach ze sterowanym czasem

**Plik:** `shared/offline-jwt/src/main/java/com/jrobertgardzinski/offlinejwt/OfflineJwtVerifier.java:108`

**Objaw:** Konstruktor przyjmuje Supplier<Instant> clock (javadoc: "Plus control of time"), ale steruje on wyłącznie świeżością cache JWKS (linia 130); wygaśnięcie tokenu porównywane jest z gołym Instant.now(). Security mintuje exp z wstrzykiwanego java.time.Clock (w kernelu jest adjustable-clock; RunHttpRefreshTest jawnie "steruje zegarem środowiska test", by osiągnąć wygaśnięcie sesji), więc w każdym środowisku z przesuniętym zegarem bramka offline ocenia exp według innego czasu niż introspekcja i mint — scenariuszy wygaśnięcia nie da się przetestować offline przez sterowanie czasem, a test `a_removed_key_stops_verifying...` musi obchodzić to, kręcąc exp na prawdziwym now().

**Dowód:** OfflineJwtVerifier.java:107-108: `if (!EXPECTED_ISSUER.equals(claims.path("iss").asText()) || claims.path("exp").asLong() <= Instant.now().getEpochSecond())` vs linia 130: `boolean stale = clock.get().isAfter(fetchedAt.plus(keysMaxAge));` — dwa źródła czasu w jednej klasie; OfflineJwtVerifierTest.java:119-120 buduje token z `Instant.now().getEpochSecond() + 3600`, mimo że test steruje `now` atomową referencją.

**Naprawa:** W verify() zamienić `Instant.now().getEpochSecond()` na `clock.get().getEpochSecond()` i dodać test wygaśnięcia sterowany wstrzykniętym zegarem (token z exp w przyszłości, przesunięcie now poza exp → verify puste).

#### P13-36. IdempotentCommandsTest w comments obiecuje „prawo dla każdej komendy z JEDNYM wyjątkiem", a pomija dwie komendy — w tym VoteOnComment, który jest drugim, niezadeklarowanym wyjątkiem (toggle: dwa wywołania ≠ jedno); rejestr w ADR 0006 kłamie o comments

**Plik:** `/home/robert/Documents/git/portal/microservice-comments/src/test/java/com/jrobertgardzinski/comments/application/IdempotentCommandsTest.java:24`

**Objaw:** Javadoc testu: „every command is idempotent BY DEFAULT … the ONE command that CANNOT obey it (adding a comment …) is a DECLARED EXCEPTION". Komend w comments jest sześć (AddComment, DeleteComment, DeleteThread, HideComment, PurgeUserComments, VoteOnComment), a mapa COMMANDS obejmuje trzy + wyjątek AddComment. VoteOnComment ŁAMIE prawo z definicji — drugi identyczny głos to retrakcja (własny feature to przypina: „the same second user up-votes it again → score of 1"), a nie jest ani w COMMANDS, ani zadeklarowany jako wyjątek; HideComment (idempotentny) też nie jest egzekwowany. Rejestr ADR 0006 („microservice-comments … declared exception: AddComment") jest przez to nieaktualny — memes ma tam CastVote jako wyjątek, bliźniaczy toggle comments zniknął. To dokładnie failure mode, który ADR sam przewiduje: „forgetting them there is the new failure mode". Skutek praktyczny: nowa/zmieniona komenda głosowania lub moderacji może przestać być idempotentna i żaden test „prawa" tego nie zauważy, mimo że jego nazwa i javadoc twierdzą, że pilnuje całego serwisu.

**Dowód:** IdempotentCommandsTest.java:100-115: COMMANDS = {DeleteComment ×2, DeleteThread, PurgeUserComments} — brak VoteOnComment i HideComment; :24-27 javadoc: „the one command that CANNOT obey it (adding a comment — two calls are two comments, by design) is a DECLARED EXCEPTION". comment-thread.feature:26-28: „When 2 users up-vote that comment / And the same second user up-votes it again / Then … score of 1" — dowód nieidempotencji VoteOnComment. shared/docs/adr/0006-idempotent-commands-by-default.md:56-57: „microservice-comments — IdempotentCommandsTest; declared exception: AddComment" (jedyny), :60: memes deklaruje CastVote.

**Naprawa:** Dopisać HideComment do COMMANDS (świat testu ma już CommentModeration in-memory w PurgeAndCascadeTest do wzięcia) i dodać drugi test „DECLARED EXCEPTION: voting twice toggles the vote away — by design" (analogicznie do add_comment_is_the_declared_exception), plus jedna linia w rejestrze ADR 0006: „declared exceptions: AddComment, VoteOnComment".

**Uwaga:** brak werdyktu weryfikatora

#### P13-37. Krok paginacji „{int} comments are returned" dowodzi wyłącznie liczności stron — scenariusz „a long thread is read one page at a time" pozostanie zielony nawet gdy strony się nakładają i gubią komentarz (czyli dokładnie przy defekcie N3)

**Plik:** `/home/robert/Documents/git/portal/microservice-comments/src/test/java/com/jrobertgardzinski/comments/infrastructure/cucumber/CommentThreadSteps.java:182`

**Objaw:** Jedyne asercje scenariusza paginacji to `assertEquals(expected, lastPage.jsonPath().getList("id").size())` dla strony 0 (2 szt.) i strony 2 (1 szt.). Liczności przy offset/limit dowodzą tylko, że wierszy jest 5 — nie dowodzą, że strony są rozłączne ani że w sumie pokazują wszystkie komentarze. Produkcyjne zapytanie sortuje po `ORDER BY created_at` bez tie-breaka (znane N3), a każda strona to osobne zapytanie z osobnym sortowaniem: przy równych created_at (komentarze wstawiane pętlą w tym samym ms) SQL nie gwarantuje stabilnego porządku między zapytaniami, więc „comment 1" może wystąpić na stronie 0 i 2, a „comment 3" nigdzie — i suita tego nie zauważy. To odpowiednik N5: nazwa scenariusza obiecuje „czytanie wątku strona po stronie", asercja sprawdza rozmiar tablicy.

**Dowód:** CommentThreadSteps.java:182-190: oba kroki `commentsReturned`/`commentReturned` to wyłącznie `.getList("id").size()`; manyComments (:167-173) asertuje tylko 201 przy tworzeniu. JdbcCommentRepository.java:38-41: `SELECT … WHERE meme_id = ? ORDER BY created_at` + offset/limit — bez `, id`. PLAN-P12 N3 potwierdza brak tie-breaka jako defekt produkcyjny; tu dokument: żaden test nie stanie się czerwony, gdy N3 wystrzeli.

**Naprawa:** W kroku czytać też treści/id-ki: zapamiętać id z `manyComments`, po przejściu wszystkich stron (0,1,2) asercja `union stron == 5 różnych id` (rozłączność + kompletność). To zamienia scenariusz w strażnika naprawy N3 (`ORDER BY created_at, id`) zamiast dekoracji.

**Uwaga:** brak werdyktu weryfikatora

#### P13-38. collections.feature deklaruje scenariusze @saga jako „Kafka-borne", ale krok „alice's account is purged" woła use case bezpośrednio — z pominięciem nawet PurgeCommandsConsumer, którego bliźniaczy krok negatywny używa; happy-path potwierdzenia sagi nie asertuje żaden scenariusz BDD

**Plik:** `/home/robert/Documents/git/portal/microservice-user-collections/src/test/java/com/jrobertgardzinski/collections/appsteps/CollectionsSteps.java:48`

**Objaw:** Feature (linie 37-38) tłumaczy podział tagów: „the HTTP entry point never sees the Kafka-borne @saga scenarios below", a javadoc HttpBddTest dokłada: „the @saga scenario, whose purge arrives over Kafka". Tymczasem w jedynym miejscu, gdzie te scenariusze się wykonują (ApplicationBddTest), krok główny robi `purgeUserItems.execute(user)` — omija parsowanie komendy PURGE_USER_CONTENT i budowę potwierdzenia, choć krok sąsiedni („a purge command arrives naming nobody") używa prawdziwego `purgeConsumer.handle(...)`. W efekcie żywa dokumentacja twierdzi, że pokrywa wejście sagi, a scenariusz szczęśliwej ścieżki nie przechodzi przez nie wcale; potwierdzenie USER_CONTENT_PURGED wracające do orkiestratora nie jest asertowane w ŻADNYM scenariuszu feature'a (tylko negacja „no confirmation goes back"). Realne pokrycie istnieje wyłącznie w unit teście (PurgeCommandsConsumerTest.a_purge_command_clears_the_user_and_confirms) — spec kłamie o swoim zasięgu, nie system o zachowaniu.

**Dowód:** CollectionsSteps.java:48-51: `@When("^(\\w+)'s account is purged$") … lastPurgeCount = purgeUserItems.execute(user);` vs :53-57: `purgeCommandNamingNobody() { lastConfirmation = purgeConsumer.handle("{\"type\":\"PURGE_USER_CONTENT\",\"email\":\"\",…}"); }`. collections.feature:37-38: „just as the HTTP entry point never sees the Kafka-borne @saga scenarios below"; :51-58: scenariusz „Deleting the account purges every collection" bez żadnego kroku o potwierdzeniu.

**Naprawa:** Przepisać krok na konsumenta: `lastConfirmation = purgeConsumer.handle("{…PURGE_USER_CONTENT, email: alice, sagaId: saga-1…}")` (purgeCount wyciągnąć z odpowiedzi lub z listingu) i dodać do scenariusza krok „Then a confirmation for that saga goes back to the orchestrator" asertujący type=USER_CONTENT_PURGED i sagaId — symetrycznie do istniejącej negacji.

**Uwaga:** brak werdyktu weryfikatora

#### P13-39. gallery.feature deklaruje „(in-memory stores)", choć suita od 2026-07-20 jedzie na pełnym stacku compose — nagłówek specyfikacji kłamie o tym, co jest testowane

**Plik:** `/home/robert/Documents/git/portal/microservice-memes/memes-ui/e2e/features/gallery.feature:4`

**Objaw:** Nagłówek feature: „These scenarios drive the React UI with Playwright against real memes + comments + security services (in-memory stores)." — dopisek o in-memory stores to relikt starego harnessu na czterech jarach; dziś run-e2e.sh startuje pełny stack (Postgresy, Kafka, Mailpit) i własny nagłówek skryptu to podkreśla. Czytelnik specyfikacji (a to repo jest portfolio) dostaje fałszywą informację o sile dowodu, jaki daje ta suita.

**Dowód:** gallery.feature:4: „against real memes + comments + security services (in-memory stores)" vs run-e2e.sh:2-16: „the scenarios run against the REAL portal stack (docker compose), where security keeps its state in Postgres ... It used to boot four jars with in-memory stores"

**Naprawa:** Usunąć „(in-memory stores)" z nagłówka gallery.feature (pozostałe trzy feature'y memes-ui mają już poprawne nagłówki o LIVE stacku)


---
## Obalone w weryfikacji

**Kolejność filtrów API-key i rate-limit jest nieokreślona — komentarz twierdzi inaczej, a odwrotna kolejność daje nieuwierzytelniony DoS na wysyłkę** (`shared/microservice-email/src/main/java/com/jrobertgardzinski/mail/boundary/ApiKeyFilter.java:18`)

Fakt bazowy się zgadza (ApiKeyFilter.java:17-18 nie ma @Priority, RateLimitFilter.java:22 ma USER=5000), ale objaw obalony empirycznie na żywym stacku: 125 kolejnych żądań GET /mails/dlq BEZ nagłówka X-Api-Key w ~10 s dało 125x401 i ani jednego 429 (limit to 120/min, więc gdyby RateLimitFilter biegł pierwszy, żądania 121-125 dostałyby 429), a żądanie Z kluczem wykonane natychmiast potem dostało 200 — licznik rate-limitu nie policzył ani jednego anonimowego żądania. Czyli w realnym runtime (Quarkus RESTEasy Reactive, ta wersja, ten build) ApiKeyFilter wykonuje się pierwszy i komentarz 'AFTER the API key' mówi prawdę. Scenariusz DoS wymaga, żeby runtime wybrał odwrotną kolejność — nie wybiera. Zostaje teoretyczna kruchość na poziomie litery specyfikacji JAX-RS (kolejność przy równych priorytetach jest implementation-specific), czyli sugestia utwardzenia (@Priority(AUTHENTICATION) to dobra higiena), nie obserwowalna wada. Zgodnie z mandatem: nie zachowuje się źle i nie kłamie — obalone.

**Redrive rekordu bez pola "event" rzuca NPE i po cichu usuwa rekord z ledgera** (`shared/microservice-email/src/main/java/com/jrobertgardzinski/mail/boundary/DlqResource.java:41`)

Kod zachowuje się tak, jak opisano (DlqResource.java:41 `record.get("event").toString()` — NPE dla null; ParkedMails.take usuwa przed walidacją), ale scenariusz jest niemożliwy przy realnym przepływie. Jedynym producentem na topik mail-requests-dlq jest MailRequestsConsumer.park(), a ten ZAWSZE ustawia pole event: MailRequestsConsumer.java:135-136 `parked.set("event", mapper.readTree(payload))` — parkowane są wyłącznie payloady, które już przeszły readTree w process() (:91), więc serializacja się nie wywali. Jedyny realny wariant klucza 'unidentified-N' to zdarzenie będące poprawnym JSON-em bez pola 'id' — taki rekord MA pole 'event' i redrive działa. Rekord bez 'event' wymaga ręcznej publikacji obcego JSON-a na wewnętrzny topik przez kogoś z dostępem do brokera — a kto pisze do Kafki, może zrobić znacznie gorsze rzeczy niż 500 na endpointcie operatorskim. Defensywny catch w onParked (:45-47) łapie nie-JSON, co nie dowodzi, że parsowalny obcy JSON jest w modelu zagrożeń. Utwardzenie (path().isMissingNode()) można zrobić przy okazji, ale to nie jest wada zachowania systemu.


---
## Czego TEN przegląd nie sprawdził

**shared/microservice-email (Quarkus) + shared/email (email-domain, email-security)**

1) Nie zweryfikowałem w runtime obietnicy "startup fails fast" przy braku MAIL_SMTP_HOST (application.properties:17-21) — wymagałoby uruchomienia nowego kontenera, czego zabroniono; możliwe, że mailer po cichu spadłby na localhost. 2) Zabicia konsumenta przez unprocessed-record-max-age (znalezisko 1) nie zademonstrowałem na żywo — wymagałoby położenia Mailpita; dowód pochodzi ze źródeł smallrye 4.28.0 i konfiguracji, nie z obserwacji. 3) Faktycznej kolejności filtrów JAX-RS (znalezisko 4) nie zmierzyłem — dowód to brak gwarancji w spec, nie zaobserwowane odwrócenie. 4) Treści szablonów Qute (12 plików .txt/.html) przejrzałem tylko przez asercje testów; nie analizowałem escapingu HTML linka. 5) Strony producenta paktu (provider-testy w microservice-security) nie sprawdzałem — tylko to, że konsumencki pakt pokrywa 6 typów zdarzeń. 6) Biblioteki email-security (constraints, MX, disposable) przejrzałem pobieżnie — testy zielone (33), ale nie robiłem głębokiej analizy normalizacji ani tego, czy microservice-security w ogóle jej używa. 7) Nie mierzyłem zachowania pod wolumenem (bounded set 10k dedup, ledger 1k) ani wyścigu dwóch równoczesnych dostaw tego samego id — to udokumentowane trade-offy walking-skeleton, przyjąłem je za świadome decyzje. 8) /q/metrics jest publiczne na 8082 (zmierzone: 200 bez klucza) — nie zgłosiłem jako osobnego znaleziska, bo to wariant tematu z P12 priorytet 7 (actuator na publicznym prefiksie); odnotowuję tu dla uczciwości.

**shared/microservice-idp, shared/microservice-sms, shared/microservice-push (Python, stdlib-only) + ich styk z microservice-security (HttpSmsCodeChannel, OidcClient, FactorsController), compose i k8s tych trzech usług. P12 pkt 5.4: zerowe pokrycie — przejrzane od zera.**

1. Nie odtworzyłem na żywo pełnego tańca OAuth (start → formularz IdP → callback), bo wymagałby założenia konta i zapisu do bazy security. Znaleziska dotyczące styku (timeouty, walidacja target) są z lektury kodu, nie z runtime. Wstrzyknięcie CRLF odtworzyłem — ale samym curlem, nie w przeglądarce, więc konsekwencję „ciasteczko dla całego localhost” opieram na regule, że ciasteczka nie są scope'owane portem i że Chrome traktuje http://localhost jako secure context — nie zmierzyłem tego w przeglądarce.

2. Nie sprawdziłem, czy `TaskExecutors.BLOCKING` w tej wersji Micronauta to pula platformowa czy wątki wirtualne (na JDK 25 prawdopodobnie wirtualne). Dlatego świadomie NIE zgłosiłem „wyczerpanie puli wątków” jako skutku braku timeoutu — zgłosiłem tylko to, co pewne: żądanie nie kończy się nigdy.

3. Nie zbadałem, czy `alg`-confusion w OidcClient jest realnie wykorzystywalne. `validatedClaims` weryfikuje podpis TYLKO gdy `alg == HS256` (OidcClient.java:106-108), pozostałe algorytmy — w tym `none` — przechodzą na zaufaniu do TLS-a, którego w tym wdrożeniu nie ma (`SECURITY_OAUTH_PROVIDERS_GOOGLE_TOKEN_URL: http://idp:8091/token`, czysty HTTP). Warunkiem ataku jest kontrola nad odpowiedzią token endpointu (MITM w sieci compose albo przejęty dostawca), a `iss`/`aud`/`nonce` atakujący i tak zna. Nie doprowadziłem tego do dowodu, więc nie liczę tego jako znaleziska — ale uzasadnienie w javadocu („arrived over TLS”) jest w tym wdrożeniu nieprawdziwe i to warto rozstrzygnąć osobno.

4. Nie zmierzyłem tempa wycieku pamięci w idp (nie chciałem bombardować działającego kontenera dziesiątkami tysięcy żądań). Szacunek „~250 tys. żądań do limitu 128Mi” to arytmetyka na oko, nie pomiar.

5. Nie sprawdziłem, czy któryś produkt (paddock/formula) faktycznie woła sms/push — README obu usług mówi o „paddock's fan-out”, ale przejrzałem tylko konsumenta ze strony security. Jeśli paddock też je woła, powierzchnia z punktu o braku uwierzytelnienia jest większa, niż napisałem.

6. Nie oceniałem determinizmu `abs(hash((to, text))) % 10_000_000` jako id wiadomości (randomizacja PYTHONHASHSEED między restartami + kolizje w przestrzeni 10^7). Test `test_the_same_message_gets_the_same_stub_id` dowodzi determinizmu tylko w obrębie jednego procesu, więc nazwa jest nieco szersza niż dowód — uznałem to za zbyt miękkie na zgłoszenie.

7. Nie przeglądałem zależności — bo ich nie ma: wszystkie trzy usługi są na czystej bibliotece standardowej, jedyna „zależność” to obraz bazowy `python:3.12-slim` (kontener raportuje 3.12.13). Nie sprawdziłem, czy ten tag nie jest przypadkiem przypięty do starego digestu w lokalnym cache.

**shared/transactional-outbox, shared/infrastructure-spring-outbox, shared/infrastructure-micronaut-clock, shared/adjustable-clock (+ konfrontacja z użyciem w portal/microservice-memes i microservice-comments)**

(1) Prawdziwy PostgreSQL — testy biblioteki i moja analiza działają na H2 w MODE=PostgreSQL; semantyka kolumny `timestamp` bez strefy przy nie-UTC strefie JVM oraz plan zapytania DELETE ... IN (SELECT ... LIMIT) na PG nie zostały zmierzone. (2) Prawdziwy producent Kafki — wszystkie dowody na wątek callbacku i blokowanie opierają się na lekturze KafkaMemeDispatch i znanych właściwości kafka-clients/Hikari, nie na pomiarze; nie wywoływałem awarii DB na żywym stacku, żeby nie zmieniać stanu. (3) Nie uruchomiłem dwóch instancji relaya naraz — scenariusz duplikatów wywiedziony statycznie. (4) ScheduledOutboxRepublisher nie ma własnego testu i nie zweryfikowałem @Scheduled w działającym kontekście Springa (testy wołają pass()/runOnce() bezpośrednio). (5) Nie sprawdziłem, czy microservice-email (Quarkus) ma jakikolwiek odpowiednik adaptera zegara — istnieje tylko adapter micronautowy; Spring-owe serwisy portalu wstrzykują Clock bez współdzielonego adaptera. (6) Zawartości tabel meme_events_outbox/comment_events_outbox na żywym stacku nie odpytywałem psql-em.

**shared/microservice-security — cykl życia sesji i refresh tokenów; shared/offline-jwt — rdzeń weryfikatora JWT**

1) Nie odtworzyłem wyścigu rotacji na żywym stacku (wymagałoby zakładania nowych sesji i ryzykowałoby revokeFamily na cudzych rodzinach — opieram się na pomiarze z P12 pkt 5.9 plus pełnym mechanizmie z kodu). 2) Nie napisałem i nie uruchomiłem testu współbieżnego dowodzącego, że proponowany warunkowy UPDATE domyka wyścig — zachowanie EvalPlanQual pod READ COMMITTED znam z właściwości Postgresa, nie z eksperymentu na tej bazie. 3) Nie przejrzałem analogicznych wyścigów w Logout/RevokeAllSessions/SessionElevation ani całych ścieżek MFA, OAuth i resetu hasła — poza zakres zadania. 4) security-ui (frontend) nietknięte. 5) Nie sprawdziłem, czy CI ma inny layout checkoutu, w którym ścieżki @PactFolder jednak by się rozwiązały (workflowy CI były poza zakresem także w P12) — na tej maszynie pominięcie jest faktem zmierzonym. 6) JwksContractTest przyjąłem jako wystarczający dowód zgodności mint→verify formatu JWK; nie audytowałem samego testu. 7) Nie zbadałem konfiguracji kluczy security.jwt.* w compose/k8s (czy dev używa efemerycznych kluczy i jak często konsumenci trafiają w refetch po restarcie security). 8) Trwałość wierszy ROTATED oceniłem na dev-stacku; nie wiem, czy na innym środowisku istnieje zewnętrzne sprzątanie (pg_cron itp.) — w repo go nie ma.

**portal/microservice-comments i portal/microservice-user-collections: suity BDD (feature'y + step definitions), testy jednostkowe/integracyjne, migracje Flyway (odwracalność, utrata danych, pokrycie indeksami, determinizm), weryfikacja żywych baz przez psql (tylko odczyt)**

1) Odpaliłem `test`, nie `verify` — sprawdziłem jednak, że w obu modułach nie ma klas *IT.java ani konfiguracji failsafe, więc `verify` nie dołożyłby suit. 2) Nie zdemonstrowałem na żywo nakładania się stron przy remisie created_at (znalezisko 3 opiera się na braku gwarancji porządku SQL, nie na odtworzonej porażce; w danych dev remisów nie ma — 161 komentarzy klikanych ręcznie). 3) Nie przeczytałem linijka-po-linijce CascadeConsumerLoopTest/PurgeCommandsConsumerLoopTest ani pełnego PurgeCommandsConsumer.run() w collections — polityka retry tej pętli to potwierdzone S4 z P12, pominąłem świadomie. 4) Nie weryfikowałem paktów od strony offboardingu (N6/N7 z P12 pokrywają macierz kontraktów); potwierdziłem jedynie, że SilentlySkippedPactTest nie został pominięty przez assumption (skipped=0). 5) Migracje collections nie mają odpowiednika PostgresDialectTest — biegną w testach tylko na H2 w trybie PG; na żywym Postgresie sprawdziłem stan (schema_history, indeksy), ale nie zachowanie UNIQUE/IDENTITY pod współbieżnością. 6) Odwracalność migracji: żaden z serwisów nie ma migracji „undo\" — to konwencja całego repo (Flyway community), więc nie zgłaszam tego jako usterki; rollback wymaga odtworzenia bazy. 7) Nie oceniałem UI ani e2e/ compose-smoke, które konsumują te serwisy — poza zakresem obszaru.

**e2e (portal/e2e, memes-ui/e2e, security-ui/e2e), workflowy CI w portal/, shared/ i sub-repach, skrypty e2e-saga.sh / e2e-saga-outage.sh (P12 pkt 5.4)**

1) Ścieżka @outage (e2e-saga-outage.sh + participant-outage.feature) — nieuruchomiona, bo zatrzymuje kontener user-collections na żywym stacku; jej kroki i After-hooki czytałem tylko statycznie (wyglądają solidnie: guard E2E_OUTAGE_PROFILE, naprawa stacka w After). 2) Żadnej suity Mavenowej — reaktor przekracza budżet 5 min; skip pactów security wywnioskowałem z Files.isDirectory i układu katalogów, nie z wyjścia surefire. 3) Prywatność repozytoriów GitHub: sub-repo CI (np. offboarding, memes) checkoutują siostrzane repa bez REPO_PAT — jeśli któreś z nich jest prywatne, te joby też padają; nie umiałem tego rozstrzygnąć z dysku. 4) Ważność/zakres sekretu REPO_PAT i czy nightly cron e2e-saga.yml nie zostanie wyłączony przez GitHub po 60 dniach nieaktywności repo. 5) JVM-owe runnery specs/ security (cucumber w Mavenie) i collections-ui (nie ma e2e) — poza tym, że security-ui pokrywa tag @ui. 6) Brak jakiejkolwiek walidacji k8s/ w CI (żaden workflow nie robi kustomize build) — nie zgłosiłem, bo klaster jest niewdrożony i to raczej brak funkcji niż kłamliwa bramka. 7) Ślad po przeglądzie: moje przebiegi zostawiły na stacku dev konta saga-*/gallery-* i kilka 1x1 memów (suita sagi sprząta po sobie, przeglądarkowa nie), a e2e-saga.sh skryptowo wyczyścił globalnie authentication_blocks/rejected_authentications — to udokumentowany efekt uboczny samego skryptu.

**Ścieżka uploadu obrazu: microservice-memes (memes-image, memes-application, memes-infrastructure/MemeController + ConcurrencyGuardedImageOptimizer) oraz microservice-image (image-encoder). Rozstrzygnięcie punktu 5.8 z PLAN-P12 (semafor vs file.getBytes()) plus reszta ścieżki.**

1. NIE odtworzyłem OOM-u na żywym stacku — świadomie, zgodnie z poleceniem. Cała arytmetyka pamięciowa jest z kodu, z manifestów i z pomiarów w OSOBNYCH, lokalnych procesach (java -Xmx…, python3). Na compose ten problem i tak jest nieodtwarzalny, bo `docker-compose.yml` nie nakłada na memes ŻADNEGO limitu pamięci i zmierzona sterta to 8,39 GB; werdykty o OOM dotyczą wyłącznie `k8s/base/memes.yaml` i `k8s/base/image-encoder.yaml`, a klastra k3d nie sprawdzałem (kontener `k3d-portal-dev-server-0` chodzi, ale nie zaglądałem do niego).

2. NIE zmierzyłem, ile realnie zajmuje `readAllBytes()` w szczycie dla pliku dyskowego z Tomcata (czy jest jedna alokacja 10 MB, czy przejściowo ~2x). Arytmetykę oparłem na zachowawczym 10 MB zatrzymanym na żądanie; jeśli szczyt jest 2x, jest gorzej, nie lepiej.

3. NIE sprawdziłem, czy JVM w obrazie `eclipse-temurin:25-jre` zachowuje się przy dekodowaniu 16-bitowego PNG-a tak samo jak host — pomiary robiłem lokalnym Javą 25.0.2. Ta sama rodzina, ale to nie jest ten sam runtime co w kontenerze.

4. NIE uruchomiłem suit, które są w moim obszarze tylko pośrednio: `memes-ui` (npm test / Playwright), `e2e/`, `e2e-saga.sh`. Nie sprawdzałem też CI (`.github/workflows/ci.yml`) inaczej niż grepem za komendą testową.

5. `microservice-image` przetestowałem lokalnym Pillow 10.2.0, a `requirements.txt` przypina 12.3.0. Suita przeszła (30/30), ale to nie jest ta wersja, która chodzi w kontenerze; pomiary RSS mogą się o kilkanaście procent różnić na 12.3.0.

6. NIE zbadałem ścieżki uploadu pod kątem treści: żadnej weryfikacji, czy `accept="image/*"` w UI i whitelist dekoderów po stronie serwera zgadzają się z tym, co realnie wchodzi (np. SVG, animowany GIF — `rejectDeclaredDimensionsAbove` patrzy tylko na klatkę 0, czego nie drążyłem).

7. NIE sprawdziłem `JdbcMemeContentIndex.claim` ani `S3ObjectStore` pod kątem zachowania przy dużych blobach i przy równoległym claimie — czytałem tylko `PublishMeme`, który je woła. Dedup po SHA-256 zostawiam, bo to N20 z P12.

8. Znalezisko o `FileCacheImageInputStream` (każdy dekode dokłada plik tymczasowy w `java.io.tmpdir`) potwierdziłem pomiarem klasy strumienia, ale NIE zmierzyłem realnego zużycia dysku pod obciążeniem ani nie sprawdziłem, czy Tomcat i ImageIO na pewno sprzątają te pliki na każdej ścieżce błędu.

9. Nie zgłaszam braku `try/catch` wokół `upload` w `memes-ui/src/App.tsx:191-197` i `void upload(file)` w linii 242 jako osobnego znaleziska — to ta sama klasa co S19 z P12, tylko w miejscu, którego S19 nie wymienia. Jeśli będziecie zamykać S19, dopiszcie `upload` do listy.
