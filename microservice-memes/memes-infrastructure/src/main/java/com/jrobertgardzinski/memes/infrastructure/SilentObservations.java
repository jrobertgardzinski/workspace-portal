package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.domain.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What this service does when nothing is watching: it goes on working.
 *
 * <p>This is the boundary made real rather than described. A service with no watcher is not a
 * broken service, so the port must have an answer even when the adapter is gone — and the day the
 * observability adapter becomes its own Maven module, removing that module from the assembly has
 * to leave the sagas passing. Without this bean it would not: the context would fail to start on a
 * missing dependency, and an architecture test written to prove the boundary would instead be
 * proving that the assembly forgot something.
 *
 * <p>It states nothing anywhere on purpose — not even a log line. A "nobody is listening" warning
 * once a minute is itself a watcher, and a noisy one.
 */
@Configuration
class SilentObservations {

    @Bean
    @ConditionalOnMissingBean(Observations.class)
    Observations<Observation> silence() {
        return Observations.silent();
    }
}
