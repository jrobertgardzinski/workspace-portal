# specs — the portal's own promises, the ones no single service can make

Every `.feature` here states something that is only true of the portal AS A WHOLE: how its parts
behave towards each other. Each service also has a `specs/` of its own, for what it promises about
its own content; those are the right place for anything one repository can state alone.

| level | what it proves | what it needs running |
|---|---|---|
| this directory | the orchestrator and the three participants, wired in ONE process | nothing |
| `<service>/specs/` | one service's own axis | nothing |
| `e2e/features/` | the promise to the person, over the deployed portal | the whole stack |

## Why the middle column says "nothing"

The runner (`account-closure-specs`) builds the real orchestrator (`EventsRouter`) and the real
participants (`memes_account-closure`, `comments_account-closure`,
`collections_account-closure`), hands them heap-only adapters and delivers the messages between
them by calling methods. No broker, no database, no HTTP, no container. A whole account closure
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

Not here: what a meme, a comment or a saved reference IS, how one is posted, or what a single
participant does with a command it can answer on its own. Those live one level down and are
already covered there.

## Literals are samples, not placeholders

`alice@example.com`, `2 memes, 3 comments and 4 favourites` — the counts are in the file because
the scenarios assert on them and because "some content" would hide which part held what. The
policy words (`ANONYMIZE_AUTHOR`) are the real vocabulary of `PurgeRule`, not stand-ins: a release
that renames one is SUPPOSED to turn this file red. Same contract as `microservice-security/specs`.

## Running them

```bash
# from this repo root
./mvnw -pl account-closure-specs -am test
```
