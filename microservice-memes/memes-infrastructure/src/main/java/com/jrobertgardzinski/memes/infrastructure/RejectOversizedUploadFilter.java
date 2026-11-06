package com.jrobertgardzinski.memes.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses an upload that ANNOUNCES more than the service accepts, before anything is read.
 *
 * <p>Two reasons, and neither is the heap — the multipart is spooled to disk
 * ({@code fileSizeThreshold=0}), so the bytes are not on the heap until {@code getBytes()}, which
 * {@link UploadAdmission} now bounds.
 *
 * <p><strong>The disk, and only the disk.</strong> Spooling happens before any of this service's
 * own limits apply, so without this a caller could make the container write its declared size to the
 * temp directory and only then be refused. Judging {@code Content-Length} costs nothing and happens
 * before the first byte is written. A request that LIES about its length is not a problem here: the
 * multipart parser's own limit still applies to whatever actually arrives.
 *
 * <p><strong>What this filter does NOT do, contrary to what it claimed when it shipped.</strong>
 * It was introduced on 2026-07-28 with the rationale that exceeding
 * {@code spring.servlet.multipart.max-file-size} surfaced as an internal error, so a user picking a
 * large photo was told the server had broken. That was already false on this stack and the review of
 * 2026-07-29 refuted it with the bytecode: on Spring Framework 6.2.8,
 * {@code MaxUploadSizeExceededException implements ErrorResponse} carrying
 * {@code PAYLOAD_TOO_LARGE}, and {@code DefaultHandlerExceptionResolver} matches
 * {@code instanceof ErrorResponse} first — so the framework answers 413 by itself, handler or no
 * handler. The claim is corrected rather than quietly dropped, because a comment that overstates
 * why code exists is the same defect this codebase spent two days removing from its tests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // before the multipart resolver, after the servlet basics
class RejectOversizedUploadFilter extends OncePerRequestFilter {

    private final long maxBytes;

    RejectOversizedUploadFilter(@Value("${spring.servlet.multipart.max-request-size:10MB}") String maxRequestSize) {
        this.maxBytes = DataSize.parse(maxRequestSize).toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (!"POST".equals(request.getMethod()) || declared <= maxBytes) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"TOO_LARGE\",\"detail\":\"the upload is "
                + declared + " bytes; this service accepts at most " + maxBytes + "\"}");
    }
}
