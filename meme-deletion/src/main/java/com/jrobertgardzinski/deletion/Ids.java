package com.jrobertgardzinski.deletion;

import java.util.UUID;

/**
 * What counts as an id on the cascade's wire. A canonical UUID is 36 characters, and both event
 * contracts spell their ids as uuids, so anything else is a mis-produced event rather than an id
 * a service has simply never seen — {@link UUID#fromString} alone would accept lax forms like
 * {@code 1-1-1-1-1}.
 */
final class Ids {

    private static final int UUID_LENGTH = 36;

    static boolean usable(String value) {
        if (value == null || value.length() != UUID_LENGTH) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException notAnId) {
            return false;
        }
    }

    private Ids() {
    }
}
