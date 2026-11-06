package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal an upload gets for ANNOUNCING more than this service accepts.
 *
 * <p>The filter shipped on 2026-07-28 with no test at all, and with a rationale that turned out to
 * be wrong: it was said to turn an "internal error" into a truthful 413, when Spring answers 413 for
 * an oversized multipart on its own. What it really buys is that the refusal happens on the
 * DECLARED length, before the multipart is spooled to the container's disk — which is what these
 * tests pin.
 *
 * <p>Driven directly rather than through the application, because that is what it is — a servlet
 * filter with one decision.
 *
 * <p><strong>Which filter runs first, corrected.</strong> This javadoc used to state that
 * {@link RequireSignInFilter} runs BEFORE this one, so that an anonymous oversized upload is
 * refused 401 — and it explained the absence of a test for the anonymous caller with exactly that.
 * The chain says the opposite: this filter is {@code @Order(HIGHEST_PRECEDENCE + 10)} and
 * RequireSignInFilter carries no {@code @Order} at all, which registers it at LOWEST_PRECEDENCE.
 * An anonymous POST declaring twenty megabytes therefore gets 413, never 401.
 *
 * <p>That order is the right one and is now pinned rather than assumed
 * ({@link FilterOrderTest}): refusing on a declared length costs one integer comparison, while
 * authenticating costs a token introspection over HTTP to another service, and doing the cheap
 * refusal first is what keeps a flood of oversized anonymous posts from becoming a flood of
 * introspection calls. Nothing is leaked by it — 413 to an anonymous caller says only that the
 * request was too big, which it was.
 */
@Epic("Infrastructure")
@Feature("Upload guards")
@Story("Declared-size refusal")
class RejectOversizedUploadFilterTest {

    private final RejectOversizedUploadFilter filter = new RejectOversizedUploadFilter("10MB");

    /**
     * A request that DECLARES a length without carrying one.
     *
     * <p>Exactly the shape being defended against, and the reason the filter reads
     * {@code getContentLengthLong()} rather than measuring a body: the refusal has to happen on the
     * announcement, before a single byte is spooled anywhere. (MockHttpServletRequest derives its
     * content length from actual content, so declaring twenty megabytes here would mean allocating
     * them — which is the very thing under test.)
     */
    private static MockHttpServletRequest declaring(String method, long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/memes") {
            @Override
            public long getContentLengthLong() {
                return contentLength;
            }
        };
        request.setContentType("multipart/form-data; boundary=x");
        return request;
    }

    @Test
    @DisplayName("a POST that declares more than the limit is refused with 413 and never reaches the chain")
    void an_oversized_declaration_is_refused_before_the_chain() throws Exception {
        MockHttpServletRequest request = declaring("POST", 20L * 1024 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("TOO_LARGE"),
                "the body names the refusal so a UI can act on it: " + response.getContentAsString());
        // the decisive assertion: refusing must happen BEFORE anything downstream sees the request,
        // so the multipart is never spooled to the container's disk on its way to being rejected
        assertNull(chain.getRequest(), "an oversized upload must not reach the chain at all");
    }

    @Test
    @DisplayName("an upload within the limit is none of this filter's business")
    void a_normal_upload_passes_through() throws Exception {
        MockHttpServletRequest request = declaring("POST", 64);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest(), "it goes on to the rest of the chain untouched");
    }

    @Test
    @DisplayName("a GET is never judged on its length, however it is announced")
    void reads_are_not_uploads() throws Exception {
        MockHttpServletRequest request = declaring("GET", 20L * 1024 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }
}
