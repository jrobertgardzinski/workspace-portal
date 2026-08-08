# Podręcznik, etapy 3–6: mem, komentarz, kaskada, obserwowalność

Ciąg dalszy po [PODRECZNIK.md](PODRECZNIK.md) (etap 1 — usuwanie konta, saga) i
[PODRECZNIK-ETAP-2.md](PODRECZNIK-ETAP-2.md) (rejestracja i logowanie). Cztery krótsze etapy
w jednym pliku, bo każdy z nich wnosi **jedną** rzecz, której poprzednie nie miały.

Ta sama zasada: nazwy i liczby są wypisane z kodu.

---

# Etap 3: „użytkownik wrzuca mema"

## 3.1 Co tu jest nowego

Pierwszy przepływ, w którym przez system idzie **plik binarny**, a nie tylko wiersze w bazie.
To zmienia dwie rzeczy: pojawia się magazyn poza bazą (a więc coś, czego transakcja nie obejmuje)
i pojawia się pytanie „czy to już mamy".

## 3.2 Trzy adaptery jednego portu

`ObjectStore` ma trzy implementacje: `DbObjectStore`, `FilesystemObjectStore`, `S3ObjectStore`
(MinIO w compose). To nie jest kolekcjonerstwo — to jest **argument o przenośności postawiony
w kodzie zamiast w prezentacji**: ten sam serwis stoi na bazie w testach, na dysku w dev i na S3
w chmurze, a przypadek użycia nie wie który.

Praktyczny skutek, który wraca w etapie 1: **usunięcie bloba z S3 jest tym punktem bez powrotu
(pivotem), za którym saga nie ma czym kompensować.** Port jest granicą, na której to widać.

## 3.3 Deduplikacja: hash i wyścig o slot

`PublishMeme.execute` robi trzy rzeczy w tej kolejności:

```java
OptimizedImage optimized = optimizer.optimize(rawImage);   // 1. przekodowanie
String candidate = UUID.randomUUID().toString();
String owner = contentIndex.claim(optimized.data(), candidate);   // 2. ATOMOWE zajęcie hasha
if (!owner.equals(candidate)) return owner;                 // ktoś już ma ten obrazek
repository.save(...);                                       // 3. dopiero teraz bajty
```

Dwa niuanse warte rozmowy:

- **Hash liczy się PO optymalizacji**, nie z surowych bajtów. Inaczej ten sam obrazek wrzucony raz
  jako BMP i raz jako PNG byłby dwoma memami.
- **Zajęcie jest atomowe i wygrywa dokładnie jeden.** Dwa równoczesne wrzuty tego samego obrazka
  ścigają się o jeden slot; przegrany dostaje id zwycięzcy i **nie zapisuje kopii**.

## 3.4 Kompensacja bez transakcji — w miniaturze

Jeśli `repository.save` padnie po wygranym zajęciu, kod **cofa zajęcie** (`contentIndex.remove`).
Komentarz w kodzie mówi dlaczego wspólna transakcja tego nie załatwi: repozytorium może pisać bajty
do S3 albo na dysk, czyli **poza zasięg commitu bazy**.

I dalej: jeśli cofnięcie też padnie (zwykle ta sama martwa baza), błąd jest **logowany i podpinany
jako `suppressed`** do pierwotnego wyjątku — nie może ani przesłonić prawdziwej przyczyny, ani
zatrzymać sprzątania bloba. To jest ta sama myśl co saga z etapu 1, tylko w jednym procesie:
**kompensacja jest best-effort i nigdy nie kłamie o tym, co się nie udało.**

---

# Etap 4: komentarz i głos — dwa zadeklarowane wyjątki od prawa idempotencji

## 4.1 Prawo

ADR 0006: każda komenda wykonana dwa razy zostawia stan taki jak wykonana raz. Egzekwuje to
generyczny test w każdym serwisie (`IdempotentCommandsTest`) — nowa komenda dołącza do prawa przez
dopisanie do mapy `COMMANDS`, a nie przez pamiętanie o niej.

## 4.2 Dwa wyjątki i dlaczego są uczciwe

- **`AddComment`** — dwa razy „dodaj komentarz" to dwa komentarze. Nie da się inaczej: to jest
  operacja *tworząca*, a nie *ustalająca stan*.
- **`VoteOnComment` / `CastVote`** — głos jest **przełącznikiem**. Drugi identyczny głos to
  retrakcja (`voting.toggle`). Dwa wywołania ≠ jedno, i to jest zamierzone zachowanie produktu,
  a nie luka.

Wartościowe jest tu **słowo „zadeklarowany"**. Wyjątek, który jest wpisany do rejestru i ma własny
test nazwany „DECLARED EXCEPTION", różni się od wyjątku, o którym ktoś zapomniał — a P13 znalazł
dokładnie ten drugi przypadek: `VoteOnComment` łamał prawo, nie będąc nigdzie zadeklarowanym,
i rejestr ADR-a przez to kłamał.

## 4.3 Czego uczy głosowanie o kolejności operacji

W usuwaniu konta (etap 1) reguła „zostaw popularne" czyta **wynik głosowania**, a głosy odchodzącego
są wycofywane razem z jego treścią. Kolejność musi być: **najpierw wycofaj jego głosy, potem oceniaj
popularność** — inaczej ktoś, kto podbił własne memy, kupowałby im przetrwanie głosem, który za
chwilę i tak znika (P18 poz. 39).

---

# Etap 5: kaskada `MEME_DELETED` — trzeci wzorzec spójności

## 5.1 Trzy wzorce obok siebie

| Wzorzec | Gdzie | Kto czeka | Co przy porażce |
|---|---|---|---|
| **saga** (orkiestracja) | usuwanie konta | orkiestrator czeka na potwierdzenia | kompensacja, potem ponawianie za pivotem |
| **transactional outbox** | każdy producent zdarzeń | nikt | ponowna publikacja z wiersza |
| **kaskada** (choreografia) | `MEME_DELETED` → wątek → ulubione | **nikt** | martwa referencja, którą UI rysuje jako martwą |

## 5.2 Dlaczego kaskada MOŻE być best-effort

Komentarz w `CascadeConsumer` (collections) stawia to najostrzej: konsument sagi to **obietnica** —
orkiestrator jest na niej zablokowany, zgubiony purge to złamane zobowiązanie RODO, więc pętla
ponawia bez commitu, przewija wsad, sonduje brokera i steruje `/health`. Kaskada to **choreografia**
— nikt nie czeka, nic nie potwierdza, nic nie kompensuje, a najgorszy skutek totalnej awarii to
wiersz wskazujący na mema, którego już nie ma. Interfejs i tak rysuje go jako martwego.

Dlatego kaskada ma **własną pętlę, własną grupę konsumentów i celowo NIE jest pod probami**:
zablokowane sprzątanie nie może raportować instancji jako niezdolnej do swojej części sagi.

## 5.3 Druga przeskocznia i jedna transakcja

W comments kaskada ma drugi skok: skasowanie wątku ogłasza `COMMENTS_DELETED`, żeby collections
mogło wyrzucić referencje do komentarzy, które zginęły razem z memem. Runda 10 zamknęła oba kroki
w **jednej transakcji**: wcześniej ogłoszenie szło po powrocie z przypadku użycia, czyli poza
transakcją, a „publikuj po commicie" znaczyło tyle, że powrót oznaczał commit. Wystarczało, żeby
rollback był cichy — ale zostawiało ogłoszenie **bezdomne**: proces mógł umrzeć w szczelinie między
commitem a wysyłką, a ponowna dostawa `MEME_DELETED` zastaje **pusty wątek i celowo nic nie
ogłasza**.

To jest ładny przykład na rozmowę: *idempotencja konsumenta potrafi zjeść zdarzenie, którego
producent nie zdążył wysłać.*

---

# Etap 6: obserwowalność — jeden `cid` przez cztery serwisy

## 6.1 Korelacja

`CorrelationIdFilter` czyta nagłówek `X-Correlation-Id` (albo bije nowy), wkłada go do kontekstu
logowania (`%X{cid}` w logbacku), **odsyła w odpowiedzi** i loguje jedną linię dostępu. Wywołania
wychodzące przekazują ten sam nagłówek dalej. Efekt: `grep` po jednym id pokazuje całą podróż
żądania przez serwisy.

W sadze widać to w praktyce: potwierdzenia i komendy niosą `cid` w nagłówku Kafki
(`KafkaTracing.HEADER`), a wiersz outboxu **przechowuje go**, żeby publikacja ponowiona godzinę
później nadal miała ten sam ślad.

## 6.2 Co naprawdę mierzą ręczne eksportery

Serwisy na Helidonie (offboarding, collections) nie mają registry — `/metrics` jest **funkcją
napisaną ręcznie**. Warto wiedzieć, co jest tam warte alertu, a co jest ozdobą:

| Metryka | Typ | Co znaczy |
|---|---|---|
| `*_kafka_records_dropped_total` | licznik | jedna porzucona komenda sagi = jedno usunięcie konta, którego ten serwis nie dokończył |
| `offboarding_sagas_compensated_total` | licznik | portal poddał się przy usuwaniu konta |
| `*_erasure_backlog` | **gauge** | ile treści jest UKRYTYCH, ale nie wymazanych, w tej chwili |

Trzecia pozycja jest najmłodsza i najważniejsza, i musi być **gauge, nie licznikiem**: pytanie brzmi
„ile obowiązków wisi TERAZ", a odpowiedź ma sama wrócić do zera, gdy domknięcie w końcu dojdzie.

## 6.3 Lekcja, którą warto zapamiętać ponad tabelkami

Metryka bez reguły alertu to **wykres, na który nikt nie patrzy**. Gauge zaległości istniał w trzech
serwisach, zanim ktokolwiek dopisał regułę, która go czyta — a awaria, którą pokazuje, jest cicha
z konstrukcji: nic nie rzuca wyjątkiem, nic nie czerwienieje, zapytanie po prostu zwraca mniej
wierszy. Reguły są w `../shared/observability/alert-rules.yml` i **na dziś istnieją wyłącznie
w stosie compose** — wdrożenie na k3s ich nie ma (zapisane w `k8s/README.md`).

---

## Gdzie to czytać w kodzie

| Etap | Wejście |
|---|---|
| 3 | `memes-application/.../PublishMeme.java`, `MemeContentIndex.java`, trzy `*ObjectStore.java` |
| 4 | `memes-application/.../CastVote.java`, `IdempotentCommandsTest` w każdym serwisie, ADR 0006 |
| 5 | `microservice-comments/.../MemesEventsListener.java`, `microservice-user-collections/.../CascadeConsumer.java` |
| 6 | `*/CorrelationIdFilter.java`, `*/MetricsEndpoint.java`, `shared/observability/alert-rules.yml` |
