package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which of the two upload filters decides first — asserted, because it was documented wrongly and
 * a reader acted on the documentation.
 *
 * <p>{@link RejectOversizedUploadFilterTest}'s javadoc used to say that {@link RequireSignInFilter}
 * runs first, so an anonymous oversized POST is refused 401, and it left the anonymous case
 * untested for exactly that reason. The chain says otherwise: the size filter carries
 * {@code @Order(HIGHEST_PRECEDENCE + 10)} and the sign-in gate carries no {@code @Order} at all,
 * which puts it at LOWEST_PRECEDENCE. An anonymous caller declaring twenty megabytes gets 413.
 *
 * <p>That IS the order this service wants, and it is a decision rather than an accident: the size
 * refusal is one integer comparison against a declared Content-Length, while the sign-in gate
 * introspects a token over HTTP against another service. Cheap refusal first means a flood of
 * oversized anonymous posts costs this service nothing and microservice-security nothing. Getting
 * it the other way round — by someone "restoring" the order the old comment described — would turn
 * the same flood into an introspection storm on the identity service every product shares.
 *
 * <p>Compared with the very comparator Spring registers servlet filters by, so this pins the
 * effective order rather than a restatement of the annotations.
 */
@Epic("Infrastructure")
@Feature("Upload guards")
@Story("Filter order")
@SpringBootTest(classes = MemesApplication.class)
class FilterOrderTest {

    @Autowired
    RejectOversizedUploadFilter oversized;

    @Autowired
    RequireSignInFilter requireSignIn;

    @Test
    @DisplayName("the size refusal runs before the sign-in gate, so an anonymous oversized POST is 413")
    void the_cheap_refusal_comes_first() {
        List<Filter> chain = new ArrayList<>(List.of(requireSignIn, oversized));
        AnnotationAwareOrderComparator.sort(chain);

        assertSame(oversized, chain.get(0),
                "an oversized declaration must be refused before anything asks identity who the"
                        + " caller is — otherwise every oversized anonymous POST costs a token"
                        + " introspection round trip to microservice-security");
        assertSame(requireSignIn, chain.get(1));
    }
}
