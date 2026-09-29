# specs/races — every order the transport allows, and what came of each

A `.feature` file next door says what the portal does. A file in here says what the portal does
**on every other order of the same events** — and, where the answer is more than one thing, how
many things it is.

## Why the level exists

Both of the portal's protocols are tested by draining a queue to a fixed point, one message at a
time, in the order the list happened to hold them. That is one schedule. Production has no such
order: the saga's commands are keyed by the leaver, the cascade's announcements by the meme, three
participants and an orchestrator are four independent consumer groups, and the broker may hand any
record over twice. Every promise next door has only ever been proven under the one schedule the
loop produced.

The runner (`portal-specs`, package `races`) keeps one queue per *(topic, partition key, consumer
group)* — which is what a broker actually serialises — and walks the tree of what could happen
next. `Scheduler.FIFO` is the old loop, named; the explorer is every other answer.

## What a file here is

One per seed. Each records the **distinct end states** of that situation and whether every law
held. It does **not** record how many schedules there were or which one led where — those change
whenever the search changes, and a file that churns is a file nobody reads. The working copy with
counts and example schedules is written to `portal-specs/target/races/*.detail` on every run.

**A diff here is a decision.** A new end state means the portal can now finish a situation in a way
it could not finish before. Somebody has to read it and write the sentence saying whether that is
all right — or fix it. Re-approve with `-Draces.approve=true`, after reading the difference.

## Laws, which are not scenarios

A scenario asserts at the end of one schedule. A law is asked after **every step of every**
schedule, and a law that breaks comes with the schedule that breaks it:

| law | what breaking it would mean |
|---|---|
| nobody is left pointing at something that is gone | a saved reference outliving the thing it names — asked once the wire is quiet, because between a deletion and its cascade the pointer IS there, and that is the trade the portal made on purpose |
| no row is created by either protocol | a compensation and an erasure disagreeing about what a row is |
| the case is closed and nothing is still reserved | content nobody can see and nothing will ever free: the only two things that take a mark off are both past |
| the portal decides once | two different verdicts for one closure — the once-latch slipped |
| purged means the portal holds nothing of them | a person who asked to be forgotten and, on some order of the same events, was not |
| no thread outlives the meme it hangs under | the cascade lost, on some order |
| a part never confirms more than it is holding | a number leaving the portal that was never true |

## What this level cannot decide

Three different things travel under the word "race", and only the first is answerable here:

| kind | example | where it belongs |
|---|---|---|
| **order** — two records, no guarantee which is first | the cascade reaching a hop before or after the saga's erase | here |
| **the row** — two transactions writing one row | two first casts of one vote losing the `MERGE` | a JDBC test per service, as `VoteUpsertTest` does it |
| **the consumer** — rebalances, offsets, two pods | two sweepers selecting the same overdue saga | the consumer-loop tests and `e2e/` |

And the honest limit of the first: this proves what the PORTAL's decisions do under every order the
model allows. It does not prove that Kafka's ordering is what the model says, and it never will —
that is what the pacts and `e2e/` are for.
