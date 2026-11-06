# Playbook dla Opusa — microservice-memes

Spisany 2026-07-07 (sesja Fable). Zadania od góry; jedno zadanie = jedna sesja.
Stan repo jest DALEJ niż todo.md — zrobione, choć nieodnotowane: NSFW/moderacja
(commit `7ece5d3`), offline JWT gate (`3875410`), logowanie Google w galerii (`ef60d6d`),
MFA/step-up/recovery w galerii (`90baddb`…`c18bb3d`). Zadanie M0 sprząta ten dług.

## Zasady pracy w tym repo

- **Moduły**: `memes-domain` (czysta domena), `memes-config` (stroiki framework-free:
  `RateLimit`, `PurgeRule`, `TagLimits`), `memes-image` (optymalizacja), `memes-application`
  (use case'y + porty, np. `ObjectStore`, `ContentFlags`), `memes-infrastructure`
  (Spring Boot: REST/JDBC/Kafka/beany), `memes-ui` (React+TS+MUI, Vite przez
  frontend-maven-plugin, dist w jarze).
- **Testy**: unit per moduł; MockMvc + cucumber (`memes-infrastructure/src/test/resources/
  features/*.feature`) + Allure. Bez DB_URL jedzie H2 w trybie PostgreSQL na TYCH SAMYCH
  adapterach JDBC — nie pisz drugiej implementacji repozytorium.
- **Build**: `../mvnw -q -pl memes-infrastructure -am package` z korzenia repo
  (wrapper w korzeniu workspace) albo pełne `../mvnw test`. Smoke stacku:
  `cd .. && ./infra-smoke.sh`.
- **Autoryzacja**: `RequireSignInFilter` + `SecurityAuthenticationGate` (dwa adaptery:
  `HttpSecurityAuthenticationGate` woła `/me`, `JwtSecurityAuthenticationGate` weryfikuje
  offline po JWKS). Tożsamość i role = `Caller{email,roles}`. Nowe akcje moderacyjne
  bramkuj rolami z Callera, nigdy polem z requesta.
- **Commit**: angielska jednolinijkowa obrazowa wiadomość + stopka Co-Authored-By.
  Javadoc/komentarze po angielsku.

---

## M0. Uzgodnij todo.md ze stanem repo (mały, zrób pierwszy)

W `todo.md`:
1. Przenieś do „Zrobione" z krótkim opisem i hashem: **NSFW/moderacja** (`7ece5d3` —
   `FlagMeme`/`ContentFlags`/`JdbcContentFlags`, `moderate-meme.feature`, blur w galerii),
   **offline JWT** (`3875410` — `JwtSecurityAuthenticationGate`), **Google w galerii**
   (`ef60d6d`), **MFA/step-up/recovery w galerii** (`90baddb`…`c18bb3d`).
2. Skreśl z „Otwarte — najbliższe" wpis o fladze NSFW (już nie czeka na RBAC).
3. Dodaj linijkę: `Plan pracy: docs/opus-playbook.md`.
Commit sam w sobie (nie mieszaj z kodem).

---

## M1. Default polityki czystki nadpisywalny w bazie + panel admina

**Cel:** dziś default osi purge (`memes.purge.memes` / `PURGE_MEMES_POLICY` w
`application.properties`) jest zamrożony na deploy. Ma być: default z env jak dotąd,
ale ADMIN może go nadpisać W BAZIE bez restartu; wybór usera z wizarda (per żądanie)
dalej wygrywa nad wszystkim.

**Stan zastany:** `PurgeRule` (memes-config, parser reguł DELETE|ANONYMIZE_AUTHOR|
KEEP_POPULAR_ANONYMIZED:n), `PurgeUserContent` (application) bierze politykę,
`PurgeCommandsListener` (infrastructure) odbiera komendę z Kafki, `MemesConfig`
składa beany. Hierarchia rozstrzygania: wizard > baza > env.

**Kroki:**
1. Port w application: `interface PurgePolicyOverride { Optional<PurgeRule> current(); void set(PurgeRule rule); void clear(); }`
   (nazwa osi w argumencie, jeśli chcesz jednym portem objąć memes i przyszłe osie —
   tu oś jest jedna: MEMES).
2. Migracja Flyway `V3__purge_policy_override.sql`: tabela `settings(key text pk,
   value text not null, updated_at timestamptz, updated_by text)` — celowo generyczna
   (następne ustawienia adminowe wchodzą bez migracji).
3. Adapter `JdbcPurgePolicyOverride` (infrastructure) + in-memory do testów MockMvc
   bez DB (trzymaj wzór: te same testy jadą na H2, więc JDBC wystarczy — sprawdź jak
   robią to inne adaptery i nie twórz in-memory bez potrzeby).
4. Rozstrzyganie w `PurgeUserContent`: żądanie z wizarda > `PurgePolicyOverride.current()`
   > default z configu. Test unit na wszystkie trzy szczeble.
5. REST (infrastructure): `GET /admin/purge-policy` (aktualny efektywny default +
   skąd pochodzi: ENV|DB) i `PUT /admin/purge-policy {"memes":"KEEP_POPULAR_ANONYMIZED:5"}`
   + `DELETE` (wraca do env). Brama: Caller musi mieć rolę ADMIN (wzór autoryzacji:
   moderacja w `MemeController`/`ModerationSteps`). Zły literał reguły → 400 z komunikatem
   parsera `PurgeRule`.
6. UI (memes-ui): w menu zalogowanego ADMIN-a pozycja „Admin"; prosty panel: aktualna
   polityka, select z regułami (+ pole n dla KEEP_POPULAR), Save/Reset-to-env.
   Wzór stylu: `DeleteAccountDialog.tsx`.
7. Testy: cucumber `admin-purge-policy.feature` (3 scenariusze: admin ustawia i purge
   respektuje; nie-admin 403; DELETE wraca do env) + unit szczebli z p.4.
8. `todo.md`: skreśl „Default polityki czystki z bazy".

**DoD:** purge po nadpisaniu w bazie zachowuje się wg nowej reguły bez restartu
(dowód: scenariusz cucumber woła use case po zmianie przez REST); smoke bez zmian zielony.

---

## M2. ObjectStore: trzeci adapter S3/MinIO

**Cel:** bajty memów w object storage. Seam już jest: port `ObjectStore`
(application, put/get/delete po id), adaptery `DbObjectStore` (default) i
`FilesystemObjectStore` (`memes.blob-store=filesystem`). Dokładasz trzeci o TYM SAMYM
kształcie — zero zmian w use case'ach.

**Kroki:**
1. Zależność: oficjalny `software.amazon.awssdk:s3` (tylko w memes-infrastructure).
   Wersję przypnij w dependencyManagement roota repo memes.
2. `S3ObjectStore implements ObjectStore` — klucze jak w filesystem (`{id}`, `{id}.webp`);
   konfig: `memes.blob-store=s3`, `memes.s3.endpoint` (MinIO!), `memes.s3.bucket`,
   `memes.s3.access-key/secret-key`, `memes.s3.path-style=true` (MinIO wymaga).
   Bucket tworzony przy starcie, jeśli brak (idempotentnie).
3. Bean w `MemesConfig` za przełącznikiem wartości `blob-store` (wzór: filesystem).
4. Testy: `S3ObjectStoreTest` na **Testcontainers MinIO** (`minio/minio`) — round-trip
   put/get/delete + brak obiektu → empty; test negocjacji WebP przechodzi bez zmian
   z podmienionym store (parametryzacja istniejącego testu NIE jest wymagana — wystarczy
   adapterowy round-trip, kontrakt portu jest wąski).
5. Workspace compose (`../docker-compose.yml`): serwis `minio` + env memes
   `MEMES_BLOB_STORE=s3`, `MEMES_S3_*`; krok w `../infra-smoke.sh`: upload mema →
   obiekt widoczny w bucket (mc ls albo GET mema po restarcie kontenera memes).
   UWAGA: to zmiana w repo WORKSPACE (osobny commit tam), nie w memes.
6. `todo.md`: skreśl „bajty do object storage".

**DoD:** galeria działa na MinIO w compose (upload/serwowanie/miniatura/WebP-cache),
restart memes nie gubi obrazków; testy Testcontainers zielone lokalnie.

**Pułapki:** nie czytaj całych bajtów do pamięci dwa razy (SDK bierze bytes — ok,
memy są małe po optymalizacji); `path-style` bez tego MinIO 400; nie loguj sekretów.

---

## M3. Dokumentacja jak w security (mały)

**Cel:** memes ma glosariusz skanujący warstwy; brakuje odpowiednika
`Documentation.md`/kontraktu cucumber jak w security. Zrób: skrypt/README sekcję,
która linkuje feature'y (`specs/`) jako
kontrakt zachowania + wygenerowany glosariusz; sprawdź `../create-documentation.sh`
(workspace) i podepnij memes, jeśli jeszcze nie jest. Wynik: `Documentation.md`
w korzeniu repo memes, regenerowalny, committowany.

---

## M-obserwacje (nie rób bez zgody usera)

- **Default bramy security**: dziś `HttpSecurityAuthenticationGate` (/me) vs
  `JwtSecurityAuthenticationGate` (offline) — sprawdź który jest domyślny i czy
  compose używa offline. Jeśli oba żyją bez przełącznika w README — udokumentuj
  przełącznik w README (drobny commit, zgody nie wymaga).
- **Nested comments / edycja komentarzy** — poza zakresem, komentarze żyją w
  microservice-comments.
