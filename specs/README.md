# specs — the portal's own promises, the ones no single service can make

Every `.feature` here states something that is only true of the portal AS A WHOLE: how its parts
behave towards each other. Each service also has a `specs/` of its own, for what it promises about
its own content; those are the right place for anything one repository can state alone.

Two protocols live here, one world: `account-closure.feature` (an orchestrated saga) and
`meme-deletion.feature` (a choreography with no orchestrator). They share `portal.world.Portal` —
the three services' rows and use cases — and each brings its own bus.

| level | what it proves | what it needs running |
|---|---|---|
| this directory | the orchestrator and the three participants, wired in ONE process | nothing |
| `races/` | the same, on EVERY order the transport allows — and how many ends there are | nothing |
| `<service>/specs/` | one service's own axis | nothing |
| `e2e/features/` | the promise to the person, over the deployed portal | the whole stack |

The second row is not a fourth kind of test; it is the same two protocols with the ORDER taken
away. Every `.feature` here is drained one message at a time in the order a list happened to hold
them, and production orders almost none of it: `races/README.md` says what that costs and what it
can and cannot decide.

## Why the middle column says "nothing"

The runner (`portal-specs`) builds the real orchestrator (`EventsRouter`) and the real
participants (`memes_account-closure`, `comments_account-closure`,
`collections_account-closure`) — and, for the deletion cascade, the real teardown (`DeleteMeme`)
and both hops (`comments_meme-deletion`, `collections_meme-deletion`) — hands them in-memory fakes and delivers the messages between them by calling methods. No broker, no database, no HTTP, no container. A whole account closure
runs in milliseconds.

That is not a shortcut, it is the point. Whether the portal is deployed as six services or as one
process is a separate decision, and these promises have to hold either way — so the file that
states them must be runnable without having made it. The day somebody assembles the portal into a
single deployable, this directory is the spec that says whether it still behaves like the portal.

## What belongs here and what does not

Here: the ORDER (nothing is destroyed before the last answer), the WAITING (a silent part holds
the case open), the GIVING UP (the account comes back with its content), and anything where the
parts differ from each other under one command — an administrator's conditions keep the comments
while destroying the memes, and mean nothing at all to the favourites.

Here too, and only here: where the two protocols TOUCH. A closure destroys memes and a destroyed
meme starts a cascade, so one closure takes threads and saved pointers belonging to people who
are not leaving — uncounted by any confirmation, after the pivot, with nothing to compensate
with. Neither `account-closure.feature` nor `meme-deletion.feature` could state that alone, and
neither could any single service: it is the last five rules of `account-closure.feature`. The
last of them is the other direction of the same seam — a comment the CLOSURE destroys is announced
on the cascade's topic, so a pointer at it is collected the way a pointer at a deleted meme's
comment already was.

Not here: what a meme, a comment or a saved reference IS, how one is posted, or what a single
participant does with a command it can answer on its own. Those live one level down and are
already covered there.

## Literals are samples, not placeholders

`alice@example.com`, `2 memes, 3 comments and 4 favourites` — the counts are in the file because
the scenarios assert on them and because "some content" would hide which part held what. The
policy words (`ANONYMIZE_AUTHOR`) are the real vocabulary of `PurgeRule`, not placeholders: a release
that renames one is SUPPOSED to turn this file red. Same contract as `microservice-security/specs`.

## Running them

```bash
# from this repo root
./mvnw -pl portal-specs -am test
```
