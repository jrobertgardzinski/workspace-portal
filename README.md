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
and the included `docker-compose.identity.yml`.

```bash
./infra-up.sh          # shared kernel install + portal jars + the whole stack up
./memes-up.sh          # just the memes world (gallery + comments + identity)
./infra-down.sh        # stop; -v drops the volumes
../shared/infra-smoke.sh   # full-stack proof over the whole stack
```

One service from the IDE, the rest in Docker — no image rebuild for a one-line change:

```bash
./dev-swap.sh memes          # stops the container, prints the env to paste into the Run Configuration
./dev-swap.sh run memes      # ...or runs the jar from target/ right here
./dev-swap.sh back memes     # the container again (stop your IDE process first — same port)
```

The env files live in `dev/local/` (same variable names as the compose environment); the
compose files publish the internal wiring for this — databases on 5434–5437, MinIO 9000, the
encoder 8087, Kafka's host listener 29092. Known limit: a container calling the swapped service
BY NAME (comments → `memes:8083`) will not find the host process; the browser will.

Build order: `(cd ../shared && ./mvnw install)` → `./mvnw clean install` here.
JDK 25, wrapper pinned to Maven 3.9.9.
