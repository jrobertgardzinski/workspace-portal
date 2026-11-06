package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.config.ImageLimits;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's two distinct refusals must stay distinct: permits exhausted for the whole patience
 * window is OVERLOAD (429, "retry me shortly" — pinned end to end in {@link DecodeConcurrencyTest}),
 * but an INTERRUPTED wait is a teardown (503, "go elsewhere") — and the interrupt flag must
 * survive the exception, or the shutdown that raised it would be silently swallowed.
 */
@Epic("Infrastructure")
@Feature("Decode concurrency")
@Story("Interrupt vs overload")
class ConcurrencyGuardedImageOptimizerTest {

    private final ImageLimits limits = new ImageLimits(1024);
    private final ConcurrencyGuardedImageOptimizer guard = new ConcurrencyGuardedImageOptimizer(
            new WebImageOptimizer(limits), limits, 1, Duration.ofSeconds(5));

    @Test
    @DisplayName("an interrupted permit wait refuses as UNAVAILABLE, not overload — with the interrupt flag restored")
    void interrupted_wait_is_unavailable_not_overloaded() throws Exception {
        byte[] png = png();
        Thread.currentThread().interrupt();   // the teardown arrives before the wait even starts
        try {
            assertThrows(ImageDecodeInterruptedException.class, () -> guard.toPngWithin(png, 64),
                    "an interrupt is a teardown, not overload — it must not masquerade as the 429 exception");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the guard must restore the interrupt flag for whoever is tearing the worker down");
        } finally {
            Thread.interrupted();   // clear the flag so it cannot leak into the next test
        }
    }

    @Test
    @DisplayName("the web boundary answers the interruption with 503, where the permit timeout earns 429")
    void web_boundary_maps_interruption_to_503() {
        var response = new WebErrorHandler()
                .decodeInterrupted(new ImageDecodeInterruptedException("interrupted while waiting"));

        assertEquals(503, response.getStatusCode().value(),
                "an instance being torn down must say UNAVAILABLE — a 429 would invite a retry against it");
        assertEquals("UNAVAILABLE", response.getBody().get("status"));
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
