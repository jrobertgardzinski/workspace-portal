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

Two classes portal-specs consumes are NOT compliant and are named `InMemory*` while being fakes:
`collections-application`'s `InMemoryCollectionRepository` and `offboarding-system`'s
`InMemorySagaStore`. They live in repositories of their own with their own history (nine-plus
call sites in offboarding alone), so renaming them is their repositories' commit, not this one's.
