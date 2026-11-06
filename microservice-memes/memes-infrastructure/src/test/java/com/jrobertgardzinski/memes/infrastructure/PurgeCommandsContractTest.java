package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import com.jrobertgardzinski.purge.PurgeRule;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The consumer's half of the account-deletion saga contract: the pact states the exact shape of
 * every {@code content-commands} event this service acts on, and proves each one by driving the
 * real listener with the pact's payload. The generated pact (../pacts, committed) is verified
 * against the REAL orchestrator by microservice-offboarding's provider tests. Only the fields this
 * consumer reads are in the contract; the producer may add more (tolerant reader).
 *
 * <p>There are THREE commands now, because the saga has two phases. The mark
 * ({@code PURGE_USER_CONTENT}) is unchanged on the wire — deliberately, so this pact keeps its
 * meaning across the change — and the closure ({@code ERASE_USER_CONTENT}) and the compensation
 * ({@code RESTORE_USER_CONTENT}) join it in the same envelope. Note where the POLICY sits: on the
 * mark it is ferried and ignored (the mark decides nothing), on the closure it is read, because
 * the rule is applied at erasure time.
 */
@Epic("Contract")
@Feature("Purge commands")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "microservice-offboarding", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class PurgeCommandsContractTest {

    private final PurgeUserContent purgeUserContent = mock(PurgeUserContent.class);
    private final MarkUserContentForErasure markForErasure = mock(MarkUserContentForErasure.class);
    private final PurgeCommandsListener listener = new PurgeCommandsListener(markForErasure,
            mock(RestoreUserContent.class), purgeUserContent, new CapturedConfirmations(),
            Observations.silent(), new ObjectMapper(), NoTransactions.template());

    @Pact(consumer = "microservice-memes")
    MessagePact purgeCommand(MessagePactBuilder builder) {
        return builder.expectsToReceive("a purge user content command")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "PURGE_USER_CONTENT")
                        .uuid("sagaId")
                        .uuid("userId"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "purgeCommand")
    void purgesWithTheDeploymentDefault(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);
        // the mark, not the erasure: this command is the reversible half of the saga
        verify(markForErasure).execute(any(UserId.class));
    }

    @Pact(consumer = "microservice-memes")
    MessagePact purgeCommandWithPolicy(MessagePactBuilder builder) {
        return builder.expectsToReceive("a purge user content command with an explicit policy")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "PURGE_USER_CONTENT")
                        .uuid("sagaId")
                        .uuid("userId")
                        .object("policy")
                        .stringType("memes", "DELETE")
                        .closeObject())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "purgeCommandWithPolicy")
    void purgesWithTheLeaversChoice(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);
        // the policy rides the mark and is not acted on here — it is the CLOSURE that applies it
        verify(markForErasure).execute(any(UserId.class));
    }

    @Pact(consumer = "microservice-memes")
    MessagePact eraseCommand(MessagePactBuilder builder) {
        return builder.expectsToReceive("an erase user content command closing the saga")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "ERASE_USER_CONTENT")
                        .uuid("sagaId")
                        .uuid("userId")
                        // the basis, and this consumer needs it STATED: anything else it reads as
                        // the leaver's own request and deletes, whatever the rule beside it says
                        .stringValue("initiatedBy", "ADMIN")
                        .object("policy")
                        // the popularity rule rather than DELETE, so this pact proves the rule is
                        // HONOURED — with DELETE, a consumer that ignored the policy entirely
                        // would have passed it
                        .stringType("memes", "KEEP_POPULAR_ANONYMIZED:100")
                        .closeObject())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "eraseCommand")
    void erasesOnTheClosureAndAppliesTheRule(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);
        verify(purgeUserContent).execute(any(UserId.class), eq(Optional.of(new PurgeRule.KeepPopularAnonymized(100))));
    }

    @Pact(consumer = "microservice-memes")
    MessagePact restoreCommand(MessagePactBuilder builder) {
        return builder.expectsToReceive("a restore user content command compensating the saga")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "RESTORE_USER_CONTENT")
                        .uuid("sagaId")
                        .uuid("userId"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "restoreCommand")
    void restoresOnTheCompensation(List<Message> messages) throws Exception {
        PurgeUserContent nothingErases = mock(PurgeUserContent.class);
        RestoreUserContent restore = mock(RestoreUserContent.class);
        new PurgeCommandsListener(markForErasure, restore, nothingErases,
                new CapturedConfirmations(), Observations.silent(), new ObjectMapper(),
                NoTransactions.template())
                .receive(messages.get(0).contentsAsString(), null);
        verify(restore).execute(any(UserId.class));
    }
}
