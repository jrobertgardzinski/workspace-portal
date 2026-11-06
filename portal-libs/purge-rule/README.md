# purge-rule

What happens to a piece of a leaver's content when their account closes: **delete it**, **keep it
with the author anonymised**, or **decide by popularity** — content that earned at least
`minScore` survives anonymised ("the community's, not the author's"), the rest disappears.

```
DELETE
ANONYMIZE_AUTHOR
KEEP_POPULAR_ANONYMIZED:100
```

Three words, their grammar, and the one thing a caller must not get wrong when a fourth word
arrives on the wire.

## Who speaks it, and who deliberately does not

The gallery (`microservice-memes`) and the comment threads (`microservice-comments`) — the same
pair that already shares `voting`, for the same reason: both hold content that **has an author**,
so both have something to anonymise.

Nobody else, and that is a decision rather than an omission:

| who | why not |
|---|---|
| `microservice-user-collections` | a saved reference is a pointer at somebody else's meme — nothing to anonymise, no popularity of its own |
| `microservice-security` | carries the leaver's choices as an opaque `Map<String, String>` and is right not to understand them |
| `microservice-offboarding` | only ferries them from the one to the others |

Which rule is the **default** is not this library's business and differs on purpose: the gallery
deletes, the threads anonymise, because a thread with holes in it is worth less than a gallery
with holes in it. The estate's twin-law test exempts that difference by name.

## A refusal never repeats what it was given

`parse()` reads a rule off the wire, and the wire carries whatever the leaver typed into the
deletion request — kilobytes of it, newlines included, which in a log file is a forged second
line. So the exception states the **length** and the **shape** and never the text:

```java
PurgeRule.parse("delete everything, my number is 601234567");
// not a purge rule (41 chars, looks like 'DELETE?')
```

`shapeOf` is that shaping, and it is public because it is this vocabulary's own knowledge. Whole
tokens only: a per-character filter would keep every digit and every capital, which is exactly the
alphabet of phone numbers and SHOUTED e-mail addresses. Everything unrecognised collapses to one
`?`, so a log line says whether the text was *almost* a rule and nothing about what it held.

This used to live in the two services that happened to catch the exception — the type handed out a
loaded gun and kept the safety catch somewhere else. A new caller logging `getMessage()` the
obvious way is now safe, which is the only kind of guard that survives a new caller.

## Why it is a library at all

It was one type written twice, and the estate's own rule was "wait for a third consumer". What
finally earned the extraction was not the second copy but the **third and fourth spellings**: each
service's saga participant had grown its own regex for the same grammar, and the two did not quite
agree with `parse()`. Four spellings of one grammar, two of them inconsistent, is not a
duplication you can watch — so it stopped being one.

Pure Java, no dependencies, like every word this estate shares.
