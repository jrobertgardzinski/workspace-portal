# Podręcznik, etap 2: „użytkownik zakłada konto i się loguje"

Ten etap czyta się po [PODRECZNIK.md](PODRECZNIK.md) (etap 1: usuwanie konta). Tamten opisywał
**sagę** — rzecz rozproszoną. Ten opisuje coś przeciwnego: przepływ, który w całości mieści się
w jednym serwisie i jednej transakcji, a mimo to jest trudniejszy — bo każdą decyzję trzeba
uzasadnić wobec kogoś, kto celowo próbuje ją obejść.

Zasada czytania ta sama: **żadnej liczby ani nazwy nie ma tu z pamięci**. Wszystko jest wypisane
z kodu i wskazuję gdzie.

---

## 1. Cała historia w jednym akapicie

Ktoś podaje adres i hasło do `POST /register`. Serwis odpowiada **201 niezależnie od tego, czy
adres był wolny** (o tym za chwilę), a na skrzynkę idzie link weryfikacyjny. Po weryfikacji można
się zalogować: `POST /authenticate` sprawdza **najpierw blokadę**, potem hasło, potem czy adres jest
zweryfikowany, i dopiero na końcu decyduje, czy sesja powstaje od razu, czy trzeba jeszcze przejść
**łańcuch czynników**. Jeśli konto ma czynniki, zamiast sesji wraca **bilet** i nazwa pierwszego
czynnika; klient wraca z dowodem do `POST /authenticate/continue`. Sesja to **para tokenów**:
krótkożyjący access (JWT, weryfikowalny offline przez JWKS) i długożyjący refresh (jednorazowy,
rotowany, w rodzinie).

---

## 2. Dlaczego rejestracja kłamie w dobrej wierze

`POST /register` na zajęty adres odpowiada **201, tak jak na wolny**. To nie niedbalstwo, tylko
obrona przed **enumeracją**: gdyby odpowiedź się różniła, formularz rejestracji stałby się
wyszukiwarką „czy ta osoba ma tu konto". Prawda idzie kanałem, który i tak należy do właściciela
adresu — **mailem**: właściciel zajętego konta dostaje powiadomienie „ktoś próbował założyć konto
na Twój adres", a nie link.

To kosztuje. Interfejs nie może powiedzieć „ten adres jest zajęty", bo sam tego nie wie. Kosztuje
też testowo — asercja „201" nie odróżnia sukcesu od odmowy, więc scenariusze czytają **skrzynkę**,
nie kod odpowiedzi. Warto to umieć powiedzieć na rozmowie w tej kolejności: *najpierw jaki atak,
potem jaka cena*.

---

## 3. Kolejność kroków w logowaniu, i dlaczego akurat taka

`Authentication.execute` (`security-system/.../authentication/Authentication.java`) to jeden
`switch` na zdarzeniach domenowych. Kolejność:

```
1. blokada źródła?          -> Blocked            (nie dotykamy hasła)
2. hasło poprawne?          -> Invalid: policz porażkę, Rejected
3. adres zweryfikowany?     -> EmailNotVerified
4. wyczyść porażki tej PARY
5. ma czynniki?  nie -> sesja teraz
                 tak -> bilet + nazwa pierwszego czynnika (MfaRequired)
```

Trzy rzeczy warte zapamiętania:

- **Blokada jest sprawdzana przed hasłem.** Inaczej zablokowane źródło wciąż mogłoby zgadywać —
  dostawałoby „odrzucone" zamiast „zablokowane", ale ruch po stronie serwera i tak by szedł.
- **Poprawne hasło NIE aktualizuje licznika porażek** — bo nie jest sygnałem zgadywania. Komentarz
  w kodzie mówi to wprost.
- **Weryfikacja adresu jest sprawdzana PO haśle.** Odwrotna kolejność zdradzałaby, czy konto
  istnieje, komuś, kto nie zna hasła.

### 3.1 Konwencja `_Krok` w praktyce

Etap 1 wspominał ADR 0002 i narzekał, że w pakiecie `account` nie ma ani jednej takiej klasy.
**Tutaj są wszystkie**: `_BruteForceGuard`, `_VerifyCredentials`, `_RequireVerifiedEmail`,
`_GenerateSession`, `_CleanBruteForceRecords`, `_UpdateBruteForceRecords`. Podkreślnik znaczy
„krok wewnętrzny tego przypadku użycia, package-private". Sens jest praktyczny: w liście plików
i w podpowiedziach IDE widać na pierwszy rzut oka, co jest wejściem (`Authentication`), a co
częścią jego środka.

---

## 4. Brute force: dwa liczniki, jedno okno, jedna blokada

To jest najlepszy fragment tego etapu na rozmowę, bo ma **udokumentowaną historię trzech pomyłek**.

`_BruteForceGuard` liczy dwie rzeczy w tym samym oknie (domyślnie **15 minut**,
`FailureWindowMinutes.DEFAULT`):

| Licznik | Pytanie | Domyślny próg |
|---|---|---|
| ciasny | ile razy TO źródło pomyliło się na TYM koncie | **3** (`MaxFailures.DEFAULT`) |
| sufit | ile razy TO źródło pomyliło się w ogóle | **30** (`MaxFailuresPerSource.DEFAULT`) |

Ciasny łapie zgadywanie jednego hasła. Sufit łapie **spray** — po dwa strzały w setkę kont, przy
którym żadne pojedyncze konto nie dobija do trójki. Konfiguracja pilnuje sensu tej pary: konstruktor
`BruteForceConfig` **rzuca wyjątkiem**, gdy sufit jest niższy niż próg ciasny.

**Blokada zawsze ląduje na ŹRÓDLE, nigdy na koncie.** Blokada idąca za kontem byłaby bronią: każdy
mógłby zablokować dowolną ofiarę z dowolnego miejsca. Czas blokady jest **losowy** z przedziału
3–10 minut (`MinBlockMinutes`/`MaxBlockMinutes`, `RandomBlockDurationPolicy`) — stała wartość
mówiłaby atakującemu, kiedy dokładnie wracać.

### 4.1 Trzy wersje tego samego kodu i czego uczy każda

1. **Licznik kluczowany samym adresem IP + czyszczenie po udanym logowaniu.** Skutek: jedno
   poprawne hasło (choćby do konta atakującego) **kasowało cały dorobek adresu** — udane logowanie
   stawało się przyciskiem „reset" dla ataku prowadzonego z tej samej maszyny.
2. **Wycięcie czyszczenia w całości** (P18 poz. 6). Skutek odwrotny: trzy literówki jednej osoby
   blokowały całe biuro za NAT-em. **Dowód z życia: własna suita e2e zablokowała samą siebie
   w połowie przebiegu.**
3. **Dzisiaj: `LockoutSubject(Source, AttemptedAccount)`.** Porażki są księgowane na PARZE,
   czyszczenie też dotyczy tylko tej pary, a `AttemptedAccount` jest **znormalizowany** — inaczej
   `a.b@gmail.com` i `ab@gmail.com` byłyby osobnymi kubełkami i każda kropka kupowałaby atakującemu
   kolejny komplet prób.

Morał, który warto umieć powiedzieć: **mechanizm obronny liczący „per źródło" traktuje całe biuro,
CGNAT i runner CI jako jedną osobę.** Ta sama pomyłka wróciła nam tego samego dnia od innej strony —
limit step-upu (10/15 min per źródło) wywrócił suitę e2e, bo cała suita jest jednym źródłem.

---

## 5. Łańcuch czynników — czym różni się od „MFA z tutoriala"

Po poprawnym haśle `Authentication` pyta o **listę** zapisanych czynników. Jeśli jest pusta, sesja
powstaje od razu (klasyczne logowanie jednoskładnikowe zostaje nietknięte). Jeśli nie —
`MfaChain.begin` otwiera **pending authentication**, `PendingAuthenticationStore.open` zwraca
**bilet**, a klient dostaje nazwę pierwszego czynnika i jego dane wyzwania.

Cztery rzeczy, które odróżniają to od typowego „SMS-a po haśle":

- **Czynniki są łańcuchem, nie jednym krokiem.** Konto może mieć ich kilka i przechodzi się je po
  kolei; `MfaChain` wie, który jest bieżący.
- **Rejestr typów zamiast `if`-ów.** `FactorRegistry` mapuje typ (`EMAIL_CODE`, `SMS_CODE`, `TOTP`,
  `WEBAUTHN`) na adapter. Przypadki użycia nad łańcuchem nie wiedzą, który to który — dlatego
  dołożenie czynnika nie dotyka logowania.
- **Kody odzyskiwania są ALTERNATYWĄ dla ogniwa, nie ogniwem.** Gdy bieżący czynnik odrzuci dowód,
  łańcuch sprawdza jeszcze, czy to nieużyty kod odzyskiwania — jeśli tak, **zużywa go** (jednorazowy)
  i przepuszcza. Dzięki temu każde wejście chodzące łańcuchem (logowanie, step-up) obsługuje
  kody odzyskiwania, nic o nich nie wiedząc.
- **Minimum per rola.** `MfaCompliance` sprawdza, czy konto spełnia podłogę swojej roli, licząc
  hasło jako pierwszy czynnik — ale **nie** licząc logowania przez dostawcę u konta federacyjnego
  (bo tam hasła nie ma). Pierwszy admin jest ułaskawiony, dopóki ma zero czynników; od pierwszego
  zapisanego obowiązuje go podłoga. Inaczej pierwszego admina nie dałoby się w ogóle zalogować.

---

## 6. Sesja: dwa tokeny o przeciwnych właściwościach

| Token | Żywotność | Kto go weryfikuje | Czy da się unieważnić natychmiast |
|---|---|---|---|
| access (JWT, EdDSA/Ed25519) | krótka | **każdy serwis offline**, przez `/.well-known/jwks.json` | **nie** — do wygaśnięcia |
| refresh | długa | tylko security (w bazie) | tak |

**To jest świadomy handel, nie niedopatrzenie.** `JwtAccessTokenMint` mówi wprost: weryfikacja
offline nie zobaczy wylogowania ani zmiany roli, dopóki token nie wygaśnie; introspekcja (`GET /me`)
zobaczy. Wybór należy do serwisu-konsumenta. Samo security traktuje access token **jak sekret
nieprzezroczysty** (hashuje, przechowuje, porównuje), więc jego własne „wyloguj" i „unieważnij
wszystkie" działają natychmiast.

Rotacja kluczy jest przewidziana i **konfiguracyjna**: `security.jwt.previous-public-keys` trzyma
publiczne połówki wycofanych kluczy w serwowanym JWK secie, dopóki żyją tokeny nimi podpisane.
Prywatną połówkę można zniszczyć w chwili rotacji.

---

## 7. Refresh token, rodziny i wykrywanie kradzieży

`RefreshSession.execute` — najgęstszy kawałek kodu w tym etapie i najlepszy materiał na pytanie
„a skąd wiesz, że token nie został ukradziony".

Zasady:

1. **Jednorazowość.** Użyty refresh token jest rotowany: znika, w jego miejsce wchodzi następca
   **w tej samej rodzinie** (`family`).
2. **Ponowne użycie = kradzież.** Token o statusie `ROTATED` przedstawiony drugi raz oznacza, że
   ktoś ma kopię. Reakcja jest brutalna i jedyna sensowna: **`revokeFamily`** — cała linia sesji
   ginie, oboje (ofiara i złodziej) muszą się zalogować od nowa.
3. **Rotacja jest WARUNKOWA i to ona decyduje o gałęzi.** Wcześniej kod czytał status, a potem
   pisał bezwarunkowo — klasyczne *check-then-act*. Dwa naprawdę równoczesne odświeżenia widziały
   `ACTIVE`, oba zapisywały `ROTATED` i oba mintowały następcę: **jedna sesja rozwidlała się w dwie
   żywe linie**, a złodziej odświeżający się obok ofiary miał gałąź, której nic nigdy nie zgłosi.
4. **Przegrana w wyścigu jest traktowana jak ponowne użycie**, bo z tego miejsca te dwie sytuacje są
   nieodróżnialne. Uczciwy koszt: dwie karty przeglądarki odświeżające się w tej samej milisekundzie
   wylogują użytkownika. To jest strona konserwatywna i tę samą wybiera rekomendacja OAuth: łagodne
   podwójne odświeżenie kosztuje jedno logowanie, niewykryty skradziony token kosztuje konto.
5. **Rotacja i utworzenie następcy to JEDNO wywołanie repozytorium** (`rotateAndCreate`). Jako dwa
   kroki wpuszczałyby między siebie `revokeFamily` — i powstałaby żywa sesja w rodzinie, którą
   serwis raportuje jako unieważnioną.

Punkt 5 jest wart osobnej uwagi: to nie jest optymalizacja, tylko **granica atomowości narysowana
w porcie**. Port istnieje właśnie po to, żeby ta granica dała się nazwać.

---

## 8. Czego ten etap uczy o testach

- **Zielony `mvn verify` nie znaczy „wstaje"** — to lekcja z etapu 1, ale tutaj ma drugą stronę:
  suita przeglądarkowa (`security-ui/run-e2e.sh`) potrafi być zielona przez miesiące i nie dotykać
  całej połowy mechanizmu. Sprawdzone 2026-08-08: `/account/step-up/factor` **nie było wołane ani
  razu** w całym przebiegu 36 scenariuszy, bo wszystkie konta testowe elewowały się na samym haśle.
  Czynnikowa połowa step-upu nie miała żadnego pokrycia — i pękła, gdy pierwszy scenariusz jej
  potrzebował.
- **`locator.isVisible({ timeout })` ignoruje timeout.** Odpowiada o tej chwili. Pole pojawiające
  się o jeden render później jest „niewidoczne", helper po cichu pomija krok i test wywala się
  gdzie indziej. W tej samej funkcji ta pułapka była już raz naprawiona — dla innego elementu.
- **Kolejność testów bywa brana z systemu plików.** Surefire domyślnie tak robi; testy dzielące
  jedną bazę H2 zależą wtedy od kolejności, a build jest zielony u Ciebie i czerwony w CI.

---

## 9. Trzy zdania, które warto umieć powiedzieć z pamięci

1. **„Rejestracja odpowiada tak samo na adres wolny i zajęty, bo inaczej byłaby wyszukiwarką kont;
   prawdę dostaje właściciel adresu mailem."**
2. **„Licznik nieudanych logowań jest kluczowany PARĄ (źródło, konto), bo po samym koncie każdy
   zablokuje ofiarę na życzenie, a po samym źródle trzy literówki blokują całe biuro za NAT-em —
   i to nam się naprawdę zdarzyło, własna suita e2e zablokowała samą siebie."**
3. **„Refresh token jest jednorazowy i rotowany w rodzinie; jego ponowne użycie traktujemy jak
   kradzież i unieważniamy całą rodzinę — a przegraną w wyścigu równoważnie, bo z tego miejsca
   nie da się ich odróżnić."**

---

## 10. Gdzie to czytać w kodzie

| Co | Gdzie |
|---|---|
| przepływ logowania | `security-system/.../authentication/Authentication.java` |
| dwa liczniki i blokada | `.../authentication/_BruteForceGuard.java`, `RandomBlockDurationPolicy` |
| podmiot blokady | `security-domain/.../vo/LockoutSubject.java`, `AttemptedAccount.java` |
| progi i ich walidacja | `security-config/.../bruteforce/BruteForceConfig.java` + `vo/` |
| łańcuch czynników | `security-system/.../mfa/MfaChain.java`, `FactorRegistry.java` |
| podłoga per rola | `security-system/.../mfa/MfaCompliance.java` |
| JWT i rotacja kluczy | `security-infrastructure/.../JwtAccessTokenMint.java`, `JwksController.java` |
| rodziny sesji i kradzież | `security-system/.../session/RefreshSession.java` |
| scenariusze | `microservice-security/specs/authenticate.feature`, `.../mfa.feature` |
