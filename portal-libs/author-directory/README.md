# author-directory

The names behind user ids. A content service holds its rows by `UserId` and, when it shows who
posted something, asks this port for the names of a batch of ids:

```java
Map<UserId, AuthorName> names = directory.namesOf(idsOnThisPage);   // absent = "deleted account"
```

`SecurityAuthorDirectory.overHttp(securityUrl, mapper, ttl, clock, onFailure)` asks security's
`GET /users?ids=` in batches of 100 and caches every answer — absences included — for the TTL.
When security cannot be reached the listing goes on with what the cache holds and `onFailure`
is told; a page of content never fails because names could not be fetched.

The same port has other adapters elsewhere: a heap in the one-process runner, a join in a
monolith. `AuthorDirectoryContractTest` (test-jar) is what each of them must pass. The pact in
`pacts/` is verified by microservice-security against its real controller.
