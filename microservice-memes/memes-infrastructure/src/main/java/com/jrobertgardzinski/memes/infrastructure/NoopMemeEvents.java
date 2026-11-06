package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Broker-less stand-in (tests, bare dev runs): deleted memes are not announced anywhere. */
@Component
@ConditionalOnProperty(name = "memes.kafka-enabled", havingValue = "false", matchIfMissing = true)
class NoopMemeEvents implements MemeEvents {

    @Override
    public void memeDeleted(String memeId) {
        // nothing to tell — no other service is listening in this environment
    }
}
