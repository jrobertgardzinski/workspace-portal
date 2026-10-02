# specs-2 — what has to be true before anybody decides how the portal is built

Two files, and both of them state the same kind of thing: the ORDER in which the parts of the
portal let go of a person's content, and what is true between any two steps of it.

| | `../specs` | here |
|---|---|---|
| question | does the portal keep its promises with THIS design? | what would ANY design have to satisfy? |
| vocabulary | the parts answer, a case closes, a case is given up on | content is hidden, the account goes, content is destroyed |
| what is built | the real router and the real three participants | the ports, their fakes, and the use cases |
| who reads it | somebody checking the design works | somebody who wants the chain and its branches in five minutes |

Neither replaces the other. `../specs` proves the portal AFTER the decision — there is a thing
that commands the parts, collects their answers, waits and gives up — and it costs about five
thousand lines of Java under three hundred lines of Gherkin to say so. These two files are what is
left when that machinery is taken away, and what is left turns out to be the whole of the promise:

> Nothing is destroyed until the account is gone. If the account cannot go, everything comes back.

With one database for the whole portal, both sentences are true for nothing: hiding content is an
uncommitted delete and bringing it back is a rollback. With the parts in separate processes,
something has to be built to keep them. Which of the two it is going to be is not decided, and
this is the file that the decision will have to answer to rather than the other way round.

## The words

`hidden` / `out of sight` — the content is not in the gallery, not in the thread, not in the list,
and is still there. `back` — it is where it was, as it was. `destroyed` / `gone` — it is not held
anywhere any more. `the account is gone` — the row and the eight other tables keyed by the same
address, none of which cascades.

What is deliberately NOT in these files: any word for the machinery. No phases, no confirmations,
no quorum, no retries, nothing about what travels between the parts. A step saying
`When memes has hidden alice's content` means that piece of work happened and nothing else — it is
as true of a method call in one process as of a message somebody acknowledges an hour later.

## The holes, named on purpose

Three things these files state and do not settle, each marked in the file where it bites:

- **Who removes the account** once every part has hidden its share. Something has to notice that
  all of them are done. That is the decision nobody has made, so the runner ticks three boxes and
  deletes the account on the third tick.
- **What finally destroys content** left hidden when the account is already gone. Nothing will ever
  come back for it: the account that named it does not exist.
- **Whether content created during a closure should survive it.** It does today, because a part may
  only destroy what it hid, and the file says so instead of calling it either right or wrong.

## Running them

```bash
../mvnw -f ../account-closure-2/pom.xml clean test
```

No broker, no database, no HTTP, no container — the two files take milliseconds. The runner
(`../account-closure-2`) depends on `*-domain` and `*-system` and on nothing else: the ports, the
fakes published beside them, and the use cases. Everything above those — the adapters, the
participants, the thing that routes between them — is the part that has not been decided.
