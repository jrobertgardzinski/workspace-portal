# portal-specs

One world in one process, and both of the portal's cross-service protocols run against it.

`portal.world.Portal` is the world: three services' rows in this process and their real use cases.
Over it sit the two buses — `closure.ClosureInOneProcess`, an orchestrated saga with a router
and confirmations, and `deletion.DeletionInOneProcess`, a choreography with no orchestrator at
all. The features they run are in `../specs`; the participants and the contracts they are held
to come from the `account-closure` and `meme-deletion` libraries as test-jars.

The two buses meet. Closing an account destroys memes, and destroying a meme announces it — so
`ClosureInOneProcess` builds a `DeletionInOneProcess` over the SAME `Portal` and hands the memes
participant its real `MemeEvents`. One closure therefore starts N cascades, after the pivot, with
nobody waiting for them; the specs drain that wire on their own step (`the cascade reaches every
part`) so a scenario can say what is true in between. Content ids are minted by
`world.ContentIds` as UUIDs, because the cascade's wire contract accepts nothing else and a
readable id is an announcement every hop would drop.

Over both buses sits one `races.Wire`: a queue per (topic, partition key, consumer group), which
is what a broker actually serialises, and a `Scheduler` that decides which of the records it could
hand over next actually goes next. `Scheduler.FIFO` is the `while (!inFlight.isEmpty())` loop that
was there before, named — every scenario in `../specs` runs under it and not one of them had to
change. `races.Explorer` is every OTHER answer: it walks the whole tree of legal orders and reports
the distinct states the portal can end in, which is what `../specs/races` holds.

Every participant here shares one `world.UnitsOfWork` — a unit of work that can be told not to
commit, which is what the three wiring points used to get `Runnable::run` for. A rollback puts the
rows back through `Portal.snapshot()`, and what a participant announced inside that transaction goes
out with it or not at all: the confirmation an atomic participant writes to its port, and the
cascade's announcements, leave through the same outbox the deployed stack writes them to.

What runs here: the decisions. What does not: Kafka, Postgres, HTTP, Spring, containers. A whole
account closure and a whole deletion cascade take milliseconds, which is what lets these
promises be stated without first deciding whether the portal ships as six services or one.

```bash
../mvnw -f pom.xml clean test
```

## One word per kind of double

Four words, four different things, and nothing else — no "stand-in", no "heap", no "atrapa":

| word | what it is | where |
|---|---|---|
| `mock` | Mockito; no behaviour, answers defaults | `mock(X.class)` |
| `Fake*` | a working double, rows in memory, held to the port's contract | `src/test/` |
| `InMemory*` | a REAL adapter that keeps its rows in RAM — not a double | `src/main/` |
| stub | a stand-alone service replacing a third party in a deployment | docker/k8s (IdP, SMS) |

This module is compliant: `world.FakeMemes`, `FakeComments`, `FakeFavourites` were `Heap*` until
the vocabulary was settled, and "heap" said where the rows live, not what kind of thing was
holding them — which is how it came to look like a third kind of double.

The two classes portal-specs consumes from the neighbouring repositories are compliant as well
since 28.09.2026: `collections-application`'s `FakeCollectionRepository` (which `FakeFavourites`
extends) and `offboarding-system`'s `FakeSagaStore` were `InMemory*` while being fakes, which is
the one name the table reserves for a production adapter. The rename was their repositories'
commit, not this one's — this note says the estate now spells it one way.
