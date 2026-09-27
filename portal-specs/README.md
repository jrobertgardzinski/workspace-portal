# portal-specs

One world in one process, and both of the portal's cross-service protocols run against it.

`portal.heap.Portal` is the world: three services' rows on the heap and their real use cases.
Over it sit the two buses — `closure.ClosureInOneProcess`, an orchestrated saga with a router
and confirmations, and `deletion.DeletionInOneProcess`, a choreography with no orchestrator at
all. The features they run are in `../specs`; the participants and the contracts they are held
to come from the `account-closure` and `meme-deletion` libraries as test-jars.

What runs here: the decisions. What does not: Kafka, Postgres, HTTP, Spring, containers. A whole
account closure and a whole deletion cascade take milliseconds, which is what lets these
promises be stated without first deciding whether the portal ships as six services or one.

```bash
../mvnw -f pom.xml clean test
```
