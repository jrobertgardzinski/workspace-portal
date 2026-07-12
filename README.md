# workspace-portal

The **PORTAL product**: meme gallery (`microservice-memes`), comments
(`microservice-comments`), the user's saved refs (`microservice-user-collections`
+ its own-origin UI), the account-deletion process manager
(`microservice-offboarding`) and the PNG→WebP encoder (`microservice-image`,
Python). Each sub-directory is an independent git repository, gitignored here;
this workspace versions only the aggregator `pom.xml`, the compose file and the
up/down scripts.

The portal runs on the **shared kernel** — identity, mail chain, stub IdP,
notification channels and every shared library — which lives in the sibling
workspace `../shared` (repo `workspace-shared`) and is consumed through `~/.m2`
and the included `docker-compose.identity.yml`. The F1 game is the OTHER product
(`../formula`); the two share only identity — one account, one token.

```bash
./infra-up.sh          # shared kernel install + portal jars + the whole stack up
./memes-up.sh          # just the memes world (gallery + comments + identity)
./infra-down.sh        # stop; -v drops the volumes
../shared/infra-smoke.sh   # full-stack proof (needs the formula world up too)
```

Build order: `(cd ../shared && ./mvnw install)` → `./mvnw clean install` here.
JDK 25, wrapper pinned to Maven 3.9.9.
