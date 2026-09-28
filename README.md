# portal-libs

The words the portal's repositories share with each other — and with nobody else.

| module | what it is |
|---|---|
| `purge-rule` | what may become of a leaver's content: delete it, keep it anonymised, or let the community decide. Three words and their grammar. |
| `author-directory` | the names behind the author ids a meme and a comment carry. |
| `meme-deletion` | the cascade a deleted meme sets off: its thread goes, and every saved pointer at both. |

## Why this repository exists

The estate has a shared kernel — `../../shared` — and it is not this. The kernel is what the two
PRODUCTS share: identity, and the generic mechanisms any service anywhere would want. A unit of
work. An outbox. An envelope. A clock. An id.

What lives here speaks the portal's own domain. The F1 game has no word for a meme, an author or
a leaver's gallery, and it never will.

These three sat in `shared/` until 2026-09-28, and the telling detail is that **not one of them
was ever listed among the kernel's libraries in `shared/CLAUDE.md`**. They arrived one at a time,
each an obvious place for one more cross-repository word — because there WAS no other place.
Four portal repositories need a vocabulary none of them can own, and the only home the estate
offered was the kernel. So the kernel learned what a meme was, one library at a time, and nobody
ever had to decide that it should.

## The rule, so the next one lands correctly

**Generic mechanism → the kernel. Product vocabulary → here.**

Not "how many consumers does it have today". By that test `user-id` would leave the kernel the
day the game stops importing it, and `unit-of-work` — which only the portal uses right now —
would already be here, though nothing in its name or its API knows what a meme is.

`account-closure` is the case that shows where the line runs. It reads like portal vocabulary and
it is not: `microservice-security` imports its `ClosureMessages` and `ClosureInitiator`, so it is
the contract BETWEEN identity and the portal — and identity is shared with the game. It stays in
the kernel, and moving it here would have pointed the kernel at a product.

## Which way the dependencies run

One way, and it is the whole point:

```
portal-libs  ──depends on──▶  shared kernel (unit-of-work, user-id)
     ▲
     └──used by── microservice-memes, -comments, -user-collections, -offboarding, portal-specs
```

Nothing in the kernel depends on anything here. If that ever has to change, the thing that wants
to move is the module, not the arrow.

## Building

A pure aggregator, **not** the parent of its modules — every one stays buildable on its own, the
estate's rule everywhere. Build the kernel first, since these depend on it:

```bash
(cd ../../shared && ./mvnw install)   # user-id, unit-of-work into ~/.m2
../mvnw -f pom.xml clean install      # this repository, from the portal workspace
```

The portal reactor (`../pom.xml`) lists this repository as its first module, so
`(cd ..; ./mvnw install)` builds it ahead of everything that speaks it.
