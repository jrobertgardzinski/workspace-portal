package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate promises a bounded wait, because it sits on every request carrying a token and this
 * service runs 32 request threads: a security service that answers slowly must cost one request,
 * not the gallery.
 *
 * <p>The promise was written with {@code SimpleClientHttpRequestFactory}, whose two timeouts go to
 * {@code HttpURLConnection} — where the read timeout applies to each {@code read()} rather than to
 * the exchange. A peer that keeps sending SOMETHING therefore never trips it. That is what this
 * test builds: a socket that answers the introspection one byte at a time, each byte comfortably
 * inside the five-second read timeout, spelling a perfectly valid response over the best part of a
 * minute. The old factory waited for all of it — 50 seconds, measured, for the same two timeouts
 * that claim to bound the wait at five; the JDK client's timeout covers the whole wait for an
 * answer, so it gives up after five.
 *
 * <p>The other half of the same defect — a name resolution that hangs, which {@code connectTimeout}
 * does not cover on {@code HttpURLConnection} because it starts counting after the name is
 * resolved — needs a resolver to reproduce and is deliberately not faked here.
 */
@Epic("Security integration")
@Feature("Token introspection")
@Story("Bounded wait")
class SecurityGateBoundedWaitTest {

    /** Generous: the gate's own budget is 5s, and the trickle below lasts ~20s. */
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private ServerSocket security;

    @AfterEach
    void stopTheTrickle() throws Exception {
        if (security != null) {
            security.close();
        }
    }

    @Test
    @DisplayName("a security service answering byte by byte costs the gate its budget, not the whole answer")
    void a_trickling_security_service_does_not_hold_the_request_thread() throws Exception {
        String url = securityThatAnswersOneByteEvery(Duration.ofMillis(500));
        HttpSecurityAuthenticationGate gate = new HttpSecurityAuthenticationGate(url);

        long startedAt = System.nanoTime();
        assertThrows(SecurityAuthenticationGate.SecurityUnavailable.class,
                () -> gate.callerFor("a-perfectly-good-token"),
                "an answer that never arrives is not a verdict on the token");
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        assertTrue(waited.compareTo(PATIENCE) < 0,
                "the gate advertises a bounded wait and waited " + waited.toMillis() + "ms; a "
                        + "per-read timeout is not a bound, because the peer keeps resetting it");
    }

    /**
     * A socket speaking HTTP in slow motion: every byte of a valid response, one per interval. Each
     * byte arrives well inside any per-read timeout, so only a timeout on the EXCHANGE can stop it.
     */
    private String securityThatAnswersOneByteEvery(Duration interval) throws Exception {
        security = new ServerSocket(0);
        Thread trickle = new Thread(() -> {
            try (Socket connection = security.accept()) {
                connection.getInputStream().read(new byte[4096]);
                OutputStream out = connection.getOutputStream();
                byte[] answer = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 30\r\n\r\n{\"email\":\"alice@example.com\"}")
                        .getBytes(StandardCharsets.UTF_8);
                for (byte b : answer) {
                    out.write(b);
                    out.flush();
                    Thread.sleep(interval.toMillis());
                }
            } catch (Exception theGateGaveUpOrTheTestEnded) {
                // both are the expected ending here
            }
        });
        trickle.setDaemon(true);
        trickle.start();
        return "http://localhost:" + security.getLocalPort();
    }
}
