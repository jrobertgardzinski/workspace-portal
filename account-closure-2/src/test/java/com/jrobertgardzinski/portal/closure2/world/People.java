package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.email.domain.Email;
import com.jrobertgardzinski.identity.UserId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who is who. Gherkin names people by first name; identity names them by address; the three
 * content parts name them by the id identity minted at registration. This is the one place the
 * three names meet.
 *
 * <p>It REMEMBERS the id. The portal's only bridge from an address to an id is the user row, and
 * the steps have to read the content fakes after that row is gone — so the suite keeps the id the
 * way a test may and the portal may not. Which is the whole "two keys" rule of the feature file.
 */
public final class People {

    private final Map<String, UserId> ids = new LinkedHashMap<>();

    public static Email emailOf(String name) {
        return Email.of(name + "@example.com");
    }

    void registered(String name, UserId id) {
        ids.put(name, id);
    }

    public boolean knows(String name) {
        return ids.containsKey(name);
    }

    public UserId idOf(String name) {
        UserId id = ids.get(name);
        if (id == null) {
            throw new IllegalStateException(name + " was never registered in this scenario");
        }
        return id;
    }
}
