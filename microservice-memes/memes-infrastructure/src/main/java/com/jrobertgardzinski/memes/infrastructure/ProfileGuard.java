package com.jrobertgardzinski.memes.infrastructure;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses a production-path start without a declared deployment profile. The bare
 * application.properties holds only the service's invariants; everything environment-shaped is
 * the deployment's to declare — so a start that names no profile is a start nobody decided, and
 * the most common way it happens is a FORGOTTEN profile, which is exactly when strictness
 * matters (default-deny).
 *
 * <p>And the other half of the same rule, because a default-deny that only covers the harmless
 * variable is not one: a start that declares {@code prod} must also say where its DATABASE is.
 * {@code spring.datasource.url} falls back to an in-memory H2, so "prod, but DB_URL was forgotten"
 * boots happily, runs Flyway against memory, serves the gallery and answers /actuator/health 200 —
 * the DataSource really is up — and loses every meme, vote and tag on the next restart. With
 * MEMES_BLOB_STORE=s3 the images survive in the bucket while the metadata does not, which leaves
 * orphaned blobs and an empty gallery. The dev path keeps the H2 fallback, which is what it is for.
 *
 * <p>Registered in {@code main()} and ONLY there — wiring, not classpath sniffing: tests boot
 * the context through SpringBootTest and never enter main, so the guard sits exactly on the one
 * path (java -jar, the container) where an undeclared start can happen.
 */
final class ProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Set<String> DEPLOYMENT_PROFILES = Set.of("dev", "test", "prod");

    /** What {@code spring.datasource.url} falls back to when DB_URL is not set. */
    private static final String IN_MEMORY = "jdbc:h2:mem";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String[] active = event.getEnvironment().getActiveProfiles();
        List<String> declared = Arrays.stream(active)
                .filter(DEPLOYMENT_PROFILES::contains)
                .toList();
        if (declared.isEmpty())
            throw new IllegalStateException("no deployment profile declared (active: "
                    + Arrays.toString(active) + ") - start with SPRING_PROFILES_ACTIVE=dev|prod");
        if (declared.contains("dev") && declared.contains("prod"))
            throw new IllegalStateException("both 'dev' and 'prod' are active ("
                    + Arrays.toString(active) + ") - an ambiguous start is worse than a refused one");
        if (declared.contains("prod")
                && event.getEnvironment().getProperty("spring.datasource.url", "").startsWith(IN_MEMORY))
            throw new IllegalStateException("a 'prod' start resolved its database to an in-memory H2"
                    + " - set DB_URL, or this instance serves the gallery and forgets it on restart");
    }
}
