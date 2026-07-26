# Plan: dokończenie paczki 9 + paczka 10 (wspólny outbox)

Stan na 26 lipca 2026. Dokument opisuje dwie rzeczy: **czego brakuje w paczce 9** (agent
padł w połowie) oraz **paczkę 10**, czyli wyciągnięcie outboxa do wspólnej biblioteki
w jądrze — wariant „C′" wybrany jako najczystszy architektonicznie.

---

## Część A — dokończenie paczki 9

### Co jest zrobione (niezacommitowane, w drzewach roboczych)

| Repo | Testy | Co powstało |
|---|---|---|
| user-collections | 81 → **118** | `CascadeConsumer` w osobnej grupie konsumenckiej i na osobnym wątku, port `ItemReferences`, use case `PurgeDeletedItem`, migracja V2 z indeksem `(item_type, item_id)`, bezpieczny read-repair w UI |
| comments | 65 → **76** | publikacja `COMMENTS_DELETED` po commicie (wariant B), test rollbacku na prawdziwej transakcji, regresja potwierdzeń sagi |
| memes | 133 → **145** | maskowany autor + pole `own` w `/meta`, PII poza logami, `/memes/hot` jeden odczyt zamiast O(n log n) + TOP-N, paginacja listingu |

### Czego brakuje

**A1. Kontrakty i asercje nazw topiców** — agent integracyjny padł na błędzie 529.
To jest akurat ta rzecz, którą audyt wskazał jako najgroźniejszą strukturalnie:
*nazwa topicu nie jest spięta żadną asercją, więc literówka daje skasowane dane
i zielone CI*. Nowa kaskada nie może odziedziczyć tej ślepej plamki.

- Pact konsument/provider dla `MEME_DELETED` (memes → collections)
- Pact konsument/provider dla `COMMENTS_DELETED` (comments → collections)
- Asercja, że producent publikuje **dokładnie na tym topicu**, którego słucha konsument

**A2. Dwie regresje zgłoszone przez samych wykonawców — do sprawdzenia, nie do zignorowania**

1. **`/memes/hot` ma teraz twardy TOP-100, a `memes-ui` używa go jako słownika wyników**
   dla kafelków ściany (`scores[m.id] ?? 0`). Mem poza setką pokaże wynik zero.
   Do rozstrzygnięcia: albo UI dobiera wyniki osobno, albo ściana nie używa rankingu jako słownika.
2. **`GET /memes` zwraca teraz stronę (50), a nie całą galerię.** Wykonawca zaktualizował
   `memes-ui` i jego e2e, ale **nie tknął `portal/e2e/`** — scenariusze w workspace mogą być zepsute.

**A3. Weryfikacja i commity** — pełne suity pięciu repo, e2e domyślne + `@outage`,
nowy scenariusz „skasowany mem znika też z ulubionych", commity per repo.

### Ryzyka odnotowane przez wykonawców (świadome, do zapisania w todo)

- `COMMENTS_DELETED` dla wielkiego wątku rośnie liniowo — kilka tysięcy komentarzy przebije
  domyślny `max.request.size`; comments nie pinuje też `max.block.ms` (memes pinuje).
- Zgubione `COMMENTS_DELETED` nie naprawi się samo: redostarczenie `MEME_DELETED` trafia
  na pusty wątek i celowo nic nie ogłasza. Jedyną naprawą jest read-repair w UI.
  **To jest dokładnie powód, dla którego robimy część B.**
- `MEMES_RESOLVER` w collections domyślnie wskazuje DNS Dockera — w k3d/k8s wymaga nadpisania.
- `auto.offset.reset=earliest` na nowej grupie: pierwszy start przejdzie całą zachowaną historię obu topiców.

---

## Część B — paczka 10: wspólny outbox (wariant C′)

### Po co

Audyt z 26 lipca nazwał to motywem nr 2: *„rola uczestnika została skopiowana czterokrotnie
i każda kopia ma inne gwarancje"*. Dziś mamy trzy różne odpowiedzi na to samo pytanie
„jak nie zgubić zdarzenia":

| Serwis | Mechanizm | Gwarancja |
|---|---|---|
| memes | pełny outbox (tabela + republisher, delivered-first) | zdarzenie przeżywa crash |
| comments | publikacja po commicie + log błędu (wariant B) | rollback nie wypuszcza, crash gubi |
| collections | `send().get()` przed commitem offsetu | inny wzorzec, nie outbox |

Biblioteka ujednolica dwa pierwsze. Trzeciego **świadomie nie ruszamy** — potwierdzenie sagi
przed commitem offsetu to inna obietnica, nie gorsza wersja tej samej.

### Dlaczego dopiero teraz, a nie zamiast wariantu B

Bibliotekę wyciąga się **z dwóch działających implementacji, nie z jednego pomysłu**.
Po paczce 9 mamy dojrzały outbox z memes (utwardzany w paczkach 5–7) i świeży, prosty
przypadek z comments. Dwa punkty danych to minimum na abstrakcję, która nie okaże się
przebraniem jednego przypadku użycia.

### Kształt

Nowy moduł **`../shared/transactional-outbox`**, obok `offline-jwt` i reszty bibliotek jądra.
Precedens na podział już istnieje: `adjustable-clock` (czysty rdzeń) + `infrastructure-micronaut-clock`
(adapter frameworkowy). Idziemy tą samą drogą, bo konsumenci siedzą na różnych stosach
i biblioteka **nie może wciągnąć Springa do Helidona**.

**Rdzeń — bez frameworka:**
- zapis wiersza **w transakcji wołającego** — API przyjmuje `Connection`, transakcją zarządza wołający
- `Dispatch` jako interfejs wysyłki — serwis dostarcza implementację (memes ma `KafkaTemplate`,
  inni surowego producenta), więc rdzeń nie zależy od żadnego klienta Kafki
- republisher z dyscypliną **delivered-first**: marka `published` dopiero po potwierdzeniu brokera
- batchowana retencja doręczonych, z limitem partii na przebieg
- **nazwa tabeli jako parametr** — biblioteka nie narzuca migracji (każdy serwis ma własne Flyway),
  tylko dokumentuje wymagany kształt tabeli

**Adapter Springowy** (osobny moduł): wpięcie w bieżącą transakcję przez `DataSourceUtils`,
`@Scheduled` republisher, publikacja po commicie.

### Kolejność i kryteria akceptacji

1. **Moduł rdzenia + własne testy biblioteki.** Kryterium: testy biblioteki przechodzą bez żadnego serwisu.
2. **memes migruje pierwszy** — to implementacja referencyjna, więc jeśli abstrakcja jest zła,
   wyjdzie tutaj. Kryterium: **wszystkie 145 testów memes zielone bez osłabiania asercji.**
   Outbox w memes niesie właściwości utwardzone w trzech paczkach (delivered-first, markowanie
   z callbacku, batchowana retencja, payload jako TEXT, walidacja dialu) — każda ma test,
   więc zielona suita jest twardym dowodem, że abstrakcja nic nie zjadła.
3. **comments przechodzi z wariantu B na bibliotekę.** Kryterium: 76 testów zielonych
   + nowy test, że zdarzenie przeżywa symulowany crash między commitem a wysyłką
   (czyli że B faktycznie awansowało do gwarancji outboxa).
4. **Regresja sagi**: `comments-events` niesie teraz dwa typy zdarzeń — offboarding
   nie ma prawa połknąć nowego.

### Czego świadomie nie robimy

- **collections nie dostaje outboxa** — jego potwierdzenia sagi idą przed commitem offsetu.
- **offboarding nie dostaje outboxa z biblioteki** — jego flaga `outcome_announced` jest wpięta
  w stan sagi, a nie w generyczną tabelę. Wciskanie tam biblioteki byłoby dopasowywaniem
  rzeczywistości do abstrakcji.

---

## Kolejność wykonania

```
A1 kontrakty  ─┐
A2 regresje   ─┼─► A3 weryfikacja + commity ─► B (paczka 10)
               ┘
```

Część A musi się domknąć przed B, bo B dotyka outboxa w memes i publikacji w comments —
czyli dokładnie tych plików, które A jeszcze weryfikuje.
