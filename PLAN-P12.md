# PLAN P12 — po paczce 11

Materiał: sześć niezależnych przeglądów (audyt całości, głębia memes, nowy kod paczki 11, oba UI,
gotowość k3s, duplikacja i kontrakty), każdy przepuszczony przez adwersaryjną weryfikację.
Potwierdzono **65 znalezisk** (po scaleniu duplikatów: **57 pozycji**), obalono **23** — te ostatnie
są w sekcji 6, bo weryfikator myli się co najmniej równie często jak znalazca (dowód w nagłówku tamtej sekcji).

Stan bazowy: stack chodzi na compose, 26 usług healthy. Klaster k3s **nie był wdrożony** — wszystkie
znaleziska z `k8s/` pochodzą z lektury manifestów. Żadna suita testów nie została uruchomiona.

---

## 1. Stan po paczce 11

**Rdzeń domenowy jest w dobrym stanie i to widać.** Paczki 9–11 zamknęły rzeczy, które naprawdę bolały:
choreografowana kaskada usuwania mema działa i ma pakty, outbox wyjechał do wspólnej biblioteki,
memy-widma zniknęły (przeleciano 100 memów przez `/meta` i `/thumbnail` — 0 odpowiedzi innych niż 200),
uczestnicy sagi mają budżet retry z zegarem ściennym zamiast ślepego `DefaultErrorHandlera`, oba UI mają
wreszcie model błędu (`request()`/`HttpError`/`renewAccessToken`, `ErrorBoundary`), a compose ma politykę
restartu i healthchecki, które faktycznie pytają aplikację. Z 59 znalezisk starego audytu 26 jest
domkniętych — sprawdzonych w kodzie, nie w komunikatach commitów. Kod jest gęsto komentowany i te
komentarze w większości mówią prawdę; kilka razy w tym przeglądzie to właśnie javadoc pozwolił odrzucić
fałszywe znalezisko, bo kompromis był nazwany i uzasadniony.

**Słabość przesunęła się z domeny na krawędź.** Najgorsze, co dziś jest w tym systemie, nie leży w sadze
ani w kaskadzie, tylko w trzech miejscach styku ze światem. Pierwsze: uwierzytelnianie —
`ClientIpResolver` kluczuje brute-force po adresie połączenia, a za Traefikiem to jeden adres dla całego
internetu, więc trzy błędne hasła zamykają logowanie wszystkim (jedyny KRYTYCZNY w tym przeglądzie
i naprawa na jedną zmienną środowiskową). Drugie: cykl życia sesji — „wyloguj" w obu UI robi wyłącznie
`setToken(null)`, cookie refresh żyje dalej pełną dobę, a `LogoutController` po stronie security istnieje
i nikt go nie woła. Trzecie: warstwa transportowa w UI — `AuthPanel` melduje użytkownikowi
„Wrong e-mail or password" na każde 5xx z security, więc awaria backendu wysyła ludzi do resetu
poprawnego hasła.

**Drugi motyw to bliźniaki, które się rozjeżdżają.** ~2750 linii warstwy uczestnika sagi jest
utrzymywanych w dwóch kopiach (memes i comments), różniących się głównie javadociem. Koszt nie jest
hipotetyczny: sanityzacja nagłówka `X-Correlation-Id` trafiła do jednej kopii filtra i nie trafiła do
trzech pozostałych, a próg MFA trafił do jednej kopii bramki HTTP i nie trafił do drugiej. To dwie
poprawki bezpieczeństwa, każda wykonana dokładnie w połowie miejsc. Do tego dochodzi rodzina
`delete+insert` udająca upsert w trzech adapterach memes — jedna z nich (głosy) produkuje na żywym
stacku 500-tkę z adresem e-mail głosującego w logu, czyli w Loki, gdzie retencji nikt nie kontroluje
i gdzie RODO-we kasowanie konta już nie sięgnie. Reszta znalezisk to dług projektowy i ergonomia
operatora: brak indeksów pod realnie wykonywane zapytania, metryki nazwane „alertem" bez reguły alertu,
dokumentacja rozjeżdżająca się z kodem.

---

## 2. Priorytety

Kolejność wynika z ryzyka razy prawdopodobieństwo. Pierwsze siedem pozycji to razem mniej niż dwa dni
pracy i zdejmuje wszystko, co realnie wybuchnie przy wystawieniu na świat.

### 1. Zaufane proxy w security — inaczej pierwszy użytkownik z literówką wyłącza logowanie wszystkim
**Co:** ustawić `SECURITY_TRUSTED_PROXIES` na zakres podów Traefika i dopilnować, żeby XFF niósł
prawdziwy adres (`externalTrafficPolicy: Local` + forwarded headers).
**Dlaczego teraz:** to jedyny KRYTYCZNY. `Source` ma `equals` wyłącznie po `IpAddress`, blok zakłada się
po 3 nieudanych próbach na 3–10 minut, a `bruteForceGuard` biegnie pierwszy i short-circuituje — więc
nikt nie „odblokuje" tego poprawnym hasłem. Skrypt 3 żądań co 3 minuty trzyma portal zamknięty bez końca.
Tego nie ma na liście przedpublikacyjnej w `go-live-2026.md`.
**Koszt:** drobiazg (jedna zmienna + jedna linia w Service).
**Pliki:** `shared/microservice-security/.../ClientIpResolver.java:36`, `k8s/base/security.yaml:41-79`.

### 2. „Wyloguj" musi kończyć sesję po stronie serwera
**Co:** `POST /logout` z `credentials:'include'` w `onLogout` obu UI i w ścieżce `session.expired()`.
**Dlaczego teraz:** endpoint istnieje, unieważnia sesję i czyści cookie — po prostu nikt go nie woła.
Zmierzone: po „wylogowaniu" z UI `POST /refresh` z tym samym cookie oddaje 200 i świeży token; po
wywołaniu `/logout` oddaje 401. Na współdzielonym komputerze to jest przejęcie konta jednym fetchem.
**Koszt:** drobiazg (4 linie w dwóch UI).
**Pliki:** `memes-ui/src/App.tsx:251`, `memes-ui/src/api.ts`, `collections-ui/src/App.tsx:31`.

### 3. Jeden handler w `WebErrorHandler` i trzy prawdziwe upserty w memes
**Co:** (a) `ON CONFLICT DO UPDATE` w `JdbcVoteRepository.cast`, `JdbcContentFlags.setNsfw`
i `JdbcPurgePolicyOverride.set`; (b) handler na `DataAccessException` logujący **typ i SQL, nigdy
`getMessage()`** Postgresa; (c) rozpoznanie `SizeLimitExceededException` w łańcuchu przyczyn
`IllegalStateException` → 413 `FILE_TOO_LARGE` na WARN zamiast 500 na ERROR.
**Dlaczego teraz:** jedna zmiana zamyka cztery znaleziska naraz, w tym jedyny potwierdzony wyciek PII
(adres e-mail głosującego w linii `Detail: Key (meme_id, voter)=(…, ktos@example.com)`, widoczny w Loki)
i jedyne miejsce, w którym serwis bierze na siebie winę użytkownika (12 MB zdjęcia = 500 + stacktrace
+ fałszywe 5xx w alertach). Wszystko odtworzone na żywym stacku.
**Koszt:** drobiazg.
**Pliki:** `JdbcVoteRepository.java:31-36`, `JdbcContentFlags.java:23-29`, `JdbcPurgePolicyOverride.java:36-46`,
`WebErrorHandler.java:74-82`.

### 4. Front nie może kłamać ani milczeć: `AuthPanel` + odrzucone obietnice
**Co:** rozdzielić gałąź `else` w `signIn` (401 → złe hasło, reszta → „security odpowiedziało {status}"),
owinąć trzy gołe `await fetch` w try/catch, dodać `.catch(() => ({}))` przy `r.json()` w gałęzi błędu,
przetypować `guard` w `MemeDialog` na `() => void | Promise<void>` z jednym `.catch`, dołożyć `catch`
do `toggleFavourite` i `try/finally` do `DeleteAccountDialog`.
**Dlaczego teraz:** część „5xx → Wrong e-mail or password" **nie wymaga żadnej awarii sieci** — wystarczy
jeden 500 z security, żeby użytkownik poszedł resetować poprawne hasło. Reszta to cisza w UI dokładnie
wtedy, gdy coś nie działa. Wzorzec do skopiowania jest w tym samym repo (`collections-ui/src/App.tsx:60`).
**Koszt:** drobiazg.
**Pliki:** `AuthPanel.tsx:115,137,163-165,169,184`, `MemeDialog.tsx:167`, `App.tsx:173-188`,
`DeleteAccountDialog.tsx:52-79`, `AdminPanel.tsx:31,86`.

### 5. Paczka manifestów k8s: sondy, strategia, pamięć
**Co:** liveness i startup security na `/health/liveness` (endpoint istnieje i odpowiada 200), readiness
zostaje na `/health`; `strategy: {type: Recreate}` w `offboarding.yaml`; limit security do ~1Gi plus
`JDK_JAVA_OPTIONS: -XX:MaxRAMPercentage=45`.
**Dlaczego teraz:** trzy jednolinijkowce, każdy zamykający scenariusz, w którym rutynowa operacja psuje
system. Liveness na agregacie (`JdbcIndicator` + `KafkaHealthIndicator` bez `@Liveness`) restartuje
security w kółko, gdy padnie jego Postgres — dokładnie ten błąd, który `k8s/README.md` opisuje jako
naprawiony u dwóch Helidonów. Brak `Recreate` przy `replicas: 1` daje dwa sweepery przez do ~3 minut
startupu, a sweeper nie ma ani `SKIP LOCKED`, ani leader election. 768Mi bez ustawionego heapu przy
Argon2 biorącym 64 MiB **natywnie** na każde równoległe hasło to OOMKill przy ośmiu jednoczesnych
logowaniach — analiza, którą autor przeprowadził dla `memes.yaml` i pominął dla security.
**Koszt:** drobiazg.
**Pliki:** `k8s/base/security.yaml:80-102`, `k8s/base/offboarding.yaml:31`.

### 6. Domknąć dwie połowiczne poprawki bezpieczeństwa w bliźniakach
**Co:** przenieść `sanitize()` z `comments/CorrelationIdFilter` do memes, collections i security;
dopisać trzy linie progu MFA w `comments/HttpSecurityAuthenticationGate`; zamienić `comments.ui-origin`
na listę.
**Dlaczego teraz:** to nie są nowe funkcje, tylko poprawki, które ktoś już napisał i wdrożył w jednym
z dwóch–czterech miejsc. Niesanityzowany nagłówek jest odtworzony na żywo (fałszywy fragment linii loga
plus 301-znakowe `cid` w każdej linii żądania, bez uwierzytelnienia). Brak progu MFA jest dziś uśpiony,
bo oba wdrożenia pinują `SECURITY_VERIFY=offline` — ale bramka introspekcyjna jest tą **domyślną**,
więc budzi się przy pierwszym `spring-boot:run`. Jednoorginowy CORS w comments położy całą sekcję
komentarzy pod ingressem host-per-service.
**Koszt:** drobiazg (kopiuj-wklej) — i naturalne wejście do decyzji z sekcji 4.
**Pliki:** `memes/CorrelationIdFilter.java:35`, `collections/CorrelationFilter.java:28`,
`shared/.../CorrelationIdFilter.java:29`, `comments/HttpSecurityAuthenticationGate.java:54`,
`CommentsConfig.java:129`.

### 7. Actuator: osobny port zamiast publicznego prefiksu, i lampka, którą widać
**Co:** `management.server.port=9083/9085` (ani ingress, ani compose tego portu nie wystawiają) plus
`management.endpoint.health.show-details=always` w memes i comments.
**Dlaczego teraz:** zmierzone anonimowo — `/actuator/prometheus` na 8083 i 8085 oddaje 200 z wersją JVM,
wersją Kafki, `hikaricp_connections_max` i szablonami URI (czyli mapą API razem z `/admin/purge-policy`),
a ingress kieruje na oba serwisy cały prefiks `/`. Druga połowa jest lustrzana: `SagaListenersHealth`
starannie składa komunikaty typu „memes-purge-commands: no completed poll for 173s (tolerance 150s)",
których `show-details=NEVER` **nigdy nie renderuje** — operator dostaje gołe `{"status":"DOWN"}`.
Ekspozycja jest dziś za duża, a diagnostyka za mała, i obie naprawy to po jednej linii.
**Koszt:** drobiazg.
**Pliki:** `memes/application.properties:105,118`, `comments/application.properties:78`,
`k8s/base/ingress.yaml:18-27`, `SagaListenersHealth.java:146-169`.

### 8. Trzy miejsca, które przewracają się na skali albo na absurdalnym wejściu
**Co:** migracja V7 w memes (`memes(published_at desc, id desc)`, `memes(author)`, `meme_votes(voter)`,
`content_index(meme_id)`, `meme_tags(tag)`); `long offset` z sufitem w `CommentController` plus minimalny
`@RestControllerAdvice`; `LIMIT/OFFSET` i cap wierszy na `(user_email, collection)` w collections.
**Dlaczego teraz:** dwa z trzech to nie skala, tylko dzisiejszy 500 od anonima —
`GET /memes/abc/comments?page=25000000&size=100` zwraca 500 z gołym bodym Springa (zmierzone), zasypuje
`Http5xxBurst` i maskuje prawdziwe awarie; bliźniacza poprawka w `MemeController` istnieje i ma komentarz
o przepełnieniu. Indeksy to koszt czysto wydajnościowy przy 112 memach, ale czystka RODO biegnie w jednej
transakcji na wątku konsumenta i to ona zapłaci pierwsza.
**Koszt:** pół dnia łącznie.
**Pliki:** `V1__memes.sql` (nowa V7), `CommentController.java:97-98`, `JdbcCollectionStore.java:80-97`,
`CollectionsApi.java:53,88-118`.

### 9. RODO: trzy miejsca, w których obietnica nie pokrywa się z zachowaniem
**Co:** (a) `OrphanedBlobMigration` ma rozróżniać sierotę od mema-widma — `LEFT JOIN memes`, klucz bez
wiersza kasować zamiast wgrywać do żywego bucketu; (b) kreator usuwania konta ma wysyłać to, co obiecuje
etykieta („delete my memes"), albo przestać to obiecywać i pokazywać efektywną politykę
z `GET /admin/purge-policy`; (c) `RESET_LINK_BASE` musi wskazywać na stronę, która istnieje — dziś
prowadzi do kontrolera POST-only (`GET /reset-password?token=x` → 405, zmierzone).
**Dlaczego teraz:** (a) wykonuje się **jednorazowo przy pierwszym starcie nowego kodu**, czyli w momencie,
gdy zalegają wszystkie historyczne sieroty — po tym starcie zdjęcie osoby wymazanej siedzi w produkcyjnym
buckecie, a wiersz, który jeszcze o nim wiedział, właśnie zniknął. (b) to pisemna obietnica przy operacji
nieodwracalnej, którą pokrętło admina może cicho unieważnić. (c) psuje reset hasła w **każdym**
środowisku, nie tylko w k8s.
**Koszt:** pół dnia.
**Pliki:** `OrphanedBlobMigration.java:74`, `DeleteAccountDialog.tsx:36-45,88-91`,
`k8s/base/security.yaml:56`, `shared/docker-compose.identity.yml:62`.

### 10. Zamknąć rozjazd polityk ponawiania i przywrócić widoczność encodera
**Co:** dać `collections/PurgeCommandsConsumer` ten sam wall-clock deadline co Springom (90 s), po nim
zrzut + licznik + commit; poprawić javadoc, który uzasadnia wieczne ponawianie stanem sprzed wydzielenia
orkiestratora. Osobno: dopisać `image-encoder` do `memes-up.sh`, dołożyć WARN przy pierwszym nieudanym
kontakcie z encoderem i naprawić `log_message` w `server.py`.
**Dlaczego teraz:** trzej uczestnicy jednej sagi mają dwie sprzeczne polityki, a `SagaRetryBudget` własnym
javadociem opisuje szkodę, którą collections nadal robi („deleting the content of an account the saga has
already restored to its owner"). Encoder z kolei znika po cichu — `memes-up.sh` go nie startuje, mimo że
nagłówek twierdzi inaczej, a `HttpImageEncoder` nie ma ani jednego `Logger`, więc degradacja do PNG jest
niewykrywalna.
**Koszt:** pół dnia.
**Pliki:** `collections/PurgeCommandsConsumer.java:330-338,36-44`, `memes-up.sh:26-28`,
`HttpImageEncoder.java:50-58`, `microservice-image/server.py:226-227`.

---

## 3. Znaleziska wg wagi

Numeracja: K/W/S/N + kolejny numer. Pozycje scalone oznaczone `(scalone: N pozycji)`.

### KRYTYCZNY

**K1. Brute-force i wszystkie throttle klucze po adresie połączenia — za proxy to jeden adres dla całego świata**
— `shared/microservice-security/.../ClientIpResolver.java:36`
*Objaw:* `resolve()` honoruje `X-Forwarded-For` tylko gdy remote address jest na liście
`security.trusted-proxies`; lista jest pusta domyślnie i nie jest ustawiana ani w compose, ani
w `k8s/base/security.yaml:41-79` (grep po obu repo: tylko javadoc i sam `@Value`). `Source.java:16-25`
ma `equals`/`hashCode` wyłącznie po `IpAddress`.
*Scenariusz:* trzy nieudane logowania (`MaxFailures.DEFAULT=3` w oknie 15 min) zakładają blok na 3–10 min
na IP Traefika, czyli na wszystkich. `Authentication.java:50-52` sprawdza guard **pierwszy** i przy
`Blocked` odmawia bez sprawdzania czegokolwiek, a czyszczenie liczników biegnie dopiero po udanym haśle —
więc nikt nie zdejmie bloku poprawnym logowaniem. Skrypt 3 żądań co 3 minuty trzyma logowanie zamknięte
bez końca. Ten sam mechanizm globalizuje throttle rejestracji (100/15 min dla całego internetu), resetu
hasła i wysyłki weryfikacji.
*Naprawa:* `SECURITY_TRUSTED_PROXIES` = CIDR podów Traefika + `externalTrafficPolicy: Local` i forwarded
headers. Do czasu naprawy nie wystawiać.

### WYSOKI

**W1. „Wyloguj" nie kończy sesji — żaden z dwóch UI nie woła `POST /logout`**
— `memes-ui/src/App.tsx:251`, `collections-ui/src/App.tsx:31`
*Objaw:* kliknięcie „sign out" wykonuje wyłącznie `setToken(null)`. HttpOnly cookie `refresh_token`
zostaje nietknięte i sesja serwerowa żyje pełne `refreshTokenValidityInHours` (~doba).
`LogoutController` w security istnieje, woła `logout.execute(...)` i zwraca `refreshCookies.clear()` —
grep po obu `src/`: zero wywołań.
*Scenariusz:* zmierzone na żywym stacku — po symulacji wylogowania z UI (czyli po niczym)
`POST /refresh` z tym samym cookie → **200 z ważnym access tokenem**; po `POST /logout` → **401**.
Na współdzielonym komputerze następna osoba wywołuje jeden `fetch('/refresh',{credentials:'include'})`
i ma token poprzedniego użytkownika z jego `sub` i rolami.
*Naprawa:* `logout()` w `api.ts` wołane w `onLogout` i w `session.expired()`; endpoint jest idempotentny,
więc porażka sieci nie może blokować czyszczenia stanu lokalnego.
*Uwaga:* samo UI sesji nie wskrzesza (bez tokenu `authHeader` nie dokłada nagłówka) — to dziura w cyklu
życia sesji, nie automatyczne przejęcie konta.

**W2. Upsert głosu przez DELETE+INSERT: równoległe głosy dają 500, a Postgres wynosi e-mail do Loki**
— `microservice-memes/.../JdbcVoteRepository.java:31-36`
*Objaw:* `cast()` = `retract()` (DELETE) + INSERT. Metoda **ma** `@Transactional`, ale pod READ COMMITTED
to nie pomaga: dwie transakcje po DELETE (0 wierszy) kolidują na PK `meme_votes_pkey`. `WebErrorHandler`
nie ma handlera na `DataAccessException`, więc leci gołe 500 Springa, a Tomcat loguje komunikat Postgresa
razem z `Detail: Key (meme_id, voter)=(…, adres@example.com) already exists.`
*Scenariusz:* odtworzone — 6 równoległych `POST /memes/{id}/votes` tym samym tokenem: 4×200, 2×500,
4 linie z pełnym adresem w logu kontenera, widoczne w Loki. `VoteButtons` w `MemeDialog.tsx:36-51` nie
blokuje przycisku na czas żądania. Serwis, który wszędzie indziej skrupulatnie trzyma PII poza logami
(`PurgeConfirmations.java:40-41`: „The logs, which have no retention anyone controls, still never see it"),
wynosi ją tu jednym wyścigiem.
*Naprawa:* `INSERT … ON CONFLICT (meme_id, voter) DO UPDATE SET direction = EXCLUDED.direction`
(H2 w trybie PostgreSQL to rozumie), handler na `DataAccessException` logujący typ i SQL **bez**
`getMessage()`, blokada przycisku in-flight w UI.

**W3. `AuthPanel` melduje 5xx jako „Wrong e-mail or password" i nie obsługuje awarii sieci**
— `memes-ui/src/AuthPanel.tsx:115,137,163-165,169,184`
*Objaw:* gałąź `else` w `signIn` (163-165) zbiera wszystko poza 200/202/403/429 i pokazuje
„Wrong e-mail or password." — czyli 500, 502 i 504 są przedstawiane jako zła treść hasła. Osobno:
`signIn`, `signUp` i `submitFactor` to gołe `await fetch`, a `submit` ma `try/finally` gaszące tylko
`busy`; `void submit()` (253) nie łapie odrzucenia. `await r.json()` w gałęzi błędu (184) wywraca się
na ciele nie-JSON (typowy 502 od proxy).
*Scenariusz:* część z 5xx nie wymaga żadnej awarii transportu — jeden 500 z security i użytkownik idzie
resetować hasło, którego nikt nie zepsuł. Przy zerwanym połączeniu spinner gaśnie i ekran nie zmienia się
o milimetr. To samo miejsce w `collections-ui` mówi uczciwie „Security service unreachable.".
*Naprawa:* rozdzielić `401` od reszty statusów, owinąć trzy `fetch` w try/catch, `.catch(() => ({}))`
przy `r.json()`, `.catch` przy `void submit()`. Efekt weryfikacji e-maila (72-82) też nie ma `.catch`.

**W4. `livenessProbe` security celuje w `/health`, które agreguje Postgresa i Kafkę**
— `k8s/base/security.yaml:80-96`
*Objaw:* startup, readiness i liveness — wszystkie trzy na `/health`, liveness 4×15 s. Potwierdzone
w źródłach frameworka: `micronaut-management` `JdbcIndicator` to `@Singleton @Requires(beans = DataSource)`
**bez** `@Liveness`/`@Readiness`, `micronaut-kafka` `KafkaHealthIndicator` tak samo; `HealthEndpoint`
agreguje wszystkie wskaźniki, a `LIVENESS` bierze wyłącznie te z `@Liveness`.
*Scenariusz:* `security-postgres` dostaje reschedule (Recreate + RWO PVC = kilkadziesiąt sekund
niedostępności). Po 60 s kubelet zabija poda security. Nowy pod startuje przy nadal niedostępnej bazie,
startup też jest na `/health` → 300 s i CrashLoopBackOff. Restart niczego nie naprawia — to dokładnie ten
scenariusz, który `k8s/README.md` opisuje jako naprawiony w offboarding i user-collections, a security
wymieniono tam jednym zdaniem „Micronaut: /health" bez refleksji.
*Naprawa:* liveness i startup na `/health/liveness` (istnieje, odpowiada 200), readiness zostaje na `/health`.

### SREDNI

**S1. `/actuator/prometheus` anonimowy i osiągalny z zewnątrz w memes i comments** *(scalone: 2 pozycje)*
— `memes/application.properties:105`, `comments/application.properties:78`, `k8s/base/ingress.yaml:18-27`
*Objaw:* `management.endpoints.web.exposure.include=health,prometheus`, a `RequireSignInFilter.java:36-40`
bramkuje wyłącznie prefiksy `/memes` i `/admin` — `/actuator` przechodzi `chain.doFilter` bez kontroli.
Ingress kieruje na oba hosty cały prefiks `/`, compose publikuje 8083 i 8085 na hosta.
*Scenariusz:* zmierzone — oba `/actuator/prometheus` → 200 anonimowo, 1014 linii, w tym `jvm_info` z wersją
JDK, `kafka_app_info` z wersją brokera, `hikaricp_connections_max{pool="HikariPool-1"} 10.0` i szablony URI
z `http.server.requests` (czyli mapa API razem z `/admin/purge-policy`). Rekonesans plus kanał obserwacji
skuteczności ataku w czasie rzeczywistym. `/env`, `/heapdump`, `/loggers` są wyłączone, więc sekretów tam
nie ma — to information disclosure, nie wyciek danych.
*Naprawa:* `management.server.port=9083/9085` (port nie wystawiany ani przez ingress, ani przez compose)
albo middleware Traefika blokujący `/actuator` z zewnątrz; probe'y kubeletu idą do poda bezpośrednio.

**S2. `CorrelationIdFilter`: sanityzacja nagłówka jest w jednej z czterech kopii**
— `memes/CorrelationIdFilter.java:35`, `collections/CorrelationFilter.java:28-32`,
`shared/microservice-security/.../CorrelationIdFilter.java:29-35`
*Objaw:* tylko kopia w comments (35-60) ma `sanitize()` z białą listą `[A-Za-z0-9_-]` i limitem 64 znaków
oraz komentarz „the inbound header is attacker-controlled". Trzy pozostałe biorą `request.getHeader(HEADER)`
surowo i wkładają do MDC, do wzorca `[cid=…]`, do access-logu i do nagłówka odpowiedzi.
*Scenariusz:* odtworzone — `curl -H 'X-Correlation-Id: evil]  INFO  fake.Logger - injected'` produkuje
w logu memes linię `INFO  [cid=evil]  INFO  fake.Logger - injected] … - cid=evil]  INFO  fake.Logger -
injected GET /memes/x/meta`, czyli sfałszowany fragment wpisu. 300-znakowy nagłówek daje 300-znakowe `cid`
w każdej linii tego żądania i 301 znaków w odpowiedzi — darmowy wzmacniacz zapisu bez uwierzytelnienia.
Comments na to samo oddaje `evilINFOfakeLogger-injected` obcięte do 64.
*Ograniczenie:* Tomcat zwija składanie nagłówka, więc CR/LF nie przechodzi — nie da się sfabrykować
osobnej linii ani rozszczepić odpowiedzi.
*Naprawa:* jedna implementacja `sanitize()` w `shared` (obok `offline-jwt`), użyta we wszystkich czterech;
minimum to skopiowanie 11 linii. Sanityzować **przed** `MDC.put` i przed `setHeader`.

**S3. comments gubi próg MFA w bramce introspekcyjnej, która jest tam bramką domyślną**
— `comments/HttpSecurityAuthenticationGate.java:51-54`
*Objaw:* memes czyta `mfaCompliant` z `GET /me` i buduje `new Caller(email, Caller.withMfaFloor(roles,
mfaCompliant))` (59-60); comments czyta te same `roles`, ignoruje `mfaCompliant` i buduje
`new Caller(email, roles)` — mimo że `comments/Caller.java:23-29` **ma** `withMfaFloor` i używa go
w bramce offline. Obie bramki mają `@ConditionalOnProperty(… matchIfMissing = true)`, a `security.verify`
nie występuje w `comments/src/main/resources/application.properties`.
*Scenariusz:* konto MODERATOR bez dopiętego drugiego składnika. W memes jego token daje uprawnienia
zwykłego USER-a. W comments uruchomionym bez `SECURITY_VERIFY=offline` (lokalny `spring-boot:run`,
zapomniana zmienna, albo świadome przełączenie na introspekcję po revocation-awareness, którą README
reklamuje jako wybór) to samo konto przechodzi `roles.contains("MODERATOR")` w `CommentController:138,175`
i może ukryć oraz usunąć cudzy komentarz.
*Ograniczenie:* oba wystawione wdrożenia pinują `offline` (`docker-compose.yml:151`,
`k8s/base/comments.yaml:40`), więc dziura jest dziś uśpiona.
*Naprawa:* trzy linie + odpowiednik memesowego `CallerMfaFloorTest`, którego comments nie ma — dlatego
rozjazd przeszedł niezauważony.

**S4. Trzej uczestnicy jednej sagi, dwie sprzeczne polityki ponawiania**
— `collections/PurgeCommandsConsumer.java:330-338`
*Objaw:* w pętli collections każdy wyjątek daje `rewindNeeded = true`, backoff (cap 30 s) i ponowienie —
bez deadline'u, licznika i DLQ. Springowi uczestnicy mają twardy budżet 90 s (`SagaRetryBudget.BUDGET`),
a orkiestrator kapituluje ok. 165 s (timeout 120 s, sweep co 15 s, 3 ponowienia). `handleRecord` nie
sprawdza świeżości komendy — nie ma znacznika czasu ani niczego, po czym można by odrzucić komendę wygasłą.
*Scenariusz:* Postgres collections leży 5 minut w trakcie usuwania konta. Saga kompensuje ok. 165 s,
security oddaje konto właścicielowi i wysyła mail „usunięcie NIE powiodło się". W 5. minucie baza wraca,
niezacommitowana komenda jest dostarczona ponownie i `PurgeUserItems` kasuje wszystkie ulubione
**żyjącego, przywróconego** konta; potwierdzenie trafia do nieistniejącej sagi i jest odrzucone jako stray.
Dopóki rekord nie przejdzie, jego partycja blokuje purge każdego innego użytkownika.
*Kontrapunkt jest w tym samym repo:* `SagaRetryBudget` javadoc — „A participant that retried without end
would purge whenever its store came back … deleting the content of an account the saga has already
restored to its owner".
*Naprawa:* wall-clock 90 s, po nim zrzut + `collections_kafka_records_dropped_total` + commit; poprawić
javadoc klasy (36-44), który uzasadnia wieczne ponawianie stanem sprzed wydzielenia orkiestratora.

**S5. security: limit 768Mi bez ustawionego heapu, a Argon2 bierze 64 MiB pamięci natywnej na hasło**
— `k8s/base/security.yaml:97-102`
*Objaw:* brak `JDK_JAVA_OPTIONS` (memes jako jedyny ma `-XX:MaxRAMPercentage=70`), więc domyślne 25%
z 768Mi to ~192Mi sterty, a reszta limitu to metaspace, wątki i alokacje natywne. `BeanFactory.java:61`
tworzy `new Argon2HashAlgorithm()` → `MemLimitInKB.DEFAULT = 65_536` KB, a `argon2-jvm` (JNA + natywna
libargon2) alokuje **poza stertą, ale w cgroupie**. Nic nie ogranicza równoległości hashowania —
brute-force liczy tylko nieudane próby.
*Scenariusz:* ośmiu ludzi loguje się naraz (albo `ab -c 8` poprawnymi danymi): 8 × 64 MiB ponad bazowe
zużycie → OOMKill, czyli restart tożsamości całego portalu.
*Naprawa:* limit do ~1Gi + `MaxRAMPercentage=45`, docelowo semafor na hashowanie (wzorzec
`MEMES_DECODE_CONCURRENCY` już jest w memes). Autor przeprowadził dokładnie tę analizę dla `memes.yaml`
(„the old 768Mi limit would OOM under exactly the load the semaphore admits") i pominął ją tutaj.
*Uwaga:* teza „restart unieważnia wszystkie tokeny" jest fałszywa — javadoc `JwtAccessTokenMint` mówi,
że świeża para kluczy psuje tylko weryfikację offline, ścieżka introspekcji jest DB-backed.

**S6. Deployment offboardingu bez `strategy: Recreate` — rolling update uruchamia drugi sweeper**
— `k8s/base/offboarding.yaml:31`
*Objaw:* `replicas: 1` i brak `spec.strategy` (grep po `k8s/base/*.yaml`: 7 trafień — pięć Postgresów,
minio, kafka; offboardingu tam nie ma), więc obowiązuje RollingUpdate. `startupProbe` to 5 s × 36, czyli
okno współistnienia do ~3 minut.
*Scenariusz:* rutynowy `kubectl rollout restart`. `KafkaLoop.sweep` to pętla na `Thread.sleep`,
a `JdbcSagaStore.sweepOverdue` robi zwykły `SELECT … WHERE state='STARTED'` bez `FOR UPDATE` i bez
dzierżawy — dwa procesy wybiorą te same sagi i oba obciążą licznik przez `retryDelivered`. Budżet 3 retry
wypala się w ~22 s zamiast ~45 s i użytkownik dostaje `PORTAL_PURGE_FAILED` przy zdrowym brokerze
i zdrowej bazie. Podwójnej kompensacji nie będzie (warunek `AND state = STARTED`).
*Naprawa:* `spec.strategy: {type: Recreate}` z komentarzem, że sweeper nie ma `SKIP LOCKED` ani leader
election, więc dwie instancje są niedopuszczalne nawet przez 30 sekund.

**S7. `OrphanedBlobMigration` przenosi do żywego bucketu zdjęcia osób, które już wykonały usunięcie konta**
— `OrphanedBlobMigration.java:74`
*Objaw:* migracja listuje `SELECT object_key FROM meme_blobs` bez żadnego złączenia z `memes` (w całym
pliku nie ma odwołania do tej tabeli) i dla **każdego** klucza robi `active.put(key, data)`, a potem
`DELETE FROM meme_blobs`. Klucz bez wiersza mema i klucz mema-widma są traktowane tak samo, choć to dwa
różne fakty.
*Scenariusz:* konto założone przy `MEMES_BLOB_STORE=db`, deployment przełączony na `s3`. Purge kasuje
wiersz i woła `active.delete(key)` na S3 — no-op, bo bajtów tam nigdy nie było; wiersz w `meme_blobs`
zostaje (dokładnie ta dziura, którą klasa cytuje we własnym javadocu). Przy najbliższym starcie migracja
wgrywa te bajty do produkcyjnego bucketu i kasuje jedyny wiersz, który o nich wiedział.
`OrphanedBlobMigrationTest` nigdy nie zakłada tabeli `memes`, więc żaden test tego rozróżnienia nie pilnuje.
*Ograniczenie:* zmigrowana sierota nie jest serwowalna (`ServeMeme` sprawdza istnienie wiersza) — to
pominięta higiena RODO, nie nowy wyciek. Ale zdarzenie jest **jednorazowe i nieodwracalne**.
*Naprawa:* `LEFT JOIN memes m ON m.id = split_part(b.object_key, '.', 1)`; klucz z wierszem migrować,
klucz bez wiersza kasować i nic nie wgrywać, plus osobny licznik w logu podsumowującym.

**S8. Lampka buduje szczegółowe detale, których `/actuator/health` nigdy nie renderuje**
— `memes/application.properties:118`, comments analogicznie
*Objaw:* `SagaListenersHealth.java:146-169` składa mapę `states` z komunikatami typu „no completed poll
for 173s (tolerance 150s)" i „stopped abnormally — the consumer thread died", a
`management.endpoint.health.show-details` nie jest ustawione nigdzie w repo — domyślne `NEVER`. Javadoc
klasy obiecuje „The details name container ids, states and ages", a `PurgeCommandsListener` uzasadnia
nazwę kontenera tym, że to „what SagaListenersHealth prints under /actuator/health".
*Scenariusz:* zmierzone — `/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}`,
`/actuator/health/readiness` → `{"status":"UP"}`, zero komponentów. Pod idzie na czerwono w nocy, operator
dostaje gołe `{"status":"DOWN"}` i nie wie nawet, czy to lampka listenera, czy `readinessState`.
*Naprawa:* `management.endpoint.health.show-details=always` (endpoint po naprawie S1 i tak nie jest
publiczny) albo `when-authorized`. To samo w comments.

**S9. Brak indeksów w memes — pięć realnie wykonywanych zapytań to seq scany** *(scalone: 2 pozycje)*
— `memes/db/migration/V1__memes.sql`
*Objaw:* w bazie memes jest 11 indeksów i wszystkie to `*_pkey` plus świadomy
`idx_meme_events_outbox_pending` (V5). Bez pokrycia zostają: `ORDER BY published_at DESC, id DESC`
w `allIds(offset,limit)` (każdy anonimowy `GET /memes`), `SELECT id FROM memes WHERE author = ?`
(purge RODO), `DELETE FROM meme_votes WHERE voter = ?` (purge RODO), `DELETE FROM content_index WHERE
meme_id = ?` (każde usunięcie mema i kompensacja uploadu), `SELECT meme_id FROM meme_tags WHERE tag = ?`.
*Scenariusz:* potwierdzone na żywej bazie — `EXPLAIN` na listingu daje `Sort → Seq Scan on memes`,
pozostałe trzy też Seq Scan. Czystka konta trzyma całą pętlę w jednej transakcji na wątku listenera Kafki,
więc płaci pierwsza. Bliźniaczy comments ma odpowiedniki założone (`idx_comments_author`,
`idx_comment_votes_voter`).
*Ograniczenie:* przy 112 memach to koszt czysto wydajnościowy, nie dzisiejsza awaria.
*Naprawa:* migracja V7 z pięcioma indeksami; przy okazji rozważyć keyset zamiast OFFSET.

**S10. Upload większy niż limit multipart odpowiada 500 „internal error" i loguje ERROR ze stacktrace**
— `WebErrorHandler.java:74-82`
*Objaw:* Tomcat rzuca gołe `IllegalStateException` opakowujące `SizeLimitExceededException` (Spring nie
zdąży zamienić tego na `MaxUploadSizeExceededException`), a to trafia w handler przeznaczony dla
„złamanych niezmienników serwera" — odpowiedź 500 `{"error":"internal error"}` i
`LOG.error("server-side invariant broken while handling a request", fault)`.
*Scenariusz:* odtworzone — 12 MB na `POST /memes` → HTTP 500 i linia
`ERROR [cid=…] c.j.m.i.WebErrorHandler - server-side invariant broken … SizeLimitExceededException:
the request was rejected because its size (12000211) exceeds the configured maximum (10485760)`.
Użytkownik z telefonowym zdjęciem widzi „Upload refused (500)." i nie ma jak się domyślić, że wystarczy
mniejszy plik; operator dostaje fałszywe 5xx w alertach. To złamanie javadoca tej samej klasy, który
zabrania mylić błąd klienta z awarią serwera.
*Naprawa:* przejść łańcuch przyczyn w `serverFault`, odpowiedzieć 413 `{"status":"FILE_TOO_LARGE"}`
i zalogować na WARN; UI ma rozpoznać 413.

**S11. Publiczny, nielimitowany odczyt galerii wymusza introspekcję: 1 anonimowy GET = 1 `GET /me`**
— `memes/RequireSignInFilter.java:44`
*Objaw:* filtr robi `bearerToken(request).flatMap(gate::callerFor)` **zanim** sprawdzi, czy to zapis, więc
rozwiązuje tożsamość dla każdego żądania pod `/memes` niosącego `Authorization`. Aktywna bramka to
`HttpSecurityAuthenticationGate` (`matchIfMissing = true`, deployment nie ustawia `SECURITY_VERIFY`),
czyli synchroniczny HTTP do security z read timeout 5 s trzymający wątek Tomcata. Odczyty nie mają
rate-limitu (`RateLimit` jest podpięty tylko do uploadu), nie ma cache negatywnych wyników.
*Scenariusz:* zmierzone — 30 równoległych anonimowych `curl -H 'Authorization: Bearer garbage$i' /memes/hot`
podbiło licznik `GET /me` w logu security dokładnie o 30. Przy wolnym security każde takie żądanie trzyma
wątek Tomcata do 5 s, a security to serwis, którego padnięcie zabija logowanie całego portalu.
*Ograniczenia:* `/memes/{id}/votes` i `/memes/{id}/meta` **realnie** używają tożsamości, więc bezużyteczna
introspekcja dotyczy `/hot`, `/scores`, `/{id}`, `/thumbnail`, `/tags`. Wybór introspekcji dla memes jest
zamierzony i opisany w compose; nieudokumentowane jest tylko to, że odpala się także na odczytach.
Wzmocnienie to 1:1, nie N:1 — atakujący musi celowo dokleić nagłówek.
*Naprawa:* rozwiązywać tożsamość leniwie, tylko dla ścieżek, które jej używają. Ewentualnie
`SECURITY_VERIFY=offline` jak w comments — ale to świadomie oddaje natychmiastową świadomość unieważnień.

**S12. `comments.ui-origin` przyjmuje dokładnie jeden origin i cicho odrzuca listę**
— `CommentsConfig.java:128-136`
*Objaw:* `@Value("${comments.ui-origin:http://localhost:8083}") String uiOrigin` idzie pojedynczo do
`.allowedOrigins(uiOrigin)` — jeden String do vararga, zero splitu po przecinku, zero ostrzeżenia.
Dla porównania security ma listę 5 originów, a collections parsuje CSV z sensownym defaultem. `UI_ORIGIN`
nie występuje ani w `docker-compose.yml`, ani w `k8s/`.
*Scenariusz:* zmierzone — dla `Origin: http://localhost:8083` comments oddaje ACAO, dla `:5173` i `:8093`
**nie oddaje nic**, podczas gdy security i collections oddają. Deweloper na Vite ma działające logowanie
i ulubione, a sekcja komentarzy sypie błędem CORS. Pod k3s jest gorzej: ingress jest host-per-service
(`comments.portal.localhost`), więc galeria dzwoni cross-origin i przy defaultowym `localhost:8083`
komentarze nie odpowiedzą w ogóle.
*Naprawa:* `String[] uiOrigins` (Spring sam rozbije CSV) i default równy trójce, którą zna collections.

**S13. `RESET_LINK_BASE` prowadzi do kontrolera POST-only — link z maila resetu hasła jest martwy**
— `k8s/base/security.yaml:56`, `shared/docker-compose.identity.yml:62`
*Objaw:* `PasswordResetController` ma wyłącznie `@Post` (33, 54, 70). Zmierzone:
`GET /reset-password?token=x` → **405**, `GET /` na security → **404** (security nie serwuje HTML-a).
Jedyne UI obsługujące ten link (`security-ui/src/App.tsx:325`) jest wg własnego README trzecim wejściem
dla specyfikacji, uruchamianym przez vite w e2e i nigdzie niewdrożonym.
*Scenariusz:* użytkownik klika link z maila i dostaje 405. Dotyczy **każdego** środowiska, nie tylko k8s.
`go-live-2026.md` traktuje to jako problem hosta w URL-u, a jest to brakująca strona.
*Naprawa:* `RESET_LINK_BASE` ma wskazywać na stronę, która istnieje — trasa w memes-ui albo wdrożone
security-ui. (Reszta pierwotnego zgłoszenia — Mailpit bez ingressu, `*.localhost:9080` — upadła jako
zadeklarowany zakres; patrz sekcja 6.)

**S14. Przepełnienie int w paginacji komentarzy — anonimowy GET daje 500, a serwis nie ma advice'a**
— `comments/CommentController.java:97-98`
*Objaw:* `int offset = Math.max(0, page) * limit;` — clamp jest na `page`, nie na iloczynie. W całym
`src/main` comments nie ma żadnego `@RestControllerAdvice` ani `@ExceptionHandler`.
*Scenariusz:* zmierzone — `GET /memes/abc/comments?page=25000000&size=100` → HTTP 500 z gołym bodym
Spring Boota (`page=0` → 200). Każde takie żądanie od anonima produkuje stack trace w ERROR, zasypuje
`Http5xxBurst` i maskuje prawdziwe awarie. Bliźniacza poprawka w memes istnieje i ma komentarz:
`MemeController.java:89-92` „long arithmetic on purpose: page * limit in ints overflows".
*Naprawa:* `long offset = Math.min((long) Math.max(0, page) * limit, MAX_OFFSET)` plus minimalny advice.

**S15. user-collections: listing bez LIMIT, brak capa wierszy i brak rate-limitu zapisu** *(scalone: 2 pozycje)*
— `JdbcCollectionStore.java:80-97`, `CollectionsApi.java:34-36,53,88-118`
*Objaw:* `list()` to `SELECT … WHERE user_email = ? AND collection = ? ORDER BY id DESC` bez LIMIT,
materializowany do `ArrayList`; endpoint nie przyjmuje żadnego parametru stronicowania; przy PUT jedyne
bramki to długości stringów (64/64/128). Zero limitu liczby wierszy, zero rate-limitu.
*Scenariusz:* zalogowany użytkownik skryptem robi setki tysięcy PUT-ów; jego własny GET buduje `ArrayNode`
ze wszystkimi wierszami i **dodatkowo** serializuje go do Stringa (`mapper.writeValueAsString`) — dwie
pełne kopie na stercie Helidona, który w tym samym JVM hostuje konsumenta czystek sagi i konsumenta
kaskady. Śmierć tego procesu zatrzymuje usuwanie kont w całym portalu.
*Ograniczenie:* ścieżka wymaga uwierzytelnienia — to nadużycie przez zalogowanego, nie anonimowy DoS.
*Naprawa:* LIMIT/OFFSET (albo keyset po `id`) + `?page/?size` z twardym sufitem; cap wierszy na
`(user_email, collection)` egzekwowany przy zapisie (409/429).
*Zamknięte:* czwarta część pierwotnego zgłoszenia (wiszące referencje do usuniętych memów) jest już
naprawiona commitem 44e8616.

**S16. Kreator usuwania konta obiecuje „delete my memes", a wysyła pusty wybór** *(scalone: 2 pozycje)*
— `memes-ui/src/DeleteAccountDialog.tsx:36-45,88-91`
*Objaw:* opcja „default" (domyślnie zaznaczona) ustawia `purge = null` i wysyła `JSON.stringify({})`,
a jej etykieta brzmi „Recommended: delete my memes (with their comment threads)". Serwer rozwiązuje wtedy
regułę jako `requested.or(override::current).orElse(defaultRule)` (`PurgeUserContent.java:40`), czyli
wygrywa runtime'owy override administratora.
*Scenariusz:* admin ustawia override na `ANONYMIZE_AUTHOR` (legalna opcja panelu). Użytkownik usuwa konto
z zaznaczoną rekomendowaną opcją, czyta „delete my memes" — a jego memy zostają w galerii z podmienionym
autorem. Pisemna obietnica przy operacji nieodwracalnej i objętej RODO nie zostaje dotrzymana.
*Uwaga:* zachowanie serwera jest **zamierzone i przetestowane** (`admin-purge-policy.feature:10` wprost
testuje leavera bez wyboru i oczekuje, że mem przeżyje), a `AdminPanel.tsx:63-64` jest wewnętrznie spójny.
Kłamie wyłącznie etykieta w kreatorze.
*Naprawa:* albo wysyłać jawnie to, co obiecuje etykieta, albo przeredagować opcję na „zastosuj domyślną
politykę serwisu" i pokazać wartość efektywną z `GET /admin/purge-policy`.

**S17. `memes-up.sh` nie startuje image-encodera, a degradacja do PNG nie zostawia śladu**
— `memes-up.sh:26-28`, `HttpImageEncoder.java:50-58`
*Objaw:* skrypt wymienia usługi jawnie i encodera w tej liście nie ma, a jego własny nagłówek twierdzi
„Databases, Kafka and the image encoder ride in via depends_on" — tymczasem `docker-compose.yml:84-93`
wymienia dla memes tylko `memes-postgres, minio, security, kafka`. Po drugiej stronie
`HttpImageEncoder.toWebp` zwraca `Optional.empty()` i przy statusie ≠ 200, i w `catch (Exception down)`;
`grep -c Logger` w tym pliku = 0.
*Scenariusz:* kto pierwszy raz odpala `./memes-up.sh` (skrypt „dnia pierwszego" z README) dostaje galerię
serwującą PNG każdemu klientowi akceptującemu WebP — 3–5× większy transfer — i nie ma jak się o tym
dowiedzieć. Ten sam mechanizm ukrywa awarię encodera na pełnym stacku (OOM-kill, zły `IMAGE_ENCODER_URL`).
*Naprawa:* dopisać `image-encoder` do listy (albo dodać `depends_on` i poprawić nagłówek); WARN przy
pierwszym nieudanym kontakcie z przełącznikiem stanu i licznik `memes_webp_encode_failures_total`.

**S18. `image-encoder` loguje żądania bez kodu odpowiedzi, a na uszkodzonej linii żądania wywala `AttributeError`**
— `microservice-image/server.py:226-227`
*Objaw:* nadpisany `log_message(self, fmt, *args)` ignoruje format i argumenty i drukuje tylko
`f"{self.command} {self.path}"` — ginie kod statusu z `log_request` i treść z `log_error`. Przy uszkodzonej
linii żądania `parse_request` nie ustawia `self.path`.
*Scenariusz:* odtworzone — surowe `GARBAGE-LINE-WITHOUT-SPACES\r\n\r\n` na porcie encodera: klient dostał
**pustą odpowiedź**, a w logu pełny traceback kończący się `AttributeError: … has no attribute path`
(wyjątek leci ze `send_error` → `log_error` **przed** `send_response`). Osobno: o 3:00 pytanie „czy encoder
odrzuca uploady czy je konwertuje" jest z logów nierozstrzygalne — udana konwersja i odmowa 400/411/413
dają identyczną linię.
*Naprawa:* `log_message(self, fmt, *args): log("INFO", fmt % args if args else fmt)`.

**S19. UI gubi odrzucone obietnice w trzech miejscach — awaria transportu = cisza** *(scalone: 3 pozycje)*
— `memes-ui/src/App.tsx:173-188`, `MemeDialog.tsx:167`, `DeleteAccountDialog.tsx:52-79`
*Objaw:* (a) `toggleFavourite` ma `try { … } finally { … }` **bez `catch`** — rollback siedzi wyłącznie
w gałęzi `if (!ok)`, więc odrzucenie fetcha przeskakuje rollback i `setWarning`, a `void toggleFavourite(...)`
kończy się unhandled rejection. (b) `const guard = (action: () => void)` jest wołany wyłącznie z funkcjami
`async` w ośmiu miejscach (151, 170, 177, 183, 195, 201, 212, 223) — TypeScript to przepuszcza
(`npx tsc --noEmit` przy `strict: true` daje EXIT=0), więc każdy odrzucony fetch ginie. (c) `submit`
i `submitCode` w `DeleteAccountDialog` ustawiają `setBusy(true)` bez `try/finally`, więc przy odrzuceniu
przyciski zostają wyszarzone bez komunikatu.
*Scenariusz:* padnięty user-collections albo utracone Wi-Fi — gwiazdka zapala się i nie cofa, ekran pokazuje
ulubiony, którego serwer nigdy nie zapisał. Niedostępny comments (inny origin, :8085) — strzałka pod
komentarzem nie reaguje, `setNotice` nie leci, w konsoli „Uncaught (in promise)". ErrorBoundary tego
z definicji nie złapie. W dialogu usuwania konta użytkownik nie wie, czy konto już się usuwa.
*Ograniczenia:* wszystkie odpowiedzi HTTP (w tym 5xx i 404) są obsłużone poprawnie — wybucha wyłącznie
warstwa transportowa; stan jest optymistyczny i prostuje się po F5; dialog usuwania odmontowuje się przy
zamknięciu, więc „na zawsze martwy" jest przesadą i nic po stronie serwera się nie stało.
*Naprawa:* `catch` z rollbackiem i komunikatem w `toggleFavourite`; `guard` przetypowany na
`() => void | Promise<void>` z jednym `.catch` (jedna zmiana pokrywa wszystkie osiem wywołań);
`try/catch/finally` w obu funkcjach `DeleteAccountDialog`. Analogicznie `AdminPanel.tsx:31,86`.

**S20. Warstwa uczestnika sagi utrzymywana w dwóch kopiach (~2750 linii)**
— `memes/SagaRetryBudget.java` i bliźniak w comments. Szczegóły i decyzja: **sekcja 4**.

### NISKI

**N1. NSFW: DELETE+INSERT poza transakcją** *(scalone: 2 pozycje)* — `JdbcContentFlags.java:23-29`
Klasa nie ma `@Transactional`, `FlagMeme` też nie (`MemesConfig.java:88-90` tworzy go gołym `new`,
w odróżnieniu od `TransactionalDeleteMeme`). Dwa równoległe PUT-y kolidują na PK `meme_flags` → 500
z kłamliwym komunikatem UI „Only a moderator may flag NSFW." (wołający jest moderatorem);
`MemeDialog.tsx:176-180` nie blokuje przycisku. Wariant fail-open (awaria między DELETE a INSERT odbiera
flagę) jest przez UI praktycznie nieosiągalny, bo toggle wysyła `true` tylko dla mema nieoflagowanego.
Naprawa razem z priorytetem 3.

**N2. Dial polityki purge: DELETE+INSERT bez transakcji, a `clear()` kasuje jedyny ślad** *(scalone: 2 pozycje)*
— `JdbcPurgePolicyOverride.java:36-46`
Awaria dokładnie między instrukcjami kasuje override i cofa serwis do `PURGE_MEMES_POLICY:DELETE`
(`application.properties:44`) — fail-destructive, bez linii w logu. Osobno: `V4__settings.sql` zakłada
`updated_at` i `updated_by NOT NULL`, `set()` je wypełnia, a **nikt ich nigdy nie czyta** —
`AdminController.current()` zwraca tylko axis/effective/source/envDefault. Po zresetowaniu diala nie da
się odpowiedzieć, kto i kiedy zmieniał politykę kasowania cudzych treści, mimo że dane są w bazie.
*Naprawa:* `ON CONFLICT DO UPDATE`, zwrócić `updatedAt/updatedBy` w GET i pokazać w panelu, `clear()`
ma zostawiać ślad (wiersz albo INFO z cid).

**N3. Stronicowanie wątku komentarzy bez indeksu pokrywającego sortowanie i bez tie-breaka**
— `JdbcCommentRepository.java:36-41`
`ORDER BY created_at LIMIT ? OFFSET ?`, a jedyny indeks to `idx_comments_meme(meme_id)`. Predykat jest
pokryty, więc baza sortuje wyłącznie komentarze tego mema (dziesiątki wierszy) — realny zostaje brak
deterministycznego tie-breaka: przy równych `created_at` kolejność między stronami nie jest gwarantowana,
a UI po paczce 11 woła strony w pętli. *Naprawa:* `CREATE INDEX … (meme_id, created_at, id)` i `, id`
w `ORDER BY`; docelowo keyset (zamyka też N29).

**N4. `HttpMemeDirectory`: awaria memes zamienia się w kłamliwe 404 „nie ma takiego mema"**
— `comments/HttpMemeDirectory.java:36-38`
`catch (RestClientException missingOrDown) { return false; }` — timeout (connect 2 s, read 5 s),
connection refused, 5xx i prawdziwe 404 są nierozróżnialne; zero Loggera, zero metryki. Wynik idzie do
`AddComment.java:23` i kończy się 404 z kontrolera. Klasa nie ma żadnego testu (grep po `src/test`: tylko
dublery in-memory). Zachowanie jest świadome (komentarz „reads as no such meme"), nieudokumentowany jest
brak **jakiegokolwiek** sygnału operacyjnego. *Naprawa:* 404 → false, reszta → 503 z `Retry-After`,
WARN z rate-limitem, test z dublerem rzucającym `ResourceAccessException`.

**N5. Krok cucumber „the leaver's meme survives anonymised" dowodzi tylko przeżycia**
— `AdminPolicySteps.java:89-93`
Cała treść kroku to `assertEquals(200, … get("/memes/" + memeId).statusCode())`. *Złagodzenie:* sama reguła
`ANONYMIZE_AUTHOR` **jest** przetestowana (`PurgeUserContentTest:130,143` asertuje `DeletedAccount.AUTHOR`),
nieprzykryta zostaje jednolinijkowa implementacja JDBC (`JdbcMemeRepository:145-147`; grep za
`reassignAuthor` po testach: zero). *Naprawa:* asercja na autorze w kroku e2e.

**N6. CI nie ma strażnika świeżości paktów, a kolejność reaktora sprzyja przeoczeniu**
— `.github/workflows/ci.yml:122-124`, `pom.xml:26-30`
Offboarding jest ostatni w reaktorze i zapisuje pakty do `${project.basedir}/pacts`, a providerzy czytają
te pliki wcześniej w tym samym przebiegu (`@PactFolder("../microservice-offboarding/pacts")`).
W workflowach nie ma ani jednego `git diff --exit-code -- */pacts`. *Złagodzenie:* „cicho pominięty pakt"
jest już zamknięty (`SilentlySkippedPactTest`) — brakuje wyłącznie kontroli świeżości.
*Naprawa:* krok `git diff --exit-code` po buildzie; docelowo Pact Broker.

**N7. Dziura w macierzy kontraktów: comments konsumuje `MEME_DELETED` bez własnego paktu**
— `MemeDeletedPactProviderTest.java:60`
`PACT_FOLDER = "../../microservice-user-collections/pacts"` — dostawca weryfikuje się wyłącznie wobec
oczekiwań collections, mimo że comments ma `MemesEventsListener` na `memes-events` i to on wyzwala drugi
przeskok kaskady. ADR 0003 zakłada, że kontrakt pisze każdy konsument. *Złagodzenie:* dziś obaj konsumenci
czytają te same dwa pola i pakt collections je przypina, więc zmiana kształtu i tak zapali czerwone.
*Naprawa:* jeden plik paktu + wpis w `@PactFolder` i w `SilentlySkippedPactTest` (~30 minut).

**N8. Reguły sagi mieszkają w dwóch adapterach `SagaStore` bez wspólnego testu kontraktowego**
— `offboarding/RecordConfirmation.java:24`, `JdbcSagaStore.java:145-190`
„COMPLETED gdy potwierdzili wszyscy wymagani" i „ponów N razy, potem kompensuj" są zaimplementowane
wewnątrz metod portu, osobno w SQL i osobno w mapach. Przejrzano wszystkie 24 pliki testowe offboardingu:
nie ma niczego parametryzowanego po obu implementacjach. Scenariusze Gherkin jadą na `InMemorySagaStore`,
więc dryf uderza w wiarygodność suity, a nie w produkcję. *Naprawa:* wspólny `SagaStoreContractTest`,
dokładnie jak `PurgeRuleContractTest` w memes/comments.

**N9. Metryki nazwane „alertem", dla których nie istnieje reguła alertu** *(scalone: 2 pozycje)*
— `offboarding/MetricsEndpoint.java:13-22`, `memes/SagaParticipantConfig.java:106-112`
Javadoc pierwszej nazywa `offboarding_sagas_compensated_total` „the alert", drugiej —
`memes_kafka_records_dropped_total` „the alert an operator wants". `shared/observability/alert-rules.yml`
ma dokładnie trzy reguły (TargetDown, Http5xxBurst, HostMemoryHigh) i żadna ich nie dotyczy; grep za
`records_dropped` po yml/yaml/json: zero, więc nie ma ich też w dashboardach. Brakuje też gauge'a
„ile sag utknęło TERAZ" i metryki odzwierciedlającej werdykt `/health` — martwa pętla nie rusza
`up{job="offboarding"}`.
*Ograniczenie:* nagłówek `alert-rules.yml` jawnie deklaruje brak Alertmanagera („nobody pages a hobbyist
at 3 a.m."), więc dodatkowa reguła nikogo by nie obudziła — to samo dotyczy trzech istniejących. Teza
„AtomicLong zerowany przy restarcie kłamie o historii" jest fałszywa: to normalna semantyka licznika
Prometheusa. *Naprawa:* dwie reguły w `alert-rules.yml` (pięć linii) + gauge `offboarding_sagas_started`
/ `offboarding_oldest_started_saga_seconds` / `offboarding_ready`.

**N10. Ścieżka sweepera wysyła zdarzenia Kafki bez `X-Correlation-Id`** — `offboarding/KafkaLoop.java:340`
Pętla konsumenta przekłada cid (`send(producer, outgoing, cid)`, l.289), sweeper woła to samo z jawnym
`null`. Ponowione `PURGE_USER_CONTENT`, `PORTAL_PURGE_FAILED` i ponowione ogłoszenia outcome'ów idą bez
korelacji, a wątek sweepera nie ustawia MDC. Dokumentowany sposób pracy o 3:00 to
`{service=~".+"} |= "cid=<CID>"` — dla sagi, która się zacięła, grep milknie dokładnie w interesującym
miejscu. *Uczciwie:* sweeper nie ma skąd wziąć cid, bo saga go nie przechowuje (kolumny: id, email, state,
retries, policy, created_at/updated_at) — naprawa wymaga migracji albo użycia `"saga-" + sagaId`.

**N11. Linia loga z wyjątkiem — jedyna, której operator szuka — jest jedyną bez cid**
— `memes/CorrelationIdFilter.java:41-46`
Filtr ma `@Order(HIGHEST_PRECEDENCE)` i czyści MDC w `finally`, a Tomcatowy `StandardWrapperValve` loguje
wyjątek dopiero po jego powrocie. Zaobserwowane przy okazji testu wyścigu głosów:
`ERROR [cid=] [trace=…] … Servlet.service() … threw exception`, przy wypełnionym cid na linii dostępowej
tego samego żądania. Javadoc obiecuje „grep that id and the whole journey … shows up". `trace_id` przeżywa,
więc korelacja jest możliwa inną drogą. *Naprawa:* handler-podłoga na `Exception` w `WebErrorHandler`
(przy okazji domyka W2) zamiast liczenia na Tomcata.

**N12. Nieznana oś polityki czystki ginie w ciszy, a zastosowana reguła nie jest logowana**
— `memes/PurgeCommandsListener.java:66-83`
Dla reguły **nieparsowalnej** jest rozbudowany WARN z sanityzacją; dla `policy.memes` **nieobecnego**
zwykłe `return Optional.empty()` bez słowa. W `handle()` jedyny INFO to „purged the memes of one leaver
(saga {})" — bez reguły i bez jej źródła, choć łańcuch kończy się fail-destructive defaultem.
*Sprostowanie:* wycięcie polityki przez `MAX_POLICY_BYTES` **nie** jest ciche — `EventsRouter:176-185`
loguje jawny WARN. Zostaje literówka w nazwie osi i brak INFO z zastosowaną regułą.
*Naprawa:* INFO z regułą i źródłem w obu listenerach, WARN gdy `policy.isObject() && rule.isMissingNode()`.

**N13. Uczestnicy wysyłają `sagaId: ""`, więc udokumentowana ścieżka zgodności wstecz jest martwa**
— `memes/PurgeCommandsListener.java:148`, `comments/PurgeCommandsListener.java:98`
`command.path("sagaId").asText()` na brakującym węźle daje `""`, a `PurgeConfirmations.confirmationOf`
bezwarunkowo robi `.put("sagaId", sagaId)` — pole jest **zawsze** obecne. `EventsRouter:203-220` degraduje
do wyszukiwania po e-mailu tylko gdy pola nie ma, a `""` drop-uje jako poison pill. Ciekawe: autor rozważył
pusty sagaId, ale tylko dla klucza partycji (`keyFor` ma fallback `nameUUIDFromBytes`) — asymetria jest
realna i nieudokumentowana. Skutek czysto hipotetyczny (wymaga producenta komend spoza offboardingu).
*Naprawa:* albo usunąć martwy fallback i nazwać `sagaId` polem obowiązkowym, albo pomijać puste pole
i traktować `""` jak brak.

**N14. `auto.offset.reset` zostawiony na domyślnym `latest` w memes i comments**
— `memes/application.properties:45`, comments analogicznie
Grep po `src/main` wszystkich czterech uczestników: `auto.offset.reset` występuje wyłącznie w Helidonach
(collections ×2, offboarding ×1), wszędzie `earliest`. Czterech uczestników jednego kontraktu ma dwie
polityki startu. Zgubiona komenda `PURGE_USER_CONTENT` wraca przez sweeper, więc realnie nieodwracalny
jest tylko przypadek `MEME_DELETED` do świeżej grupy comments — osierocony wątek bez sygnału.
*Naprawa:* jedna linijka w obu serwisach z komentarzem, że to ta sama polityka co u Helidonów.

**N15. `MEME_DELETED` — jedyne zdarzenie startujące kaskadę — nie niesie pola `version`**
— `KafkaMemeEvents.java:79-87`
Payload budowany konkatenacją stringów, bez `version`, podczas gdy wszyscy pozostali producenci
(COMMENTS_DELETED, obie PurgeConfirmations, collections, offboarding ×2, security ×4) stemplują
`version: 1`. ADR 0004 wymienia `memes-events` w Scope z nazwy. Osobno: javadoc (39-40) obiecuje, że
„the consumers recognise the duplicate by it [eventId]" — grep po `MemesEventsListener` i `CascadeConsumer`:
zero odczytów `eventId` (idempotencja jest osiągana innym mechanizmem).
*Ograniczenia:* ADR sam pisze, że pakty świadomie nie zawierają `version` i „any event without the field
is implicitly v1"; ryzyko konkatenacji jest nieosiągalne (do payloadu trafia wyłącznie id wydane przez ten
serwis). *Naprawa:* dopisać `version`, przepisać na Jacksona jak bliźniak, poprawić javadoc.

**N16. Reguła autoryzacji moderatora zduplikowana inline w czterech kontrolerach; `Caller.isModerator()` martwy w produkcji**
— `MemeController.java:211,226`, `CommentController.java:138,175`
`roles != null && (roles.contains("MODERATOR") || roles.contains("ADMIN"))` w czterech kopiach plus po raz
piąty w `Caller.java:15` w obu repo. Grep za `isModerator` po całych repo: definicje plus wywołania
**wyłącznie** w `JwtSecurityAuthenticationGateTest:41,54` — zero użyć produkcyjnych. Przyczyna:
`RequireSignInFilter:45-48` wkłada do atrybutu samo `c.roles()`, nie obiekt `Caller`. Dziś wszystkie cztery
kopie są identyczne, więc to duplikacja i fałszywe poczucie pokrycia, nie dziura autoryzacyjna.
*Naprawa:* wkładać całego `Callera` albo wystawić `static boolean isModerator(Set<String>)`.

**N17. `memes.author` i `meme_votes.voter` to `VARCHAR(200)`, a tożsamości mają do 255 znaków**
— `V1__memes.sql:6,25`
Security trzyma e-maile w `VARCHAR(255)` i nie ogranicza ich długości (`Email.of` sprawdza wyłącznie
pozycję pojedynczego `@`), comments używa 255, collections 320, offboarding 255. Żadna migracja V2–V6 tego
nie poszerza (V4 używa już 255 dla `settings`, więc 200 to relikt). Konto z adresem >200 znaków
zarejestruje się, zaloguje i skomentuje, ale każdy upload i każdy głos skończy się 22001 i 500.
*Naprawa:* `ALTER TABLE … TYPE VARCHAR(255)` — w Postgresie rozszerzenie varchar nie przepisuje tabeli.

**N18. Dokumentacja rozjeżdża się z kodem w pięciu miejscach** *(scalone: 2 pozycje)*
— `microservice-offboarding/README.md:79-80` i dalej
(1) README wymienia cztery zmienne, a `Main` czyta dziesięć — brak m.in. `OFFBOARDING_RETENTION_DAYS`,
czyli pokrętła decydującego, jak długo e-mail osoby żądającej usunięcia leży w bazie; grep za „alive":
zero trafień, mimo że `/alive` jest livenessem w k8s i ma własny nietrywialny floor.
(2) `Main.java:229` wypisuje jedyną linię startową przez `System.out.println`, bez timestampu, bez `[cid=]`
i bez efektywnych wartości. (3) `collections/PurgeCommandsConsumer.java:29-31` nadal przypisuje komendę
„microservice-security's outbox", choć orkiestratorem jest offboarding. (4) `MemeRepository.java:10-12`
opisuje się jako „in-memory now, a real store later" przy działającym adapterze JDBC.
(5) `microservice-user-collections` nie ma README.
*Scenariusz:* podczas audytu RODO nie da się z żywego procesu ani z logów odczytać, po ilu dniach kasowane
są adresy — `docker inspect` pokaże tylko zmienne jawnie ustawione, a te są nieustawione.
*Naprawa:* uzupełnić README (z naciskiem na `RETENTION_DAYS` jako pokrętło RODO), `LOG.info` z jedną linią
efektywnych wartości wszystkich dziesięciu, poprawić dwa javadoce, dopisać README dla collections.

**N19. `COLLECTIONS_PORT` parsowany gołym `Integer.parseInt`** — `collections/Main.java:181`
`COLLECTIONS_PORT=80a` wywala serwis komunikatem `For input string: "80a"` bez nazwy zmiennej — podczas gdy
**ten sam plik** ma już wzorzec `stallSeconds(name, raw)` (163-178) rzucający komunikat z nazwą zmiennej,
użyty dla dwóch innych pokręteł, a bliźniaczy Helidon zbudował `longEnv(name, default, min, max)`
z uzasadnieniem „a bare NumberFormatException names neither the variable nor the fix".
*Zamknięte:* pierwsza połowa pierwotnego zgłoszenia (duplikaty w `parseParticipants`) — commit 57821cf.

**N20. Globalny dedup po SHA-256 nie prowadzi listy zgłaszających i nigdzie tego nie napisano**
— `PurgeUserContent.java:8-15`, `PublishMeme.java:33-38`
Drugi użytkownik wgrywający identyczny obraz dostaje id mema pierwszego autora i nic nowego nie powstaje
(jeden wiersz, jeden author). `PurgeUserContent` traktuje „mem, którego author = odchodzący" jako treść
wyłącznie odchodzącego i przy domyślnej regule DELETE usuwa go w całości wraz z kaskadą `MEME_DELETED`,
czyli razem z wątkiem komentarzy innych osób. Javadoc wspomina tylko o „votes and dedup-index entry".
Wymaga identycznego bajt-w-bajt obrazu po optymalizacji. *Naprawa (minimum):* dopisek w javadocu;
docelowo zawęzić dedup do `(author, content_hash)` albo prowadzić listę zgłaszających.

**N21. `GET /memes/{id}` bez żadnego `Cache-Control`, gdy miniatura ma 30 linii uzasadnienia RODO**
— `MemeController.java:107-118` vs `120-162`
Zmierzone: pełny obrazek zwraca tylko `Vary: Accept` i `Content-Type`, miniatura —
`Cache-Control: max-age=3600, public`. *Sprostowanie:* odpowiedź bez `Cache-Control`, `Expires`
i `Last-Modified` nie ma z czego wyliczyć heurystycznej świeżości, więc „proxy może ją trzymać
w nieskończoność" nie ma pokrycia. Zostaje: każde otwarcie mema to świeży round-trip do MinIO przez
aplikację, i — ważniejsze — decyzja o oknie kasowania dla **większego** artefaktu nie została nigdzie
zapisana, choć dla mniejszego jest opisana na trzydziestu liniach. *Naprawa:* postawić jawną politykę
(ta sama godzina co miniatura albo `cachePrivate`/`noStore`) — rzecz w tym, żeby decyzja była zapisana.

**N22. Publiczne endpointy odczytu wykonują pracę proporcjonalną do całej tabeli, bez rate-limitu i cache**
— `SearchMemesByTag.java:24-28`, `RankMemes.java:44-51`, `JdbcContentFlags.java:38-42`
`GET /memes?tag=` woła `allIds()` bez LIMIT i przecina w JVM (strona 20 kosztuje tyle co strona 0);
`GET /memes/hot` agreguje całe `meme_votes` i sortuje w JVM, dopiero potem `TOP_N=100`; każdy `GET /memes`
ładuje **wszystkie** oflagowane id, żeby oznaczyć 50 kafelków. RateLimit jest podpięty tylko do uploadu.
*Ograniczenie:* to w dużej części świadomy, opisany kompromis — `SearchMemesByTag:25-26` sam mówi „still
a full listing intersected in memory … the page bound at least keeps the RESPONSE from growing without
end". Dług wydajnościowy, nie defekt. *Naprawa:* JOIN w SQL, `TOP_N` w SQL albo cache na kilkadziesiąt
sekund, `nsfwIds` tylko dla id bieżącej strony.

**N23. Purge RODO odpytuje o wynik głosowania każdego mema, także gdy reguła brzmi DELETE**
— `PurgeUserContent.java:41-42`
`rule.keeps(voteRepository.scoreOf(memeId))` — argument jest wyliczany przed wywołaniem, a
`PurgeRule.Delete()` zwraca `false` niezależnie od wyniku. To N+1 w jednej transakcji na wątku konsumenta,
obok seq scanu z S9. Istnieje gotowy batch `scoresOf(...)`, nieużywany na tej ścieżce.
*Naprawa:* `default boolean needsScore()` (false dla Delete i AnonymizeAuthor) albo jeden `scoresOf`.

**N24. `RankMemes`: ujemny wynik odwraca porządek ogona listy hot** — `RankMemes.java:54-61`
`score / (ageHours + 2)^1.5` — dla score dodatniego rosnący mianownik obniża pozycję, dla ujemnego podnosi
ją ku zeru, czyli **wyżej**. Mem sprzed tygodnia ze score −100 daje −0,045, świeży ze score −1 daje −0,19.
Ujemne wyniki są osiągalne (`SUM(CASE WHEN direction='UP' THEN 1 ELSE -1 END)` bez filtra), a endpoint nie
odsiewa niedodatnich. Dziś niewidoczne (UI bierze z `/memes/hot` tylko mapę id→score), ale kontrakt
publicznego endpointu kłamie, a cały `RankMemesTest` operuje na liczbach dodatnich.
*Naprawa:* wariant znakowany `signum(score) * log10(max(|score|,1)) / …` albo odsianie niedodatnich, plus
test „stary mocno zminusowany nie wyprzedza świeżego lekko zminusowanego".

**N25. Access token w `localStorage`, gdy refresh token jest chroniony jako HttpOnly** — `memes-ui/src/App.tsx:29,137,140`
`collections-ui` trzyma token wyłącznie w stanie Reacta (grep za `localStorage|sessionStorage`: zero).
Żaden serwis nie wysyła CSP / X-Content-Type-Options / Referrer-Policy (sprawdzone `curl -I`).
XSS albo przejęta zależność npm czyta token jednym wyrażeniem; comments i collections weryfikują token
offline, więc są ślepe na unieważnienie. *Ograniczenia:* to tylko access token o żywotności godziny —
refresh siedzi w HttpOnly cookie („nothing in the page can see or send that token itself"), a XSS na tym
originie i tak może wywołać `/refresh` z `credentials:'include'`, więc przeniesienie do pamięci nie zamyka
wektora. Trwałość jest celowa i udokumentowana (karta otwarta po godzinie wymienia token zamiast wyrzucać
na login). **Decyzja do podjęcia, nie oczywista naprawa.** Bliźniacze zgłoszenie z obiektywu UI zostało
obalone — patrz O8.

**N26. `adminOpen` przeżywa zmianę sesji — dialog administratora zostaje dla następnego zalogowanego**
— `memes-ui/src/App.tsx:129-139,339`
Blok czyszczący zeruje `user`, `isModerator`, `isAdmin`, `favourites`, `showFavourites` i localStorage,
ale nie `adminOpen` ani `selected`; `<AdminPanel …/>` renderuje się poza jakimkolwiek warunkiem na
`isAdmin`, a `AdminPanel` trzyma `policy` w stanie z efektem na `[open]`. Wyciekają dwie wartości
konfiguracji purge, nie dane osobowe; zapis jest autoryzowany serwerowo. *Naprawa:* dopisać
`setAdminOpen(false); setSelected(null);` albo renderować `{isAdmin && <AdminPanel …/>}`.

**N27. Każdy głos — również odrzucony — zwija ścianę z N doładowanych stron do pierwszej**
— `MemeDialog.tsx:200-209`, `App.tsx:57-69,333`
`onVoted()` stoi **poza** gałęziami `if (ok && fresh)` / `else if (status !== 401)`, a `refresh` robi
`setMemes(page)` i `setPagesShown(1)`. Cztery kliknięcia „Load more" znikają — także wtedy, gdy głos nie
przeszedł i użytkownik właśnie zobaczył „Your vote did not go through". *Naprawa:* przenieść `onVoted()`
do gałęzi sukcesu, docelowo odświeżenie punktowe zamiast `refresh`.

**N28. Wątek komentarzy resetuje się do strony 0 przy cichym odświeżeniu tokenu i przy każdym „ukryj"**
— `MemeDialog.tsx:117,144,145,196`
`token` jest w zależnościach `loadThread`/`load`, a `useEffect(load, [load])` przeładowuje wątek od zera.
Token zmienia się **automatycznie** przy każdym odświeżeniu sesji (`api.ts:124 session.renewed` →
`App.tsx:122 renewed: setToken`). 300 komentarzy zwija się do najstarszych 100, a komentarz, na który
użytkownik właśnie głosował, znika z ekranu. `toggleHidden` po sukcesie woła `load()`, więc moderator
traci potwierdzenie, że akcja zadziałała. Utrata pozycji w widoku, nie danych.
*Naprawa:* trzymać token w ref albo przekazywać argumentem; w `toggleHidden` aktualizować listę lokalnie.

**N29. „Load newer" gubi komentarz, gdy usunięcie przesunęło granice stron po stronie serwera**
— `MemeDialog.tsx:186,124`, `api.ts:241`
`removeComment` filtruje listę lokalnie i nie rusza `pagesLoaded`, a paginacja jest offsetowa po
`ORDER BY created_at`. Po skasowaniu wpisu ze strony 0 element o dawnym indeksie 100 zjeżdża na 99 i żadne
kolejne żądanie po niego nie sięgnie — mimo napisu „Showing the oldest N comments — there are more".
Wymaga wątku >100 komentarzy, usunięcia i kliknięcia „Load newer"; ginie dokładnie jeden komentarz i wraca
po ponownym otwarciu dialogu. *Naprawa:* przeładować wątek z zachowaniem liczby stron albo przejść
na kursor (zamyka się z N3).

**N30. collections-ui: brak `credentials`, brak odnawiania tokenu i 401 bez komunikatu** *(scalone: 2 pozycje)*
— `collections-ui/src/App.tsx:45,124,185,211`
`fetch(SECURITY/authenticate)` bez `credentials: 'include'`, a w trzech miejscach `signOut(); return;`
bez `setNotice` — `Favourites` odmontowuje się razem ze stanem, wpisany identyfikator przepada i nigdzie
nie pada zdanie dlaczego. To samo w memes-ui pokazuje „Your session expired — sign in again…".
*Sprostowanie:* brak `credentials` jest dziś **bezskutkowy** — ten UI nie ma i nigdy nie miał logiki
odnawiania (zero wywołań `/refresh`), więc sama flaga nie zmieniłaby ani jednego zachowania.
*Kontekst:* to jawnie zredukowany klient („that is the point of this UI: it exercises the CORS edge"),
który przy 202 sam odsyła do galerii po MFA. *Naprawa (minimum):* komunikat przed `signOut`.

**N31. nginx w collections-ui ma zaszyty resolver Dockera, a manifest k8s nie ustawia `MEMES_RESOLVER`**
— `k8s/base/collections-ui.yaml:24-46`
Dockerfile sam pisze, że „a cluster deployment overrides MEMES_RESOLVER with its own DNS service
(in k8s: kube-dns.kube-system.svc.cluster.local)" i ustawia domyślnie `127.0.0.11`; manifest nie ma sekcji
`env` w ogóle. Zapytanie do nieistniejącego `127.0.0.11:53` kończy się timeoutem i 502, więc kafelki
na zawsze wiszą w „couldn't check", a read-repair się nie odpala. *Ograniczenia:* degradacja jest
zaprojektowana i bezpieczna (502 nigdy nie renderuje się jako oferta usunięcia), a do tego widoku i tak
nie da się w tym klastrze dojść, bo bundle woła `localhost` (co manifest sam deklaruje).
*Naprawa:* dwie linie `env` — obraz obsługuje to przez envsubst.

**N32. Kafelki galerii bez `alt`, a przycisk otwierający mema nazywa się „▲ 0"**
— `memes-ui/src/App.tsx:300-311,394-401`
`<CardMedia component="img" …/>` w obu miejscach renderuje `<img>` bez `alt`, a `CardActionArea` nie ma
`aria-label`, więc nazwa dostępna wylicza się z Chipów: czytnik ekranu odczytuje serię przycisków
„▲ 0", „▲ 3", „NSFW ▲ 1". Dodatkowo kafelki głównej ściany nie mają `onError` (ma go tylko `FavouriteTile`,
z jawnym komentarzem o cache'owanej miniaturze usuniętego mema), więc nieudana miniatura zostaje pustym
prostokątem. *Naprawa:* `aria-label` na `CardActionArea`, `alt` na obrazku, `onError` jak w `FavouriteTile`.

---

## 4. Dług duplikacyjny — decyzja, nie zadanie

**Fakt zmierzony.** Warstwa uczestnika sagi istnieje w dwóch kopiach: 12 klas produkcyjnych (1195 linii
po stronie memes) i 17 klas testowych (1554 linie). Po znormalizowaniu nazw domenowych różnice między
bliźniakami to: `SagaRetryBudget` **0 linii**, `SagaListenersHealth` 15, `SagaParticipantConfig` 19,
`PurgeConfirmations` 30, `Caller` 8, `SecurityAuthenticationGate` 5, `JwtSecurityAuthenticationGate` 6,
`RequireSignInFilter` 18, `CorrelationIdFilter` 15, `HttpSecurityAuthenticationGate` 32; w testach
`SagaRetryBudgetTest` 0, `SagaListenersHealthTest` 0, `CapturedConfirmations` 0, `NoTransactions` 0.
Realnie różne są tylko `PurgeCommandsListener(+Test)`, `TestAuthConfig` i `KafkaProducerClocksTest`.
To ten sam wzorzec, który w paczce 10 uzasadnił wyprowadzenie outboxu do `../shared`, tyle że o rząd
wielkości większy.

**Koszt już zapłacony, nie hipotetyczny.** Dwie poprawki bezpieczeństwa zostały wykonane dokładnie
w połowie miejsc: sanityzacja `X-Correlation-Id` trafiła tylko do comments (S2), próg MFA tylko do memes
(S3). Trzecia oś — reguła „kto jest moderatorem" — istnieje w pięciu kopiach w dwóch repo, a metoda, która
ją enkapsuluje, jest wołana wyłącznie z testów (N16). Statystycznie każda przyszła poprawka w tej warstwie
ma około 50% szans wylądować w jednym serwisie.

**Czego NIE traktuję jako dowodu.** `KafkaTracing` deklaruje w javadocu bajtową tożsamość z bliźniakiem
i faktycznie różni się od niego o 7 linii — ale to jest **dokładnie ten akapit**, o którym ten sam javadoc
pisze „The sentence naming the twin is the one line that MUST differ between the two copies".
Udokumentowana, zamierzona różnica; nie liczę jej jako złamany niezmiennik.

**Trzy warianty do wyboru.**

**(A) Wyprowadzić moduł `saga-participant-spring` do `../shared`** — obok `infrastructure-spring-outbox`.
Zawartość: `SagaRetryBudget`, `SagaListenersHealth`, `SagaParticipantConfig` (sparametryzowane prefiksem
property i nazwą metryki), `PurgeConfirmations` (sparametryzowane topikiem), `Caller` + `withMfaFloor`,
obie bramki, `CorrelationIdFilter`, `RequireSignInFilter`. Netto do skasowania ~1750 linii. Pułapka:
prefiksy (`memes.` / `comments.`) i nazwy zmiennych operatora (`MEMES_LISTENER_STALL_SEC` vs
`COMMENTS_LISTENER_STALL_SEC`) muszą przejść na konfigurację modułu, żeby komunikaty walidacji nadal
nazywały właściwą zmienną. Koszt: **dzień+**, skala paczki 10, z bliźniaczymi testami i dwiema zielonymi
suitami do utrzymania.

**(B) Zostawić dwie kopie i wprowadzić rytuał.** Test porównawczy w CI (znormalizowany diff obu drzew
z listą dozwolonych różnic), który staje na czerwono, gdy ktoś zmieni jedną kopię. Koszt: pół dnia.
Zaleta: zero ryzyka regresji w działającym systemie. Wada: utrwala 2750 linii i nie pomaga trzeciemu
uczestnikowi, gdyby kiedyś powstał.

**(C) Nic nie robić, ale najpierw domknąć S2, S3 i N16.** Wtedy dług zostaje, ale dwie dziury
bezpieczeństwa znikają.

**Rekomendacja:** (C) natychmiast (priorytet 6), potem (A) jako osobna paczka — nie dlatego, że 2750 linii
boli w utrzymaniu, tylko dlatego, że to jest portfolio na rozmowy kwalifikacyjne, a „wyprowadziłem warstwę
uczestnika sagi do wspólnego modułu, bo dwie poprawki bezpieczeństwa trafiły w połowę miejsc" to lepsza
historia niż „mam skrypt, który pilnuje, żeby kopie były identyczne". (B) tylko jeśli (A) miałoby czekać
dłużej niż miesiąc.

---

## 5. Czego ten przegląd NIE sprawdził

Uczciwie, bo od tego zależy, ile warte są powyższe werdykty.

**1. Nie uruchomiono ani jednej suity testów.** Żadnego `mvn verify`, `npm test`, cucumbera ani
`e2e-saga.sh`. Wszystkie werdykty pochodzą z lektury kodu, `git show --stat`, rozpakowanych źródeł
frameworków (spring-kafka 3.3.7, micronaut-management 4.10.22, micronaut-kafka 5.9.0) oraz odpytywania
żywego stacku curl-em i psql-em. **Nie wiadomo, czy suita jest dziś zielona** ani czy któreś ze zgłoszeń
ma już czerwony albo pominięty test, który je opisuje.

**2. Klaster k3s nie był wdrożony.** Wszystkie znaleziska z `k8s/` (W4, S5, S6, S13, N31, ingress w S1)
są statyczne — z manifestów i ze źródeł frameworków. Zachowanie Traefika, kubeleta i local-path opisano
na podstawie ich znanych właściwości, nie obserwacji tego klastra. OOMKill security i restart-loop przy
padniętym Postgresie **nie zostały zademonstrowane**.

**3. Brak konta MODERATOR/ADMIN.** Wyścigi na `PUT /memes/{id}/nsfw` (N1) i `PUT /admin/purge-policy` (N2)
udowodniono z kodu i przez analogię do wyścigu głosów, który potwierdzono na żywo na tej samej bazie.
Nadanie sobie roli wymagałoby modyfikacji bazy security.

**4. Zerowe albo szczątkowe pokrycie:** `microservice-email` (Quarkus), `microservice-idp/sms/push`
(Python), `security-ui`, `e2e/` i `memes-ui/e2e/`, workflowy CI poza gerpem za paktami,
`shared/transactional-outbox` i `infrastructure-spring-outbox` (paczka 10 — poza zakresem),
`shared/offline-jwt` poza odczytaniem cienkich adapterów, pełne suity BDD i migracje comments oraz
user-collections. Nie wiadomo, czy w tych ostatnich nie ma odpowiedników N5 — kroków dowodzących mniej,
niż obiecuje ich nazwa.

**5. Dashboardy Grafany nieprzejrzane.** Sprawdzono wyłącznie listę reguł w
`shared/observability/alert-rules.yml` (trzy: TargetDown, Http5xxBurst, HostMemoryHigh). Nie wiadomo,
czy istnieje panel dla offboardingu.

**6. Brak pomiarów przy realistycznym wolumenie.** Baza ma ~112 memów, ~160 komentarzy, 54 głosy.
Pokazano plany zapytań (już dziś seq scany), ale nie `EXPLAIN ANALYZE` na skali. S9, N3 i N22 pozostają
wadami projektowymi widocznymi w kodzie i schemacie, nie dzisiejszymi awariami.

**7. Żadne UI nie było uruchomione w przeglądarce.** Znaleziska S19, N27–N29 są oparte na przeczytanym
kodzie (`request` w `api.ts:117` woła `fetch` bez try/catch, więc błąd sieci propaguje jako odrzucenie).
Uznane za pewne, ale nieodtworzone w runtime — celowo nie zatrzymywano kontenerów, żeby nie zmieniać stanu
działającego stacku. Nie sprawdzono CSS, responsywności ani dostępności poza `alt`/`aria-label`.

**8. Podejrzenie, którego nie zmierzono i którego dlatego nie zgłoszono:** `file.getBytes()` alokuje do
10 MB **poza** semaforem dekodowania (rate-limit jest sprawdzany dopiero po sparsowaniu multipartu), więc
arytmetyka w komentarzach `memes.yaml` może nie pokrywać wielu równoległych uploadów. Test OOM wywróciłby
działający stack.

**9. Obserwacja poza zakresem, do zbadania osobno:** detekcja ponownego użycia refresh tokenu w security
**nie jest odporna na wyścig** — przy dwóch naprawdę równoczesnych `POST /refresh` (delay 0) obie odpowiedzi
to 200, czyli sesja rozwidla się na dwa łańcuchy; przy 50 ms opóźnienia druga daje 401 `ReuseDetected`
i unieważnia świeżo zrotowany łańcuch. To znalezisko **backendowe**, zauważone przy okazji przeglądu UI,
niedoprowadzone do końca. Nie jest liczone do statystyki.

**10. Ślad po przeglądzie na stacku dev:** konto testowe `audit1785085299@example.com` (zweryfikowane),
dwa memy wgrane w trakcie testów i kilka linii ERROR w logach memes oraz w Loki (te celowo — są dowodem
w W2). Nie kasowano ich, żeby nie odpalać kolejnych sag. **Żadnego pliku w repozytoriach nie zmodyfikowano.**

---

## 6. Obalone w weryfikacji — do samodzielnego rozstrzygnięcia

**Czytaj tę listę jak opinię, nie jak werdykt.** Wyrywkowa kontrola dwóch pozycji z tej listy wykazała,
że w **obu przypadkach mylił się weryfikator, a nie znalazca**:

- *„ciasteczko refresh jest Secure także poza testami"* (O7/O16/O23) — weryfikatorzy odrzucili to,
  twierdząc, że `Secure` jest wyłączane poza produkcją. Kontrola: `security.cookie.secure` ma default
  `true`, `SECURITY_COOKIE_SECURE=false` występuje **wyłącznie** w `security-ui/run-e2e.sh` — ani compose,
  ani k8s tego nie ustawiają — a zmierzony nagłówek na żywym stacku to
  `Set-Cookie: refresh_token; Secure; HTTPOnly; SameSite=Strict`.
- *„DELETE nieistniejącego wpisu w collections oddaje 404"* (O22) — potwierdzone w kodzie:
  `CollectionsApi.java:85` to `status == REMOVED ? NO_CONTENT_204 : NOT_FOUND_404`.

Dwa trafienia na dwie próby. Poniższe 23 pozycje są więc materiałem do decyzji, nie śmietnikiem.
Trzy z nich **wprost kolidują** z pozycjami potwierdzonymi w sekcji 3 — te oznaczam ⚠.

**O1. Wyszukiwanie po tagu przecina pełną listę id w JVM zamiast JOIN-em** — `SearchMemesByTag.java`
*Powód odrzucenia:* zamierzony, jawnie opisany kompromis — komentarz 25-26 nazywa go po imieniu wraz
z przyczyną i mitygacją. *Uwaga: zachowane jako część N22, bo pozycja z innego obiektywu przeszła.*

**O2. `DeleteThread` kasuje głosy komentarz po komentarzu i ładuje całą treść wątku** — `DeleteThread.java:33-39`
*Powód:* zarzut jest wprost zaadresowany w `V3__comment_votes_fk.sql` („the application's purge-then-delete
STAYS, but the invariant no longer depends on it") — kaskada jest pasem, jawny purge szelkami.

**O3. Po rebalansie budżet retry jest już przeterminowany — purge leci do kosza bez próby** — `SagaRetryBudget.java`
*Powód:* nagłówek „bez ani jednej próby" nieprawdziwy (rekord JEST ponownie dostarczony i listener JEST
wywołany), a wall-clock deadline to dokładnie deklarowany kontrakt klasy („a wall-clock DEADLINE, not
a number of attempts"). Każda re-komenda sagi to nowy offset, czyli pełne 90 s budżetu.
*Do rozstrzygnięcia: mechanizm reużycia `BackOffExecution` po rebalansie weryfikator potwierdził w kodzie
frameworka — sporna jest wyłącznie ocena, czy to defekt.*

**O4. Puls lampki bije tylko przy bezczynności — zajęty listener czyta się jako martwy** — `SagaListenersHealth.java`
*Powód:* `application.properties:85-98` opisuje idle events jako heartbeat wprost i wyprowadza tolerancję
150 s **dokładnie** z tego, że retry śpią na wątku konsumenta (30+90+30). Przypadek zajętej pętli stoi
na wymyślonej liczbie 0,3 s/rekord. *Weryfikator dorzucił własną uwagę, której nikt nie zgłosił: tolerancja
150 s równa się co do sekundy najgorszemu przypadkowi (porównanie jest `>`), czyli zero marginesu, podczas
gdy offboarding w analogicznym miejscu dokłada 25%. To może być warte jednej linii.*

**O5. `flooredStall` wywraca serwis zamiast przyciąć wartość** — `SagaParticipantConfig.java:201-216`
*Powód:* javadoc cytuje **dwa** elementy wzorca domu (`longEnv` **i** `flooredStall`), a pierwszy z nich
rzuca wyjątek. Zaniżona tolerancja to „lampka, która kłamie", więc fail-fast jest właściwy. Zostaje sama
nazwa metody — kosmetyka.

**O6. Obiecane „90 s" nie jest sufitem: sąsiednie klasy liczą 120 s i 150 s** — `SagaRetryBudget.java:49-51`
*Powód:* javadoc pisze „≈90s … ≈120s … either way ABOUT the orchestrator's own patience" — jawne
przybliżenie, a osobny akapit („The residual window, named rather than hidden") opisuje dokładnie to,
co znalezisko przedstawia jako obalenie sufitu.

**O7. Cookie refresh jest `Secure` bez wyjątku poza testami — po HTTP mechanizm odświeżania jest martwy** ⚠
— `RefreshCookies.java:14-19,28`
*Powód:* `Secure=true` jest postawą **poprawną**, wartość jest parametryzowana, a `*.localhost` jest
traktowane przez przeglądarki jako potentially-trustworthy. *Kontrola właściciela wykazała, że faktyczna
podstawa odrzucenia („wyłączane poza produkcją") jest nieprawdziwa — patrz nagłówek sekcji. Sam werdykt
„to nie defekt, poprawką jest TLS" może nadal być słuszny, ale uzasadnienie było błędne.*

**O8. Access token w `localStorage`** ⚠ — `memes-ui/src/App.tsx:29,137,140`
*Powód:* architektura, nie usterka — krótkożyjący bearer w warstwie strony + długożyjący sekret poza
zasięgiem JS to standardowy podział dla SPA bez BFF; trwałość jest udokumentowana w komentarzu.
*Kolizja: ta sama rzecz przeszła weryfikację w innym obiektywie i jest w sekcji 3 jako N25. Zostawiłem N25,
bo różnica dwóch UI tego samego produktu jest faktem, ale werdykt „to decyzja, nie błąd" uważam za lepiej
uzasadniony niż samo zgłoszenie.*

**O9. Efekt pobierający punktacje nie ma anulowania** — `App.tsx:81-88`
*Powód:* oba przebiegi pytają o te same liczby, wyzwalaczem w scenariuszu jest gwiazdka (nie zmienia
punktacji), a stan jest samoleczący przez `onVoted → refresh`. Strategia scalania jest opisana
(„Merged, never replaced").

**O10. Lista dostawców OAuth trafia do `.map` bez sprawdzenia kształtu** — `AuthPanel.tsx:40-45`
*Powód:* odpowiada własny serwis security z ustalonym kontraktem, degradacja jest zaprojektowana
(`.catch(() => setProviders([]))`), a sam autor zgłoszenia napisał „to ryzyko konfiguracyjne, nie aktualny
błąd".

**O11. `collections-ui` nie ma `noUncheckedIndexedAccess`** — `collections-ui/tsconfig.json`
*Powód:* różnica ustawień kompilatora, oba miejsca użycia bronione ręcznie (optional chaining + prop
typowany `Resolution | undefined`), a scenariusz awarii dotyczy kodu, który nie istnieje.

**O12. Ingress bez TLS i przypięty do `*.portal.localhost`** — `k8s/base/ingress.yaml:16-56`
*Powód:* `k8s/README.md:1` — „dev-parity manifests … validated on a local k3d cluster";
`go-live-2026.md` pkt 4 ma TLS + Let's Encrypt jako spisane zadanie Stage 1. Awaria byłaby głośna
(404 na wszystkim przy pierwszym teście), nie cicha. *To najpoważniejsza pozycja na tej liście — warta
osobnego spojrzenia, jeśli hosting zbliża się szybciej, niż zakładano.*

**O13. CORS zabetonowany na localhost we wszystkich trzech serwisach** — `application.yml:19`, `CommentsConfig`, `CorsFilter`
*Powód:* „zabetonowany" upada w dwóch trzecich — collections czyta `COLLECTIONS_ALLOWED_ORIGINS`, comments
czyta `UI_ORIGIN`, security da się nadpisać standardowym mapowaniem env Micronauta. `go-live-2026.md` pkt 2
nazywa CORS jako element overlaya produkcyjnego.
*Uwaga: jednoorginowość comments przeszła jako S12 — tam zarzut jest inny (String vs lista), więc te dwie
pozycje się nie wykluczają.*

**O14. Adresy API wypieczone w bundlach obu UI, a `user-collections` nie ma trasy w Ingressie**
— `memes-ui/src/api.ts:3-8`, `k8s/base/ingress.yaml`
*Powód:* trzykrotnie opisany zakres — `k8s/README.md` sekcja „Deliberately missing vs compose"
(„a k8s-native UI build … is a follow-up. API-level flows through the Ingress are fully functional")
i nagłówek `collections-ui.yaml:1-7`. Autor zgłoszenia sam cytował README jako dowód.

**O15. Jedyny overlay to `dev`: sekrety literałem w gicie i `imagePullPolicy: Never`**
— `k8s/overlays/dev/kustomization.yaml:28-52`
*Powód:* plik tłumaczy się w nagłówku („That is fine for a throwaway local cluster and NOTHING ELSE —
production uses SealedSecrets or ExternalSecrets, never literals in git"). Hasła to publicznie znane
wartości domyślne compose'a.

**O16. Cookie refresh `Secure` vs Ingress po HTTP** ⚠ — `RefreshCookies.java:28`
*Powód:* znalezisko odwrócone — kod robi rzecz poprawną, a scenariusz wymaga najpierw zrealizowania O12
w najgorszy możliwy sposób. *Patrz zastrzeżenie przy O7.*

**O17. Kafka na `emptyDir` — reschedule poda kasuje topiki i offsety** — `k8s/base/kafka.yaml:63-90`
*Powód:* decyzja jawnie podjęta w tym samym pliku (linie 9-11: „losing broker state on reschedule is
acceptable for a dev cluster"). *Weryfikator przyznał, że uszczegółowienie autora jest technicznie słuszne —
zdarzenia potwierdzone przez brokera, ale jeszcze nieskonsumowane, giną — i że PVC w overlayu hosted to dwie
linijki. Warte zapisania na liście hosted.*

**O18. Brak limitu na miejsce: local-path nie egzekwuje rozmiaru PVC** — `k8s/base/minio.yaml:3-12`
*Powód:* znana właściwość rancherowskiego local-path, nie defekt manifestu; scenariusz wymaga złośliwego,
zweryfikowanego mailem konta atakującego hosting, który nie istnieje. Przeciw działają limit uploadu
per user, dedup i moderacja.

**O19. Metryki Prometheusa anonimowo wystawione przez Ingress na trzech hostach** ⚠
— `memes/application.properties`, security `application.yml:31-34`
*Powód:* słowo „publicznych" fałszywe, bo `*.portal.localhost` rozwiązuje się na 127.0.0.1; ekspozycja
jest świadoma i opisana („metrics carry no secrets"). ⚠ **Kolizja: dwa inne obiektywy potwierdziły to samo
zjawisko i oba przeszły weryfikację (S1), z pomiarem `200` anonimowo i z cytatem z `ingress.yaml`.
Zostawiłem S1 — argument „dziś nikt z zewnątrz tego nie dosięgnie" jest prawdziwy tylko dopóki nie ma
hostingu, a naprawa (osobny port) kosztuje jedną linię.**

**O20. Zero backupów przy `reclaimPolicy: Delete` na pięciu PVC** — `k8s/base/*-postgres.yaml`
*Powód:* klaster jest z definicji jednorazowy (`k3d cluster delete portal-dev` jako normalne zakończenie
pracy), a `pg_dump cron` jest już spisany w `go-live-2026.md` pkt 4.

**O21. `infra-up.sh` na publicznej maszynie = Mailpit, Grafana z anonimowym adminem i stub IdP**
— `docker-compose.yml:23-25` i pliki include
*Powód:* samo znalezisko jest warunkowe („JEŚLI na VM pójdzie infra-up.sh"), a `go-live-2026.md` wyprzedza
dwa z trzech wektorów imiennie (pkt 3: „The stub IdP must not be public — that is a sign-in bypass";
pkt 5: `GRAFANA_ANON=false`). Bindowanie dev-compose'a na 0.0.0.0 to norma Dockera.
*Weryfikator odnotował jednak, że `HOSTING-K3S.md` wlicza Prometheusa/Grafanę/Tempo/Loki do budżetu RAM,
więc dostawienie observability compose'em na tej samej VM jest realnym następnym krokiem.*

**O22. DELETE nieistniejącego wpisu w collections oddaje 404** ⚠ — `CollectionsApi.java:85`
*Powód:* 404 na DELETE nieobecnego zasobu jest zgodne z RFC 9110 i nie łamie idempotencji, kontrakt jest
spisany w javadocu klasy, a objaw po stronie UI już nie istnieje (oba klienty traktują 404 jako sukces,
z komentarzami w czasie przeszłym). ⚠ **Kontrola właściciela potwierdziła kod. Do rozstrzygnięcia zostaje
sama preferencja kontraktowa — czy DELETE ma zawsze oddawać 204.**

**O23. Cookie refresh jest `Secure` także w profilu dev, a wie o tym tylko skrypt e2e cudzego UI** ⚠
— `RefreshCookies.java:28`
*Powód:* javadoc dokumentuje i default, i furtkę; `security-ui/run-e2e.sh` podaje
`SECURITY_COOKIE_SECURE=false` razem z `KAFKA_ENABLED=false` z komentarzem wyjaśniającym powód — to
udokumentowany override dla spakowanego jara, nie odkryta pułapka. *Patrz zastrzeżenie przy O7:
faktografia zgłaszającego okazała się dokładniejsza niż weryfikatora.*

---

*W weryfikacji potwierdzono 65 znalezisk (57 pozycji po scaleniu duplikatów) i obalono 23 — z czego
wyrywkowa kontrola dwóch obalonych wykazała błąd po stronie weryfikatora w obu przypadkach.*
