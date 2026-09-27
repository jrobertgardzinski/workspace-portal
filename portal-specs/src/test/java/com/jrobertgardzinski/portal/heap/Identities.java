package com.jrobertgardzinski.portal.heap;

import com.jrobertgardzinski.identity.UserId;

import java.util.UUID;

/**
 * The identity security would have minted for an address. Gherkin names people by address,
 * because that is how the portal's own people talk about each other; nothing inside the portal
 * holds one any more. The translation happens here, once, and the steps are the only callers.
 */
public final class Identities {

    public static UserId idOf(String email) {
        return new UserId(UUID.nameUUIDFromBytes(("user:" + email).getBytes()));
    }

    private Identities() {
    }
}
