# meme-deletion

The words three services agree on when a meme is deleted, and the promises each hop of the
cascade owes the ones after it.

```
memes            MEME_DELETED      ──▶ comments      drops the thread
comments         COMMENTS_DELETED  ──▶ collections   drops the refs
memes            MEME_DELETED      ──▶ collections   drops the refs to the meme itself
```

Nobody orchestrates this and nothing confirms: each service reacts to the hop before it. There
is **no compensation** — a hop that gives up leaves dead rows, which the UI already renders as
such. That is the accepted worst case of a choreography, stated rather than fixed.

## What is here

`DeletionMessages` — the two message names and the fields they carry. Note that the envelope id
is spelled `eventId` on MEME_DELETED and `id` on COMMENTS_DELETED: both names are in the
vocabulary, because unifying the wire would break the pacts that pin `eventId`.

`MemeDeleted` and `CommentsDeleted` — the parsed events. `of(...)` is the only way in, and it is
where a deletion that names nothing is turned away: an id is a canonical 36-character UUID, at
the event boundary, never a judgement on ids already stored. A COMMENTS_DELETED keeps the
comment ids that are usable and counts the rest, so a partially mis-produced list still drops
what it honestly can.

`DeletionOutcome` — `Dropped(rows)` or `Nothing`, one shape for both hops.

`CascadeHopContractTest` and `AtomicHopContractTest` (test-jar) — what a hop must prove: it is
idempotent, it drops nothing on an event that names nothing, and, if it announces, it announces
inside the same unit of work as the drop.

## What is NOT here

The topics. They are transport, they differ per deployment, and they stay in each service's
infrastructure beside the pact tests that pin them.
