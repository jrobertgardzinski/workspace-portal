package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.outbox.OutboxTable;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The inbound {@code X-Correlation-Id} is a header a stranger writes, and this service does four
 * things with it: puts it in the MDC, prints it on every log line of the request, echoes it back,
 * and — the one that bites — stores it in {@code meme_events_outbox.cid}, which V5 declared as
 * {@code varchar(64)}.
 *
 * <p>So an over-long header was not cosmetic. The announce INSERT of a delete failed with 22001
 * inside the delete's own transaction, and the rollback took the whole teardown with it: while the
 * caller kept sending that header, the meme could not be deleted at all, and the author got a 500
 * for it. The comments twin has capped and stripped the header since P12; this is the same cap.
 *
 * <p>The filter is driven directly — it is a servlet filter with one decision — but the chain it
 * runs is the real announcement, because the column, not the string length, is what made this a
 * defect rather than a tidiness note.
 */
@Epic("Infrastructure")
@Feature("Correlation id")
@Story("A header a stranger wrote")
@SpringBootTest(classes = MemesApplication.class)
class CorrelationIdFilterTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    DataSource dataSource;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private MemeEvents events;

    @BeforeEach
    void freshOutbox() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        events = new KafkaMemeEvents(new SpringOutbox(dataSource,
                OutboxTable.named("meme_events_outbox"), clock, new KafkaMemeDispatch(kafka)));
        jdbc.sql("DELETE FROM meme_events_outbox").update();
    }

    @Test
    @DisplayName("a 100-character inbound correlation id does not roll the delete back")
    void an_over_long_header_still_lets_the_announcement_through() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/memes/doomed");
        request.addHeader(CorrelationIdFilter.HEADER, "a".repeat(100));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                tx.executeWithoutResult(status -> events.memeDeleted("doomed")));

        assertEquals(1, outboxRows(), "the delete's announcement must survive the header");
        assertEquals(64, storedCid().length(), "capped to what the column holds: " + storedCid());
    }

    @Test
    @DisplayName("nothing but [A-Za-z0-9_-] survives into the log context and the echo")
    void the_header_cannot_forge_a_log_line() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/memes");
        request.addHeader(CorrelationIdFilter.HEADER, "abc\r\n13:37 INFO forged line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String echoed = response.getHeader(CorrelationIdFilter.HEADER);
        assertEquals("abc1337INFOforgedline", echoed,
                "a correlation id carrying a newline writes the attacker's own log line");
        assertTrue(echoed.matches("[A-Za-z0-9_-]+"), echoed);
    }

    private int outboxRows() {
        return jdbc.sql("SELECT COUNT(*) FROM meme_events_outbox").query(Integer.class).single();
    }

    private String storedCid() {
        return jdbc.sql("SELECT cid FROM meme_events_outbox").query(String.class).single();
    }
}
