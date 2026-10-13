# account-closure-2

The runner for [`../specs-2`](../specs-2/README.md): the portal's promises about letting go of a
person's content, stated before anybody decides what the portal is built of.

It is the second runner over the same two subjects as `portal-specs`, and it is not its successor.
The two answer different questions and both answers are worth having:

|  | `portal-specs` | here |
|---|---|---|
| assumes | the design: a thing that commands the parts, collects their answers, waits, gives up | nothing |
| builds | the real router and the real three participants, plus their adapters' stand-ins | the ports, the fakes beside them, and the use cases |
| depends on | `*-application`, `*_account-closure`, `offboarding-*` | `*-domain` + `*-system`, and nothing above them |
| costs | ~5000 lines of Java under 312 lines of Gherkin | ~1900 under ~450, the state machine and its own file included |

That dependency line is the whole constraint, and it is what shaped the module: a suite that may
not reach above `*-system` cannot borrow anybody's orchestration, so it has to say out loud what it
is standing in for.

## What stands in for what

- **`world.ClosureProtocol`** — the closure as a state machine: HIDING, DESTROYING, RESTORING, DONE,
  WITHDRAWN; a part reports its share hidden, destroyed or restored; the closure is given up on.
  Each state accepts some reports and refuses the rest, and a refused report changes nothing. It
  has a feature file of its own, [`src/test/resources/closure-protocol.feature`](src/test/resources/closure-protocol.feature),
  and `../specs-2` borrows its state names ("Given the closure has reached DESTROYING"). It is the
  PROTOCOL and not the transport: a monolith keeps it inside one transaction, separate services
  keep it with a saga, and the same scenarios are meant to run against either. What carries a
  report, how long anybody waits for a part and who gives up are outside it on purpose.
- **`world.Closures`** — this suite's own `ContentPurge`, which is what `StartAccountDeletion` hands
  a closure to. It reads the leaver's id off the account row ONCE and keeps it, because destroying
  happens after the account is gone and there is then no row left to read it from.
- **`world.Accounts`** — `FakeUserRepository` with one switch in front of it: an account that
  refuses to be deleted. The scenario the whole order exists for needs one.
- **`world.MemeAnnouncements` / `world.CommentAnnouncements`** — what one part told the others,
  written down, so a step can say "memes announced" and a later step "collections has heard", and
  the two are visibly different moments. Not fakes of the ports: this is the suite's own wiring.

Everything else is the real thing. The use cases are `memes-system`, `comments-system`,
`collections-system` and `security-system`'s own; the stores are the fakes published beside their
ports as test-jars; `DeleteAccount` is handed all nine repositories it names, because none of those
tables has a foreign key and whatever is not named outlives the account.

Two ports stay on Mockito — `TagRepository` and `MemeContentIndex` — by the verdict of 2026-09-28:
what they hide is one service's own promise, a level below anything these files state.

## The two rules the step definitions keep

**One step is one piece of work OR one assertion, never both.** No `When` moves the chain along and
checks something at the same time, and none quietly does two parts' work. The one licence is a
`Given`: "the closure has reached DESTROYING" plays every part's hide and the account's going
through the real use cases, because the moments in between are what `closure-protocol.feature` is
about, and a scenario in `../specs-2` starts where its own claim starts. Without that split an
order the files promise could be broken by the code and the files would still pass, because nothing
would ever observe the moment in between.

**An assertion reads a fake directly**, with no helper layer between a `Then` and
`FakeMemeErasure` / `FakeCommentErasure` / `FakeCollectionRepository` / `FakeUserRepository`. And
**"gone" means "not in the store", never "the repository cannot see it"**: those repositories hide a
hidden row exactly as the deployed adapters' active views do, so an assertion written through
`find` or `findMetadata` would pass the moment something was HIDDEN and claim it had been
destroyed. Writing them the strict way immediately found a drift in two of the fakes — a destroyed
row left its mark behind in a map, which in the schema (where the status is a column of the row) can
never happen.

```bash
../mvnw -f pom.xml clean test
```
