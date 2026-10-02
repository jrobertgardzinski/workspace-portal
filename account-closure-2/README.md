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
| costs | ~5000 lines of Java under 312 lines of Gherkin | ~1400 under ~290 |

That dependency line is the whole constraint, and it is what shaped the module: a suite that may
not reach above `*-system` cannot borrow anybody's orchestration, so it has to say out loud what it
is standing in for.

## What stands in for what

- **`world.Checklist`** — three boxes and one callback: each part reports its share hidden, and the
  third report deletes the account. **It is not a design.** Something in the portal has to notice
  that every part is done; what that something is, how long it waits and what it does when a part
  never answers are the decisions `../specs-2` is written before. In a single-process portal the
  same role belongs to a transaction's commit.
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

**One step is one piece of work OR one assertion, never both.** No step moves the chain along and
checks something at the same time, and none quietly does two parts' work — which is why the feature
files spell the three parts out three times instead of saying "every part". Without it an order the
file promises could be broken by the code and the file would still pass, because nothing would ever
observe the moment in between.

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
