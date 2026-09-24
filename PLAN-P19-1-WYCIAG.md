# P19 część 1 — wyciąg z przebiegu rutyny (2026-08-31/09-01)

> ## PRZECZYTAJ NAJPIERW — czym ten plik JEST, a czym NIE JEST
>
> Rutyna chmurowa „P19 cz.1 — shared + security" (Opus 5, 7 agentów o wąskim zakresie + lektura
> własna w głównej pętli) **wykonała przegląd i napisała `PLAN-P19-1.md` — 2125 linii, 52 znaleziska**,
> numeracja ciągła `P19-1-1` … `P19-1-52`, wagi: **1 KRYTYCZNE / 17 WYSOKICH / 22 ŚREDNIE / 12 NISKICH**.
>
> **Tego pliku nie ma w repozytorium.** Push został odrzucony:
> `remote: Claude doesn't have GitHub access to jrobertgardzinski/workspace-portal`.
> Klonowanie działa (repa są publiczne), push wymaga zainstalowanej aplikacji **Claude GitHub App**.
> Kontener jest efemeryczny, więc agent **załączył plik w sesji**:
> <https://claude.ai/code/session_01Y7kmnu1kFfFmiTVUnHjXEb>
>
> **Ten plik to WYCIĄG z logu przebiegu, nie kopia tamtego dokumentu.** Zawiera tylko te pozycje,
> których treść dało się odczytać z logu w całości — **9 z 52**. Log przycina każde zdarzenie
> (`[+24914 chars]`), więc reszty nie da się stąd odtworzyć: po pełną listę trzeba pobrać załącznik
> z sesji. Cytaty poniżej pochodzą z logu i nie były przeze mnie ponownie sprawdzone w kodzie —
> tam, gdzie sam coś potwierdziłem, jest to napisane wprost.
>
> **Czego rutyna NIE zrobiła:** niczego nie uruchomiła. Zero buildów, zero testów, zero migracji —
> kontener ma JDK 21, a estate wymaga `release 25`, i proxy blokuje pobranie Adoptium. Cały przegląd
> jest lekturą kodu.
>
> **Część 2 (portal) nie powstała w ogóle** — rutyna odbiła się o limit pięciogodzinny
> (`You've hit your session limit`) po jednej turze.

---

## Ustalenie ramowe

W `microservice-security` **nie ma żadnego globalnego mapera wyjątków** — `grep` po
`ExceptionHandler|@Error|serverError` (bez `target/` i testów) nie zwraca nic. Każdy `RuntimeException`,
który ucieknie z metody kontrolera, ląduje w domyślnej obsłudze Micronauta jako **500 z komunikatem
wyjątku w ciele**. Obie sierpniowe poprawki granicy (`720b396`, `348251d`) naprawiają to
**punktowo, endpoint po endpoincie** — więc każdy endpoint, którego nie objęły, wciąż jest odsłonięty.
To jest przyczyna źródłowa pozycji 1 i 2 poniżej.

---

## 1. KRYTYCZNE — step-up przechodzi na sam skradziony access token dla każdego konta federacyjnego

Naprawa pozycji 1 z P18 (step-up nie może opierać się na samej żywej sesji) zrobiła **jawny wyjątek
dla kont bezhasłowych**.

`microservice-security/security-system/.../mfa/StepUp.java:73-81`
```java
boolean mustProvePassword = StepUpPolicy.FULL_CHAIN.equals(requirement) || enrolled.isEmpty();
if (mustProvePassword && !passwordless.isPasswordless(email) && !passwordMatches(email, passwordAttempt)) {
    return new Result.WrongPassword();
}
if (enrolled.isEmpty()) {
    elevation.elevate(accessToken, action);   // nothing further to prove
    return new Result.Elevated();
}
```

Konto z Google rodzi się bezhasłowe — `security-system/.../federation/FederatedSignIn.java:123-126`:
```java
users.save(new User(email, unusablePassword()));
verifications.markVerified(email);
passwordless.setPasswordless(email, true);   // born through the provider, no password of its own
```

Akcje wrażliwe mają domyślnie najostrzejsze wymaganie (`BeanFactory.java:405,413,418`:
`delete-account`, `change-email`, `change-password` → `FULL_CHAIN`), więc gałąź jest osiągalna dla wszystkich.

**Skutek:** sam skradziony access token wystarcza, by przejść step-up i wykonać `change-email` —
czyli trwale przejąć konto; `delete-account` działa tak samo.

**Uwaga rutyny (ważna):** w kodzie stoi komentarz **broniący** tej gałęzi, więc może to być świadomy
kompromis — ale jego przesłanka („żywa sesja to wszystko, co takie konto ma") jest nieprawdziwa:
konto federacyjne może udowodnić świeżą rundę OAuth albo kod odzyskiwania. Przed naprawą warto
rozstrzygnąć, czy to luka, czy decyzja.

---

## 2. `/authenticate/factor` — brak `mfaTicket` daje NPE i 500 na endpoincie BEZ uwierzytelnienia

To ta sama rodzina („pominięte pole"), którą commit `348251d` zamknął — ale zamknął ją **listą pięciu
endpointów, nie regułą**, i tego nie objął.

`security-infrastructure/.../AuthFactorController.java:38-42`
```java
HttpResponse<Map<String, Object>> submit(@Body Map<String, String> body) {
    String ticket = body.get("mfaTicket");
    String proof  = body.get("proof");
    ContinueAuthenticationResult result = transactionBoundary.execute(() -> continueAuthentication.execute(ticket, proof));
```
Bilet trafia do składu opartego na `ConcurrentHashMap`, która na `get(null)` rzuca NPE —
`InMemoryPendingAuthenticationStore.java:26,42`. Ten sam kształt ma `StepUpController.java:59`
(`body.get("stepUpTicket")`).

**Skutek:** nieuwierzytelniony i nieprzepustnicowany endpoint zwraca 500 z nazwą klasy wyjątku;
każda próba to stack trace w logu.

**Moja uwaga:** to trafienie w moją wczorajszą poprawkę. Szukałem wzorca `(String) body.get(...)`,
a tu jest samo `body.get(...)` na mapie `Map<String, String>` — mój grep tego nie widział.

---

## 3. `GET /mails/dlq` wydaje jawnym tekstem linki resetu hasła i kody MFA

`microservice-email`:
- `boundary/MailRequestsConsumer.java:294` — do zaparkowanego rekordu wchodzi **całe** zdarzenie:
  `parked.set("event", mapper.readTree(payload));`
- `boundary/ParkedMails.java:60` — całe ląduje w ledgerze: `ledger.put(key, parked);`
- `boundary/DlqResource.java:33-34` — całe wychodzi po HTTP: `return parked.all();`
  (ścieżka odmowy dokłada je do ciała 422 — `:69`)
- `boundary/ApiKeyFilter.java:33-34` — jedyna bramka to `mail.api-key`, ten sam dla `/mails` i `/mails/dlq`

Ten sam plik uznaje te wartości za tajne gdzie indziej (`MailRequestsConsumer.java:174-176`:
*„the payload itself never reaches the log: these events carry password-reset links and one-time MFA codes"*).

**Skutek:** SMTP leży 10 minut, reset hasła parkuje; ktokolwiek zna `MAIL_API_KEY` — a compose daje mu
fallback `local-dev-key` i ten sam sekret trzyma `microservice-security` — robi `GET /mails/dlq`
i czyta `"link":"…/reset?token=…"`. Token resetu nie wygasa (P18 §12).

---

## 4. Cache JWKS nigdy nie wygasa — wyłącznik awaryjny klucza nie działa

`offline-jwt/src/main/java/.../OfflineJwtVerifier.java:183-190`
```java
Map<String, PublicKey> fresh = jwksFetcher.get();
if (fresh.isEmpty()) {
    // the JWKS is unreachable: keep serving the last good keys (and retry next time —
    // fetchedAt stays put) instead of turning a fetch hiccup into a total outage
    return known.get(kid);
}
```
Wbrew własnemu javadocowi (`:31-34`: *„removing a compromised key from the JWKS reaches every consumer
within minutes… the emergency kill-switch must propagate"*), a złe zachowanie jest **przypięte testem**
(`OfflineJwtVerifierTest.java:216-220`).

**Skutek:** dopóki `/.well-known/jwks.json` jest nieosiągalny, skompromitowany `kid` weryfikuje tokeny bez końca.

---

## 5. `email_verifications` — link weryfikacyjny nie wygasa nigdy

`V2__email_verifications.sql:3-7` — tabela bez jakiejkolwiek kolumny czasu; `EmailVerificationEntity.java:13-16`
też nic nie niesie; `VerifyEmail.java:18-22` nie sprawdza wieku tokenu (brak `Clock`, brak `Duration`).

Ten sam defekt naprawiono dla `password_resets` (V20) i `email_changes` (V24) — **trzecia rodzina została
pominięta**, a komentarz V24 twierdzi, że była ostatnia.

---

## 6. `LocalPart.normalize()` omija własny walidujący konstruktor

`email/email-domain/.../LocalPart.java:10-12` — konstruktor pakietowo-prywatny, bez walidacji;
`:35-48` — `normalize()` buduje wynik przez `new LocalPart(...)`, a nie `LocalPart.of(...)`, więc nie
przechodzi żadnej z trzech reguł (w tym sierpniowej o podwójnej kropce). Dla gmaila/yahoo/outlook regex
może wyczyścić część lokalną do `""` — wartości, którą `LocalPart.of` odrzuca kodem `LOCAL_PART_EMPTY`.

To trafia w `NormalizedEmail` (`:16-20, 30-32`), czyli **klucz unikalności konta**.

---

## 7. Wszystkie sekrety stacku mają fallback `:-`, a plan wdrożenia kieruje te pliki na publiczny serwer

`workspace-shared/docker-compose.identity.yml`: `:74` i `:327` `POSTGRES_PASSWORD:-secret`,
`:166` `MAIL_API_KEY:-local-dev-key`, `:211` (oraz `:106`, `:115`) `IDP_CLIENT_SECRET:-demo-secret`,
`:95` `SECURITY_BOOTSTRAP_ADMINS: admin@example.com`;
`docker-compose.observability.yml:76` `GRAFANA_ADMIN_PASSWORD:-admin`.

Operator `:-` (a nie `:?`) sprawia, że **pusta** zmienna też wraca do wartości deweloperskiej — literówka
w `.env` cicho przywraca `admin`/`secret` zamiast zatrzymać start. A `docs/deployment-plan.md:30-31`
przeznacza te same pliki na pierwszy publiczny VPS.

---

## 8. Co weryfikacja OBALIŁA — czytaj to zanim zaufasz reszcie

Rutyna wprost potwierdziła, że **obie sierpniowe poprawki granicy się bronią**:

- ciche 202 na `/verify-email/request` i `/reset-password/request` jest **bajt w bajt identyczne**
  dla złego stringa, brakującego pola i złego typu JSON;
- `AbstractToken` odrzucający `null` **nie zepsuł żadnego wołającego** — w drzewie nie ma ani jednego
  `catch (NullPointerException)`;
- `/register` **nie jest wyrocznią istnienia konta** — walidacja poprzedza sprawdzenie zajętości,
  a hasło jest hashowane przed rozgałęzieniem, więc nie ma też kanału czasowego.

Zastrzeżenie rutyny: zamknięto rodzinę **listą endpointów, nie regułą** — stąd pozycja 2 wyżej.

Osobno obalono podejrzenie o brak reguły długości adresu: reguły faktycznie nie ma
(`Email.java:22` sprawdza tylko pozycje `@`), ale kolumny są `VARCHAR(255)`, co zawęża skutek.

---

## Do zrobienia z tym plikiem

1. Pobrać **pełny `PLAN-P19-1.md`** (52 pozycje) z załącznika w sesji i wstawić do repo — ten wyciąg
   ma zastąpić dopiero wtedy, gdy pełna wersja będzie na miejscu.
2. Zainstalować **Claude GitHub App** na repozytoriach, żeby rutyny mogły pushować.
3. Uruchomić **część 2 (portal)** po zresetowaniu limitu.
4. Rozstrzygnąć pozycję 1 (luka czy świadomy kompromis) — to jedyne KRYTYCZNE w całym przeglądzie.
