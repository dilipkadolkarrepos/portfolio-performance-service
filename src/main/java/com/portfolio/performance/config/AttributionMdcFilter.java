package com.portfolio.performance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that populates the SLF4J MDC for every inbound HTTP request so that
 * all log lines emitted during request processing carry a stable correlation identifier.
 *
 * <h2>MDC keys written</h2>
 * <ul>
 *   <li><b>correlationId</b> — value of the {@code X-Request-ID} request header when
 *       present; otherwise a freshly generated UUID.  The same value is echoed back to
 *       the caller via the {@code X-Correlation-ID} response header so clients can
 *       correlate their own traces.</li>
 *   <li><b>requestId</b> — application-level idempotency key taken from the
 *       {@code X-Attribution-Request-ID} request header when supplied.  This key is
 *       also set by {@link com.portfolio.performance.controller.AttributionController}
 *       after the request body has been parsed, so downstream log lines always carry
 *       it even when the header is absent.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * The filter always clears the MDC in a {@code finally} block regardless of whether
 * the downstream chain throws, ensuring that no MDC state leaks between requests on
 * the same thread (critical in servlet thread-pool environments).
 *
 * <p>Registered as a Spring {@link Component}, so it is picked up automatically by
 * Spring Boot's {@code FilterRegistrationBean} infrastructure and applied to every
 * request URL.
 */
@Slf4j
@Component
public class AttributionMdcFilter extends OncePerRequestFilter {

    /** MDC key for the per-request trace identifier derived from {@code X-Request-ID}. */
    public static final String MDC_CORRELATION_ID = "correlationId";

    /**
     * MDC key for the application-level idempotency key.
     * Set here when the client supplies {@code X-Attribution-Request-ID};
     * overwritten (harmlessly) by the controller once the JSON body is parsed.
     */
    public static final String MDC_REQUEST_ID = "requestId";

    /** Inbound header from which the correlation ID is sourced. */
    private static final String HEADER_REQUEST_ID = "X-Request-ID";

    /**
     * Optional inbound header that carries the attribution {@code requestId} before the
     * JSON body has been read.  Most clients omit this and rely on the body field instead.
     */
    private static final String HEADER_ATTRIBUTION_REQUEST_ID = "X-Attribution-Request-ID";

    /** Response header to which the resolved correlation ID is written for client tracing. */
    private static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // ----------------------------------------------------------------
            // 1. Resolve correlation ID: prefer X-Request-ID header, else UUID
            // ----------------------------------------------------------------
            String correlationId = request.getHeader(HEADER_REQUEST_ID);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }
            MDC.put(MDC_CORRELATION_ID, correlationId);

            // Echo the resolved correlation ID back to the caller
            response.setHeader(HEADER_CORRELATION_ID, correlationId);

            // ----------------------------------------------------------------
            // 2. Optionally seed requestId from a dedicated header.
            //    AttributionController overwrites this once the body is parsed,
            //    so even clients that omit this header get a populated requestId
            //    in every log line emitted by the service layer.
            // ----------------------------------------------------------------
            String attributionRequestId = request.getHeader(HEADER_ATTRIBUTION_REQUEST_ID);
            if (attributionRequestId != null && !attributionRequestId.isBlank()) {
                MDC.put(MDC_REQUEST_ID, attributionRequestId);
            }

            log.debug("MDC populated [correlationId={}, uri={}, method={}]",
                    correlationId, request.getRequestURI(), request.getMethod());

            filterChain.doFilter(request, response);

        } finally {
            // Always clear MDC to prevent state leaking to the next request on this thread
            MDC.clear();
        }
    }
}
