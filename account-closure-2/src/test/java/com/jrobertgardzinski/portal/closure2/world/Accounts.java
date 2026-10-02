package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.email.domain.Email;
import com.jrobertgardzinski.email.domain.NormalizedEmail;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.password.domain.HashedPassword;
import com.jrobertgardzinski.security.domain.entity.User;
import com.jrobertgardzinski.security.domain.repository.FakeUserRepository;
import com.jrobertgardzinski.security.domain.repository.UserRepository;
import com.jrobertgardzinski.security.domain.vo.Role;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The door the identity use cases use to reach {@link FakeUserRepository} — the fake itself, with
 * one switch in front of it: {@link #refuseDeletion()} makes the user row refuse to go.
 *
 * <p>That switch is the whole reason this class exists. The feature's central rule — "if the
 * account cannot go, everything comes back" — needs an account that cannot go, and the fake is
 * final and always can. Every other method delegates unchanged, and the steps still assert on the
 * fake directly, never on this class.
 */
public final class Accounts implements UserRepository {

    private final FakeUserRepository rows;
    private boolean deletionRefused;

    Accounts(FakeUserRepository rows) {
        this.rows = rows;
    }

    /** From now on the row stays, whatever asks it to go. */
    public void refuseDeletion() {
        deletionRefused = true;
    }

    @Override
    public void deleteByEmail(Email email) {
        if (deletionRefused) {
            throw new IllegalStateException("the user row of " + email.value() + " refuses to be deleted");
        }
        rows.deleteByEmail(email);
    }

    @Override
    public Optional<User> findBy(Email email) {
        return rows.findBy(email);
    }

    @Override
    public List<User> findAllBy(Collection<UserId> ids) {
        return rows.findAllBy(ids);
    }

    @Override
    public void setRoles(Email email, Set<Role> roles) {
        rows.setRoles(email, roles);
    }

    @Override
    public int countAdmins() {
        return rows.countAdmins();
    }

    @Override
    public void updatePassword(Email email, HashedPassword passwordHash) {
        rows.updatePassword(email, passwordHash);
    }

    @Override
    public void updateEmail(Email currentEmail, Email newEmail) {
        rows.updateEmail(currentEmail, newEmail);
    }

    @Override
    public void markPendingDeletion(Email email) {
        rows.markPendingDeletion(email);
    }

    @Override
    public void clearPendingDeletion(Email email) {
        rows.clearPendingDeletion(email);
    }

    @Override
    public boolean isPendingDeletion(Email email) {
        return rows.isPendingDeletion(email);
    }

    @Override
    public boolean existsBy(NormalizedEmail normalizedEmail) {
        return rows.existsBy(normalizedEmail);
    }

    @Override
    public User save(User user) {
        return rows.save(user);
    }
}
