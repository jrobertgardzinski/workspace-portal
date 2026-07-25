# Hosting portalu na k3s — 2 najlepsze opcje cena/jakość

Stan na 2026-07-25. Założenia: pełny stack portalu (5 serwisów portalu + shared
identity: security, email, stuby, Kafka, Postgres, MinIO) **z observability**
(Prometheus, Grafana, Tempo, Loki) → ~7–8 GB RAM, 40–80 GB dysku. Horyzont:
~3 miesiące. Formula NIE wchodzi w zakres. Do tego niezależnie od opcji:
domena (.pl ~20–30 zł pierwszy rok), TLS darmowy (Let's Encrypt/cert-manager).

---

## Opcja 1 — GCP + kredyt startowy $300 (kwartał za 0 zł)

**Maszyna:** e2-standard-4 (4 vCPU, 16 GB) + dysk 50 GB + IPv4 → ~$100/mies.,
w całości pokryte kredytem **$300 na 90 dni** dla nowych kont.

**Koszt za 3 miesiące: 0 zł** (kredyt niemal idealnie pokrywa kwartał).

Zalety:
- 16 GB RAM = observability oddycha, zero ściskania heapów JVM;
- hyperscaler w CV obok k3s; manifesty przenośne 1:1 na GKE
  (zonal GKE ma control plane objęty osobnym darmowym kredytem, węzły płatne);
- po wygaśnięciu kredytu decyzja: zjazd na e2-standard-2 (~230 zł/mies.),
  przenosiny (opcja 2) albo gaszenie.

Wady / uwagi:
- wymaga karty przy rejestracji; pilnować końca kredytu (ustawić budget alert
  w GCP pierwszego dnia!);
- po kredycie cena hyperscalera: ~650–700 zł/kwartał na e2-standard-2;
- spot/preemptible NIE — Google może ubić maszynę w środku demo.

## Opcja 2 — Hetzner CX32 (~100 zł za kwartał, bez terminu ważności)

**Maszyna:** CX32 (4 vCPU, 8 GB, 80 GB dysku) → ~€7/mies. (~30 zł).

**Koszt za 3 miesiące: ~90–100 zł** (+ ~20% za snapshoty/backup — grosze).

Zalety:
- najlepsza cena za GB w ogóle; brak "zegara" kredytu — stack może stać latami;
- prosty billing, europejskie DC (Falkenstein/Helsinki), sensowny transfer
  w cenie.

Wady / uwagi:
- 8 GB = z pełnym observability jest ciasno: wymagane przycięte `-Xmx` JVM-ów,
  jedna instancja Postgresa z osobnymi bazami (granice serwisów zostają:
  osobne bazy + credentiale), observability jako wyłączalny overlay;
- wariant ARM CAX21 (~€6.5, 8 GB) minimalnie tańszy, ale wymaga obrazów
  multi-arch (buildx) — na pierwsze podejście lepiej x86 (CX32);
- mniej "efektowny" w CV niż hyperscaler.

---

## Rekomendowana ścieżka

Start na **GCP z kredytem** (opcja 1) — kwartał za darmo na wygodnej maszynie,
nauka chmury gratis. Pod koniec kredytu przenosiny na **Hetznera** (opcja 2) —
te same manifesty k3s, koszt spada do ~30 zł/mies. Dzięki temu decyzja
"czy trzymać dłużej" kosztuje 100 zł/kwartał, nie 700 zł.

Odrzucone po drodze: AWS (t4g.large ~700–780 zł/kwartał, EKS +$73/mies. za sam
control plane), Azure (B2ms ~780–880 zł/kwartał, kredyt $200 tylko na 30 dni),
Oracle Cloud Free Tier (0 zł i 24 GB RAM, ale loteryjna dostępność ARM
i wymóg obrazów arm64 — za dużo ruchomych części na pierwsze podejście),
spot/preemptible (ubijalne maszyny nie nadają się pod publiczne demo z QR).
