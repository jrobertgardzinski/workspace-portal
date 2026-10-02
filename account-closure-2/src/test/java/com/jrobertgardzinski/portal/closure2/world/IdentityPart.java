package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.email.domain.Email;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.password.domain.HashedPassword;
import com.jrobertgardzinski.security.domain.entity.EnrolledFactor;
import com.jrobertgardzinski.security.domain.entity.SessionTokens;
import com.jrobertgardzinski.security.domain.entity.User;
import com.jrobertgardzinski.security.domain.port.AccessTokenMint;
import com.jrobertgardzinski.security.domain.repository.FakeEmailChangeRepository;
import com.jrobertgardzinski.security.domain.repository.FakeEmailVerificationRepository;
import com.jrobertgardzinski.security.domain.repository.FakeEnrolledFactorRepository;
import com.jrobertgardzinski.security.domain.repository.FakeFederatedIdentityRepository;
import com.jrobertgardzinski.security.domain.repository.FakePasswordResetRepository;
import com.jrobertgardzinski.security.domain.repository.FakePasswordlessAccountRepository;
import com.jrobertgardzinski.security.domain.repository.FakeRecoveryCodeRepository;
import com.jrobertgardzinski.security.domain.repository.FakeSessionRepository;
import com.jrobertgardzinski.security.domain.repository.FakeUserRepository;
import com.jrobertgardzinski.security.domain.vo.AccessTokenValidityInHours;
import com.jrobertgardzinski.security.domain.vo.AccountClosure;
import com.jrobertgardzinski.security.domain.vo.FactorType;
import com.jrobertgardzinski.security.domain.vo.RefreshTokenValidityInHours;
import com.jrobertgardzinski.security.domain.vo.SessionTokensConfig;
import com.jrobertgardzinski.security.system.account.DeleteAccount;
import com.jrobertgardzinski.security.system.account.StartAccountDeletion;

import java.time.Clock;
import java.util.List;

/**
 * The account: the nine repositories {@link DeleteAccount} names, each one security-domain's own
 * fake, and the two use cases that bracket the whole chain — {@link StartAccountDeletion}, which
 * only locks the account and asks the content to go, and {@link DeleteAccount}, which is the last
 * thing to happen before anything is destroyed.
 *
 * <p>Nine, and all nine by hand: not one of those tables has a foreign key, so nothing cascades
 * and whatever is not named outlives the account. Three of them are seeded here (a session, a
 * second factor, a set of recovery codes), because "the account is gone" has to mean more than one
 * row going — a password hash left under a freed address is the kind of leftover nobody sees.
 */
public final class IdentityPart {

    private static final SessionTokensConfig SESSION_CONFIG = new SessionTokensConfig(
            new RefreshTokenValidityInHours(24), new AccessTokenValidityInHours(1));

    private final FakeUserRepository users = new FakeUserRepository();
    private final Accounts accounts = new Accounts(users);
    private final FakeSessionRepository sessions;
    private final FakeEnrolledFactorRepository factors = new FakeEnrolledFactorRepository();
    private final FakeRecoveryCodeRepository recoveryCodes = new FakeRecoveryCodeRepository();
    private final FakeFederatedIdentityRepository federatedIdentities =
            new FakeFederatedIdentityRepository();
    private final FakeEmailVerificationRepository emailVerifications;
    private final FakePasswordResetRepository passwordResets;
    private final FakeEmailChangeRepository emailChanges;
    private final FakePasswordlessAccountRepository passwordlessAccounts =
            new FakePasswordlessAccountRepository();

    private final Clock clock;
    private final DeleteAccount deleteAccount;
    private final StartAccountDeletion startClosure;
    private final Closures closures;

    IdentityPart(Clock clock) {
        this.clock = clock;
        this.sessions = new FakeSessionRepository(clock);
        this.emailVerifications = new FakeEmailVerificationRepository(clock);
        this.passwordResets = new FakePasswordResetRepository(clock);
        this.emailChanges = new FakeEmailChangeRepository(clock);
        this.deleteAccount = new DeleteAccount(accounts, sessions, factors, recoveryCodes,
                federatedIdentities, emailVerifications, passwordResets, emailChanges,
                passwordlessAccounts);
        this.closures = new Closures(accounts, deleteAccount);
        this.startClosure = new StartAccountDeletion(accounts, sessions, closures);
    }

    /**
     * One account, signed in on one device, with a second factor and recovery codes — and the id
     * security minted for it, which is the only name the three content parts know it by.
     */
    UserId registered(Email email) {
        User user = users.save(new User(email, new HashedPassword("argon2:" + email.value())));
        sessions.store(SessionTokens.createFor(email, SESSION_CONFIG, clock, AccessTokenMint.RANDOM));
        factors.enrol(new EnrolledFactor(email, FactorType.TOTP, "authenticator app", 0, "a-secret"));
        recoveryCodes.replaceAll(email, List.of("hash-of-code-1", "hash-of-code-2"));
        return user.id();
    }

    public void closureRequested(AccountClosure request) {
        startClosure.execute(request);
    }

    /** The user rows, with the switch that makes the account refuse to go. */
    public Accounts accounts() {
        return accounts;
    }

    /** The user rows as the fake holds them — what an assertion about the account reads. */
    public FakeUserRepository users() {
        return users;
    }

    public FakeSessionRepository sessions() {
        return sessions;
    }

    public FakeEnrolledFactorRepository factors() {
        return factors;
    }

    public FakeRecoveryCodeRepository recoveryCodes() {
        return recoveryCodes;
    }

    public Closures closures() {
        return closures;
    }
}
