# TODO — microservice-memes

Only open items. History = git log.

**Plan pracy z instrukcjami wykonawczymi: [docs/opus-playbook.md](docs/opus-playbook.md)**
(2026-07-07; M0–M3 ZROBIONE — playbook memes wyczerpany). Galeria dostała też odsłonę
ukrywania komentarzy (C4 po stronie comments): przycisk oka moderatora + tombstone;
naprawiony bliźniaczy leak `nsfwIds` (`.stream()`→`.list()`).

## Zrobione (walking skeleton)
- Multi-module Spring Boot (domain / config / image / application / infrastructure).
- Upload obrazka → optymalizacja do PNG (BMP→PNG, ImageIO) → zapis → serwowanie.
- **Miniatury** — `GET /memes/{id}/thumbnail`, generowane na żądanie (`MakeThumbnail`).
- **Dedup po hashu** — SHA-256 bajtów po optymalizacji (`MemeContentIndex`); drugi upload tego
  samego obrazka zwraca istniejące id.
- **Komentarze** — `AddComment`/`ListComments` + REST + cucumber.
- **Głosowanie na memy** — `CastVote`/`RankMemes`, ranking `GET /memes/hot` + cucumber.
- Testy: unit (image/config/application), MockMvc, cucumber+Allure. Zielone na JDK 25 +
  Spring Boot 3.5.
- **Autoryzacja przez microservice-security** — odczyty publiczne, POST-y pod `/memes` wymagają
  Bearer tokena potwierdzanego przez `GET /me` security (`RequireSignInFilter` + brama HTTP;
  w testach stub). Autor komentarza = potwierdzona tożsamość, nie pole z requesta.
- **UI galerii** — moduł `memes-ui`: React + TypeScript + Material UI (Vite przez
  frontend-maven-plugin, dist w jarze jako `META-INF/resources`); logowanie/rejestracja/
  weryfikacja przez security (CORS), upload/komentarze/głosy po zalogowaniu.
- **Jeden głos na użytkownika** (mem i komentarz) — ponowny głos zastępuje poprzedni, nie
  kumuluje się; **głosowanie na komentarze** (`POST .../comments/{id}/votes`), listing komentarzy
  niesie score.

- **Saga usuwania konta, KONFIGUROWALNA** — `PurgeUserContent` na komendę `PURGE_USER_CONTENT`
  z Kafki; los treści to polityka wdrożeniowa (`ContentPurgePolicy` w memes-config, env
  `PURGE_MEMES_POLICY`/`PURGE_COMMENTS_POLICY`, osie DELETE|ANONYMIZE_AUTHOR). Domyślnie: memy
  znikają z całymi wątkami i głosami, komentarze gdzie indziej zostają jako „deleted account";
  głosy usera wycofywane zawsze. Potwierdzenie na `memes-events`. Memy mają autora (tożsamość
  z security przy uploadzie). Reguły per oś: DELETE | ANONYMIZE_AUTHOR |
  KEEP_POPULAR_ANONYMIZED:n; wybór usera z wizarda w UI nadpisuje default per żądanie.

- **Komentarze wydzielone do `microservice-comments`** (2026-07-02 wieczór): ten serwis trzyma
  memy i głosy na memy (lib `voting`); skasowany mem ogłasza `MEME_DELETED`, a serwis komentarzy
  kasuje wątek.

## Zrobione (2026-07-04..06 — moderacja, bramy, galeria×security; odnotowane 2026-07-07)
- **Moderacja + NSFW** — role z security (`Caller{email,roles}` przez bramę): MODERATOR/ADMIN
  kasuje cudze memy (`50557b7`, cucumber `f56cc81`, przyciski w galerii `8425e20`, autor swoje
  także z UI `5dcdf70`); **flaga NSFW moderatora rozmywa galerię** (`7ece5d3`: `FlagMeme`/
  `ContentFlags`/`JdbcContentFlags`, `moderate-meme.feature`, blur+odsłona w UI).
- **Offline JWT gate** (`3875410`): `JwtSecurityAuthenticationGate` weryfikuje access token
  po JWKS security (Ed25519) zamiast wołać `/me` — mniej ruchu; kompromis jak w security/todo
  (offline nie widzi logoutu do wygaśnięcia).
- **Galeria × security, pełny łańcuch** — logowanie z MFA (krok kodu `90baddb`), dokończenie
  OAuth wymagającego czynnika (`7c1b37e`), step-up przed delete (`404d9cb`), przyciski social
  z `GET /oauth/providers` (`a269c60`), hint recovery codes (`c18bb3d`), sign-in z Google
  (`ef60d6d`), cichy kontrakt rejestracji (`d9ad4b7`).

## E2E galerii (2026-07-20, na życzenie właściciela: „w memach brakuje e2e")

Suita przeglądarkowa BYŁA (5 scenariuszy z 2026-07-07: przeglądanie, logowanie hasłem,
upload, głos, komentarz + 3 ulubione), ale pokrywała jedne drzwi. Dołożone dwa pliki
Gherkina, wszystko przez REALNY panel w realnej przeglądarce:

- `identity.feature` — założenie konta z panelu i wejście z mailowego linku, złe hasło,
  konto niezweryfikowane odesłane do skrzynki, **drugi czynnik (krok kodu)** i **kod
  odzyskiwania zamiast mailowego**. Jedyna furtka testowa to skrzynka security (przeglądarka
  nie czyta maili).
- `account-deletion.feature` — danger zone: kreator polityki treści, step-up hasłem, konto
  NAPRAWDĘ znika (nie wpuszcza z powrotem), złe hasło nic nie kasuje, „Keep my account"
  zostawia wszystko, a konto z czynnikiem dostaje krok kodu także na wyjściu.
  Scenariusze idą PEŁNYM łańcuchem: usunięcie konta wyjeżdża z security outboxem na Kafkę,
  offboarding rozkazuje czystkę, memes/comments/collections kasują i potwierdzają, a test
  sprawdza NA SERWISIE, że spalony mem zwraca 404 i że komentarz po „zalecanej" polityce
  stoi podpisany „deleted account".

**HARNESS = PRAWDZIWY STACK (poprawka po werdykcie właściciela 2026-07-20: „chujowo robić
e2e bez jakiegoś członu")**: `run-e2e.sh` nie stawia już czterech jarów z pamięciowymi
sklepami — podnosi (albo zastaje) `docker compose` portalu i prowadzi scenariusze po nim.
Powód techniczny, wart zapamiętania: środowisko `test` security NIE MA publikacji outboxu
(`@Requires(notEnv = "test")`), więc w starym harnessie nic nigdy nie wychodziło na broker,
a usunięte konto tylko się blokowało — test przechodził, dowodząc niczego. Maile czyta się
teraz ze skrzynki stacku (Mailpit API), czyli tak, jak czyta je człowiek: zero furtek
testowych. GOTCHA przy debugowaniu: `mvn package` BEZ `clean` potrafi zostawić stary fat jar
(jar:jar pomija, repackage podkłada zastane archiwum) — obraz memes serwował wtedy UI sprzed
naprawy; `clean package` załatwia sprawę.

**ZŁAPANY BUG PRODUKCYJNY**: `AuthPanel.signIn` sprawdzał `r.ok`, a to jest PRAWDA dla 202 —
gałąź drugiego czynnika w galerii była martwym kodem (konto z czynnikiem dostawało undefined
zamiast kroku kodu). Ta sama klasa błędu, którą security-ui złapało u siebie 2026-07-06;
galeria miała własną kopię i nikt jej nie prowadził przeglądarką. Naprawione (200 zamiast
`r.ok`, plus obsługa 202 w `submitFactor` dla łańcucha dłuższego niż jedno ogniwo).
18 scenariuszy e2e zielonych (dwa biegi pod rząd), `tsc` czysty, suita modułu 41 zielona.

## OCENA ZROZUMIAŁOŚCI PROJEKTU (2026-07-20, na prośbę właściciela) — i co z niej naprawiamy

Ocena trudności zrozumienia: **6/10 łącznie**, ale ta liczba jest myląca, bo trudność nie leży
tam, gdzie się jej szuka:

- **czytanie kodu: 3/10** — 2493 linie produkcyjnego Javy w 7 modułach, 2578 linii testów
  (1:1), największy plik produkcyjny 156 linii, hexagon prawdziwy, komentarze tłumaczą DLACZEGO,
  a dziewięć plików `.feature` jest kontraktem zachowania. **WERDYKT WŁAŚCICIELA: „bardziej się
  nie da"** — zgoda, 3 to praktycznie podłoga i NIC z tej listy nie celuje w kod.
- **bezpieczna zmiana zachowania: 6/10** — wymienne adaptery mnożą modele mentalne (trzy
  ObjectStore, dwie bramy autoryzacji, Kafka albo Noop, polityka czystki rozstrzygana kaskadą).
- **odpowiedź „co się dzieje, gdy user kasuje konto": 8/10** — i TO jest cel napraw: znaczenie
  tego zdania było rozłożone na cztery repozytoria i nie istniało nigdzie w całości.

SPROSTOWANIE do własnej oceny: zarzut o „podwójną pisownię konfiguracji" (`PURGE_MEMES_POLICY`
vs `memes.purge.memes`) był BŁĘDNY — to jednokierunkowe mapowanie ENV→property w
application.properties, czyli dokładnie dobra praktyka. Wycofane.

ZROBIONE tego samego dnia:
- **`docs/account-deletion-across-services.md`** — cała droga przez cztery serwisy: step-up,
  natychmiastowa BLOKADA (która NIE jest usunięciem), fakt w outboxie, offboarding jako proces
  sagi, potwierdzenia uczestników, kompensacja gdy któreś nie przyjdzie, plus tabela „gdzie co
  leży". README linkuje do niej z sekcji o usuwaniu konta.
- **README: „Which runtime am I actually in?"** — tabela osi wymiennych adapterów, bo bez wiedzy,
  w której konfiguracji się jest, nie da się przewidzieć zachowania.
- **Pułapki spisane** (wcześniej wiedza plemienna): środowisko `test` security bez publikacji
  outboxu; `mvn package` bez `clean` serwujący stary front; „pierwszy kafelek na ścianie" to nie
  twój mem; kody jednorazowe po obu stronach.

ZOSTAJE (kolejne kroki tej samej naprawy):
- **Diagram/strona na poziomie PORTALU, nie tylko memów** — dziś ta droga jest opisana w repo
  memów; siostrzane repa (comments, collections) mają ten sam problem i powinny linkować do
  jednej strony w workspace, zamiast każde do własnej kopii.
- **Trzy mechaniki „zniknięcia treści"** (delete / NSFW blur / tombstone) opisane razem w jednym
  miejscu, bo dziś czyta się je z trzech osobnych commitów.
- **Onboardingowy „pierwszy dzień"** dla portalu: minimalna ścieżka „postaw stack → zaloguj się →
  wrzuć mema → skasuj konto → zobacz, co zniknęło", z komendami. Dziś każdy składa to sam.

## Regresja po paczce 9: ściana czytała ranking jak słownik (2026-07-26)

**Objaw**: `/memes/hot` dostało twardy TOP-N (100), a `memes-ui` używało tej listy jako SŁOWNIKA
wyników kafelków (`scores[m.id] ?? 0`). Mem poza pierwszą setką pokazywałby „▲ 0" mimo realnych
głosów — cicha nieprawda w interfejsie, czekająca tylko na wzrost galerii (dziś 91 memów, 54 z
głosami, więc czapka jeszcze nie gryzie). Ta sama klasa błędu w drugą stronę: nieudane
`hotMemes()` (sieć/500) też dawało ścianę pewnych zer.

**Naprawa (wariant „wąski endpoint")**: `GET /memes/scores?ids=a,b,c` (use case `ShowMemeScores`,
porty `MemeRepository#existingOf` + `VoteRepository#scoresOf` — po jednym odczycie na stronę
kafelków, nie po jednym na kafelek). Kontrakt trzyma różnicę ZERO vs NIEZNANE: id, które wraca,
ma prawdziwy wynik (0 to fakt o memie bez głosów); id, które NIE wraca, nie ma wyniku do podania
(serwis nie ma takiego mema — np. ulubiony, który przeżył swój mem). W UI wynik to
`ReadonlyMap`, a nie `Record`: `get` zwraca `undefined`, `ScoreChip` renderuje wtedy „▲ n/a"
(jak dialog dla `score === null`), nigdy zera. Dodatkowo `noUncheckedIndexedAccess` w
`tsconfig.json` — indeksowanie, które może nie trafić, musi się do tego przyznać w typie, więc
`?? 0` nie wróci przypadkiem. `/memes/hot` zostaje jako RANKING (kolejność), bez konsumenta w UI.

**Pin**: `ShowMemeScoresTest` (m.in. `REGRESSION: the capped hot ranking cannot answer for a wall
that outgrew it` — TOP_N+1 zagłosowanych memów, ranking gubi część z nich, nowy odczyt nie),
`MemeScoresBatchTest` (kształt odpowiedzi: 0 obecne, nieznane id NIEOBECNE, duplikaty, sufit 100).

**Druga połowa regresji (e2e)**: `world.wallIds()` w `portal/e2e` — jedno miejsce, które pyta o
ŚCIANĘ jawną stroną (`?page=0&size=100`); używają go trzy kroki „gone from the gallery"
(account-deletion, participant-outage, deletion-cascade), bo wcześniej dwa z nich sprawdzały
tylko bezpośredni GET mema, mówiąc o „galerii". `memes-ui/e2e` też pyta jawnie o `page=0&size=50`.

## 26 memów-widm: wiersz jest, bajtów w AKTYWNYM store nie ma (2026-07-26)

**Objaw**: na żywym stacku 26 z 91 memów miało wiersz w `memes` i bajty w `meme_blobs` — tabeli
store'a DB, którego bean nawet nie powstaje przy `MEMES_BLOB_STORE=s3`. Galeria je listowała
(kafelek się renderował), a `/meta`, `/votes`, `/thumbnail`, GET obrazu i DELETE odpowiadały 404:
ani autor, ani moderator nie mógł takiego mema usunąć. Gorsza połowa jest o RODO — czystka konta
usuwała wiersz i prosiła S3 o klucz, którego tam nie było (no-op), więc obraz osoby zostawał
w `meme_blobs` bez ŻADNEJ ścieżki usunięcia, a serwis meldował sukces.

**Naprawa, dwie części (żadna nie wystarcza sama)**: (1) bramki istnienia przestały pytać
`find()`, które zawsze skleja wiersz z bajtami — `exists()` tam, gdzie pytanie brzmi „czy wiersz
istnieje" (ServeMeme, FlagMeme, CastVote, ShowMemeVote), i nowy port `MemeRepository#findMetadata`
(`MemeMetadata` = id/autor/format, bez bajtów) tam, gdzie potrzebny jest autor (DeleteMeme,
TagMeme, ViewMeme → `/meta` i DELETE). Bajty czytają WYŁĄCZNIE ServeMeme i MakeThumbnail, obie
po bramce `exists()` (kolejność z MakeThumbnail: tanie pytanie lokalne, potem drogie zdalne).
Efekt uboczny na gorącej ścieżce: otwarcie dialogu to jeden odczyt obrazu zamiast trzech, DELETE
zero zamiast dwóch, a trafienie w cache WebP nie ściąga już PNG-a na śmietnik.
(2) `OrphanedBlobMigration` (ApplicationRunner): gdy `blob-store != db`, a `meme_blobs` nie jest
puste, przenosi zawartość do AKTYWNEGO store'a i opróżnia tabelę. Idempotentnie (ADR 0006) i per
obiekt, bez otaczającej transakcji: klucza, który aktywny store już ma, NIE nadpisuje (prawdą jest
to, co w aktywnym store), a jeden obiekt, którego nie da się skopiować, jest logowany i pomijany —
jego wiersz zostaje na następny start i nie kosztuje serwisu startu.

**Uruchomione na żywym stacku (2026-07-26)**: 26/26 obiektów do MinIO (65 → 91 originals),
`meme_blobs` puste, wszystkie 26 dawnych widm odpowiadają `/meta` 200; dawne widmo
`58494daa` serwuje obraz i miniaturę, a DELETE przez NIE-autora dochodzi do autoryzacji
(403 NOT_YOURS zamiast 404). Restart bez logu migracji = no-op.

**Pin**: `MemeWithoutItsBytesTest` (mem bez bajtów: galeria listuje, `/meta` i `/votes` 200, obraz
i miniatura 404, autor USUWA, moderator flaguje i usuwa, obcy nadal 403, nieistniejący nadal 404),
`OrphanedBlobMigrationTest` (przenosi i opróżnia, idempotentna, przy `db` NIE RUSZA tabeli, jeden
błąd nie zabiera pozostałych ani startu, nie nadpisuje aktywnego store'a), `ServeMemeTest`
(trafienie w cache WebP nie czyta obrazu; wiersz bez bajtów serwuje 404).

## Skalowanie gubiło kanał alfa — TRWALE, bo optimize() re-enkoduje przy uploadzie (2026-07-26)

**Objaw**: `WebImageOptimizer.downscaleWithin` rysowało na buforze `TYPE_INT_RGB`, więc piksele
przezroczyste kompozytowały się na czarnym tle. Miniatura skaluje praktycznie każdy mem (próg
256 px), więc przezroczysta naklejka była na ścianie czarnym kafelkiem — ale sedno jest gorsze:
`optimize()` re-enkoduje bajty PRZY UPLOADZIE i serwis przechowuje WYNIK, więc dla każdego
przezroczystego PNG dłuższego niż 1024 px alfa ginęła bezpowrotnie w magazynie. Bug był
STRUKTURALNIE niewykrywalny: wszystkie 15 fikstur obrazkowych w repo to `TYPE_INT_RGB`, więc suita
nie potrafiła wyprodukować wejścia z alfą.

**Naprawa**: bufor `TYPE_INT_ARGB`, gdy źródło ma kanał alfa, `TYPE_INT_RGB`, gdy nie ma —
wyjściem jest PNG (nosi alfę), a spłaszczanie zostaje dla źródeł nieprzezroczystych (JPEG), żeby
kanał samych `0xff` nie puchł w magazynie.

**Pin**: `WebImageOptimizerTest` — fikstura ARGB z przezroczystą ćwiartką (cała ćwiartka, nie jeden
piksel: skaler interpoluje) i asercje `getRGB >>> 24 == 0` po skalowaniu na ścieżce UPLOADU
(1600→1024) i miniatury (600→256), plus „opaque source stays flattened to RGB";
`MemeControllerTest` pinuje cały łańcuch (upload → magazyn → serwowanie). Sprawdzone też na żywym
stacku: 1600×1200 RGBA wgrane przez API wraca jako 1024×768 colourType=6 z przezroczystą ćwiartką.

## Otwarte
- **Kompensacja sagi offboardingu (ADR 0007) — WDROŻONE 2026-08-08.** Komenda czyszczenia
  **oznacza** treści (`PENDING_ERASURE` + `markedForErasureAt`), kasuje dopiero
  `ERASE_USER_CONTENT`, a `RESTORE_USER_CONTENT` cofa oznaczenie. Filtr `ACTIVE` jest w jednym
  miejscu — w widoku bazodanowym — a strażnik źródeł wywala build, gdy jakikolwiek SQL poza
  adapterem wymazywania nazwie tabelę bazową. Otwarte:
  - ~~Alarm zaległości nie ma reguły w Prometheusie~~ — ZROBIONE 2026-08-08:
    reguła `ErasureBacklogStuck` w `../../shared/observability/alert-rules.yml`
    (jedna na trzy serwisy, dopasowanie po sufiksie metryki; `memes_erasure_backlog`).
    **Zostaje**: reguły żyją tylko w stosie compose — wdrożenie k3s nie ma Prometheusa
    (zapisane w `../k8s/README.md` jako dług przyszłego overlaya observability).
  - (opc.) `pendingSince` używa tylko `StuckErasureWatch`; gdyby kiedyś przyszła polityka
    retencji, to jest miejsce, w którym się ją dopina — ale **nigdy** jako kasowanie z upływu czasu.
 — najbliższe (małe moduły, "à la security")
- ~~Tagi + wyszukiwanie~~ — ZROBIONE (2026-07-04): moduł `memes-tags` (VO `Tag`: normalizacja
  lowercase/trim, 2..30 znaków [a-z0-9-], pojedyncze myślniki), use case'y `TagMeme` (autor
  kuratorem — REPLACE całego zestawu, limit `TagLimits` z env `memes.tags.max-per-meme:8`,
  403 NOT_THE_AUTHOR) i `SearchMemesByTag` (galeria zawężona tagiem, porządek galerii);
  REST: POST/GET `/memes/{id}/tags`, `GET /memes?tag=`; purge czyści indeks tagów;
  3 scenariusze w tag-meme.feature. UI ZROBIONE (2026-07-04): czipy tagów w dialogu (klik =
  filtr galerii), edytor "tags, comma-separated" dla zalogowanych (backend autorytetem —
  odmowa NOT_THE_AUTHOR/INVALID_TAG jako komunikat), pasek aktywnego filtra z krzyżykiem.
- ~~Ranking hot z czasem~~ — ZROBIONE (2026-07-04): hotness = score/(ageHours+2)^1.5
  (Reddit-like), port `PublicationLog` (store zna czas publikacji; nieznany mem = świeży,
  fail-safe), zwracany score bez zmian — decay tylko porządkuje; GET /memes/hot bez zmiany
  kontraktu. Zegar przez java.time.Clock (bean).
- ~~EXIF~~ — ZROBIONE (2026-07-04): jawny pin — spreparowany JPEG z segmentem APP1 Exif
  ("SecretGPSLocation…") wchodzi, wychodzi PNG bez śladu metadanych.
- ~~Rate-limit uploadu~~ — ZROBIONE (2026-07-04): `RateLimit` w memes-config (per-uploader, env MEMES_UPLOAD_RATE_LIMIT, default 12/min, 0 wyłącza), 429+Retry-After w POST /memes; unit pin + MockMvc z limitem 1/min.
- ~~Flaga NSFW / moderacja~~ — ZROBIONE (2026-07-05, patrz sekcja wyżej): RBAC w security
  odblokował temat, moderator flaguje, galeria rozmywa.
- ~~Dedup pod współbieżnością~~ — ZROBIONE (2026-07-04): port `MemeContentIndex` to teraz
  atomowy `claim(data, candidateId)` (putIfAbsent) — przy dwóch równoczesnych uploadach wygrywa
  dokładnie jeden id i nic osieroconego nie jest zapisywane (save dopiero PO wygranym claimie);
  pin: test z dwoma wątkami na jednej bramce.

## Otwarte — infra
- **Cache miniatur a RODO — świadomy kompromis (2026-07-25).** `GET /memes/{id}/thumbnail`
  odpowiada `Cache-Control: public, max-age=3600`, więc **delete/purge NIE dosięga kopii już
  wydanych**: miniatura skasowanego mema może żyć w przeglądarkach i w cache'ach pośredniczących
  (CDN, proxy firmowe) jeszcze do godziny po tym, jak serwer o niej zapomniał. Obowiązek
  usunięcia realizujemy u ŹRÓDŁA (wiersz, blob i oba warianty giną w transakcji delete'u; od
  2026-07-25 dodatkowo bramka `exists` na trafieniu w cache — sierota po crashu nie jest
  serwowana), a to, co zostaje, to ogon ograniczony i wygasający. Gdyby czystka miała być
  twardsza: `private` odcina cache współdzielone, `no-store` daje natychmiastowość kosztem
  round-tripu na każdy kafelek galerii. Polityka mieszka w `MemeController#thumbnail` —
  tam komentarz z tym samym rozumowaniem.
- **Sierota AT REST — drugi, ostrzejszy brzeg tego samego obowiązku (2026-07-25).** Nota
  o `Cache-Control` mówi o kopiach JUŻ WYDANYCH; osobnym ryzykiem jest wariant, który został
  W MINIO. Samo-uzdrawianie w `MakeThumbnail` jest **wyzwalane żądaniem**: sierota (`{id}.thumb`
  po crashu między zapisem cache'u a jego re-checkiem) jest wykrywana dopiero wtedy, gdy ktoś
  o nią poprosi. O sierotę, o którą nikt już nie zapyta, nikt nie zapyta — leży w buckecie
  bezterminowo. Kod gwarantuje, że **nie zostanie PODANA**, nie że **zostanie USUNIĘTA**, a to
  są dwa różne obowiązki RODO (art. 17 mówi o usunięciu, nie o nieudostępnianiu). Wybór
  świadomy: domknięcie wymaga cyklicznego sweepu, który **ENUMERUJE bucket** (lista wszystkich
  kluczy vs. wiersze) — przegląd całego magazynu przeciwko oknu wielkości „JVM padł dokładnie
  w tej milisekundzie". WARUNEK ZMIANY DECYZJI: pierwszy realny crash, który zostawi sieroty,
  audyt żądający dowodu usunięcia, albo store na tyle duży, że zabłąkane obiekty kosztują.
  Ścieżka normalnego delete'u jest czysta (wiersz + blob + oba warianty w jednej transakcji) —
  to jest wyłącznie o oknie crashowym.
- **Sufit N×5 s w `PurgeUserContent` — dług DO SPISANIA, nie do naprawy dziś (2026-07-25).**
  `memeRepository.findIdsByAuthor(author)` nie ma limitu, a każdy skasowany mem ogłasza
  `MEME_DELETED`. Pierwsza próba publikacji jest nieblokująca, ale `send()` czeka do
  `max.block.ms` (5 s) na metadane — więc przy topicu BEZ LIDERA konto z N memami trzyma wątek
  listenera N×5 s (300 memów = 1500 s). Nad tym wisi `max.poll.interval.ms` = 300 s: powyżej
  ~60 memów rebalans, czyli awaria brokera zamienia się w awarię sagi. DZIŚ NIEOSIĄGALNE —
  Kafka na stacku jest jednobrokerowa, więc „brak lidera" oznacza „brokera nie ma wcale",
  a wtedy `send()` pada od razu, nie po 5 s. WARUNEK: **przed skalowaniem Kafki >1 brokera
  dodać cap** (limit na `findIdsByAuthor` + kontynuacja w kolejnym przebiegu, albo circuit
  breaker po pierwszym timeout'cie publikacji — reszta i tak dojdzie outboxem, bo wiersze
  zostają). ROZJAZD BUDŻETÓW, wart zapamiętania przy tej samej okazji:
  `OFFBOARDING_PURGE_TIMEOUT_SEC` = 120 s (proces sagi przestaje czekać na potwierdzenie), a
  budżet listenera memes to 300 s — memes może więc mielić jeszcze 3 minuty po tym, jak
  offboarding uznał uczestnika za spóźnionego. Idempotencja to ratuje (ponowiona komenda nie
  psuje), ale liczby powinny zejść się w jednej tabeli zegarów.
- ~~Outbox dla MEME_DELETED~~ — ZROBIONE (2026-07-25): tabela `meme_events_outbox` (V5),
  wpis w TEJ SAMEJ transakcji co teardown, after-commit wysyła i markuje `published` dopiero
  po POTWIERDZONYM doręczeniu, `MemeEventsOutboxRepublisher` (co 15s) dosyła niezmarkowane
  starsze niż 30s; eventId = klucz wiersza (duplikat rozpoznawalny, comments idempotentne).
  DOMKNIĘTE (2026-07-25): zegary producenta JAWNE — `max.block.ms=5000` (default Kafki to 60s,
  a `send()` blokuje wątek ogłaszający na fetchu metadanych; przy purge'u na wątku listenera
  N×60s wysadziłoby `max.poll.interval.ms`), `delivery.timeout.ms=30000`,
  `request.timeout.ms=15000` — te same co w offboardingu i collections, pin
  `KafkaProducerClocksTest`. Retencja kasuje batchami po 500, max 4 batche na pass (pierwszy
  pass na dużej tabeli nie jest jedną wielką transakcją na wątku schedulera);
  `memes.outbox.retention-hours` ≤ 0 = odmowa startu z nazwą i wartością.
  PRZENIESIONE DO JĄDRA (2026-07-26, paczka 10): `MemeEventsOutbox` i
  `MemeEventsOutboxRepublisher` **usunięte** — mechanizm to teraz biblioteka
  `com.jrobertgardzinski:transactional-outbox` + adapter `infrastructure-spring-outbox`
  (`../../shared/`), wyciągnięta Z TEJ implementacji, więc gwarancja jest tym samym kodem, a nie
  kopią o tym samym kształcie. Tabela i migracje V5/V6 BEZ ZMIAN (nazwa tabeli jest parametrem
  biblioteki i kształt zgadza się kolumna w kolumnę, indeks włącznie). Po stronie serwisu zostały
  trzy rzeczy, których biblioteka nie może zrobić: `KafkaMemeEvents` (nazwa topicu + payload
  wokół `OutboxEvent.newId()`), `KafkaMemeDispatch` (wysyłka + oba punkty kontraktu `Dispatch`)
  i `MemeOutboxConfig` (beany, `@EnableScheduling`, nazwa property retencji). Wszystkie 161
  testów przeszło bez zmiany ani jednej asercji — to był cały dowód, że abstrakcja nic nie zjadła.
- ~~Default polityki czystki z bazy~~ — ZROBIONE (2026-07-07): port `PurgePolicyOverride`
  + generyczna tabela `settings` (V4, klucz `purge.memes`), rozstrzyganie wizard > baza > env
  w `PurgeUserContent`; REST `GET/PUT/DELETE /admin/purge-policy` (filtr wymaga zalogowania
  na całym `/admin/**`, kontroler roli ADMIN — 403 NOT_AN_ADMIN); `PurgeRule.asText()`
  (odwrotność parse, round-trip w teście); panel „Admin" w galerii (dial + reset do env).
  Testy: 2 nowe unit w PurgeUserContentTest + `admin-purge-policy.feature` (3 scenariusze).
- **Realna persystencja** — ZROBIONA W RDZENIU (2026-07-04): metadane I bajty w bazie —
  Postgres na stacku (DB_URL), bez DB_URL in-memory H2 w trybie PostgreSQL (dev/testy jeżdżą
  na TYCH SAMYCH adapterach JDBC co produkcja, zero drugiej implementacji); Flyway V1 (memes/
  content_index/meme_tags/meme_votes), claim dedupu = unikalność PK w bazie (wyścig rozstrzyga
  constraint), upserty delete+insert jak w comments. Zweryfikowane live na PG: Flyway, galeria,
  filtr tagiem i bajty obrazka przeżywają restart aplikacji. ZOSTAJE: bajty do object storage
  (S3/MinIO) zamiast bytea, gdy galeria urośnie. SEAM ZROBIONY (2026-07-04): bajty wyjęte
  z wiersza mema za port `ObjectStore` (put/get/delete po id); JdbcMemeRepository trzyma tylko
  metadane i deleguje bajty (zapis/odczyt/kasowanie razem, transakcyjnie). Migracja V2 przenosi
  bajty do `meme_blobs` i usuwa kolumnę `data`. Dwa adaptery: DB-blob (default, bez nowych
  zależności) i FILESYSTEM (`memes.blob-store=filesystem`, `memes.blob-dir`). Zweryfikowane
  na PG (schemat V2) + testy (MockMvc round-trip, FilesystemObjectStoreTest z ochroną przed
  path-traversal). TRZECI ADAPTER S3/MinIO — ZROBIONY (2026-07-07): `S3ObjectStore`
  (awssdk s3, `memes.blob-store=s3`, path-style dla MinIO, bucket tworzony idempotentnie
  na starcie), round-trip na ŻYWYM MinIO (Testcontainers, skip bez dockera; gotcha
  Docker≥29 → `~/.docker-java.properties` w README), MinIO w compose workspace'u + krok
  smoke (obiekt mema widoczny w bucket). PRZY OKAZJI NAPRAWIONY BUG doboru adaptera:
  DbObjectStore był bezwarunkowo `@Primary`, więc `memes.blob-store=filesystem` niczego
  nie przełączał — teraz dokładnie jeden bean per tryb, pin `BlobStoreSelectionTest`.
- ~~WebP~~ — ZROBIONE (2026-07-04): OSOBNY MIKROSERWIS `microservice-image` (Python + Pillow,
  bezframeworkowy jak race-sim: POST /encode?format=webp&quality=, /health). memes: port
  `ImageEncoder` + adapter HTTP (`HttpImageEncoder`, pusty URL/awaria = empty), use case
  `ServeMeme` negocjuje po `Accept: image/webp` — WebP kodowany RAZ i cache'owany w ObjectStore
  pod kluczem {id}.webp, inaczej PNG (enkoder padł = degradacja jakości, nie dostępności).
  Zweryfikowane live: PNG 1790B → WebP 900B, cache w PG. Testy: 4 sim + WebpNegotiationTest.
- ~~Dokumentacja jak w security~~ — ZROBIONE (2026-07-07): README dostał sekcję
  „Documentation — the living contract" (feature'y jako kontrakt zachowania + wskazanie
  workspace'owych powierzchni: glosariusz UL i zbiorczy Allure przez
  `../create-documentation.sh`); generator celowo NIE dublowany per repo —
  workspace'owy skanuje memes od dawna.
