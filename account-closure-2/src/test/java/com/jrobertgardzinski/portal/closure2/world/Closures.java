package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.email.domain.Email;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.security.domain.entity.User;
import com.jrobertgardzinski.security.domain.port.ContentPurge;
import com.jrobertgardzinski.security.domain.repository.UserRepository;
import com.jrobertgardzinski.security.domain.vo.AccountClosure;
import com.jrobertgardzinski.security.system.account.DeleteAccount;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This suite's own {@link ContentPurge}: what {@link
 * com.jrobertgardzinski.security.system.account.StartAccountDeletion} hands the closure to.
 *
 * <p>It does two things and nothing more. It reads the leaver's id off the user row ONCE, here,
 * and keeps it — the three content parts speak ids, identity speaks addresses, and the row is the
 * only bridge; since destroying happens after {@link DeleteAccount} there is no row left to read
 * it from later. And it opens a {@link ClosureProtocol} for the closure, which is what every report of
 * a part goes to and what lets the account go.
 *
 * <p>Nothing is commanded from here: the steps of the feature file are the parts hearing about the
 * closure, one hop per step, so that the file can say what is true between any two of them.
 */
public final class Closures implements ContentPurge {

    /** One open closure: what was asked, whose content it is, and where the protocol has got to. */
    public record Closure(AccountClosure request, UserId leaver, ClosureProtocol protocol) {
    }

    private final UserRepository users;
    private final DeleteAccount deleteAccount;
    private final Map<String, Closure> open = new LinkedHashMap<>();

    Closures(UserRepository users, DeleteAccount deleteAccount) {
        this.users = users;
        this.deleteAccount = deleteAccount;
    }

    @Override
    public void begin(AccountClosure request) {
        Email email = request.target();
        UserId leaver = users.findBy(email).map(User::id).orElseThrow(() -> new IllegalStateException(
                "no account under " + email.value() + ": nothing to find the content by"));
        ClosureProtocol protocol = new ClosureProtocol(new ClosureProtocol.Account() {
            @Override
            public void go() {
                deleteAccount.execute(email);
            }

            @Override
            public void unlock() {
                users.clearPendingDeletion(email);
            }
        });
        open.put(email.value(), new Closure(request, leaver, protocol));
    }

    public boolean isOpen(Email email) {
        return open.containsKey(email.value());
    }

    public Closure of(Email email) {
        Closure closure = open.get(email.value());
        if (closure == null) {
            throw new IllegalStateException("no closure of " + email.value() + " was requested");
        }
        return closure;
    }
}
