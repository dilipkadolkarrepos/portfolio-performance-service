package com.portfolio.performance.controller;

import com.portfolio.performance.config.AttributionMdcFilter;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.service.AttributionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the portfolio performance attribution API.
 *
 * <p>This controller is intentionally thin — it validates the incoming request
 * (via Jakarta Bean Validation on {@code @Valid}), delegates all business logic
 * to {@link AttributionService}, and returns the result. No calculation or
 * decision-making belongs here.
 *
 * <p>Error responses for constraint violations are produced by
 * {@link com.portfolio.performance.exception.GlobalExceptionHandler}.
 */
@Slf4j
@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
public class AttributionController {

    private final AttributionService attributionService;

    /**
     * Accepts a portfolio attribution request and returns the computed result.
     *
     * <p>If the {@code requestId} was already processed, the stored result is
     * returned immediately (idempotent behaviour is transparent to the caller).
     *
     * @param request the validated attribution request body
     * @return HTTP 200 with the {@link AttributionResponse} body
     */
    @PostMapping("/attribution")
    public ResponseEntity<AttributionResponse> processAttribution(
            @Valid @RequestBody AttributionRequest request) {

        // Populate MDC requestId now that the JSON body has been deserialized.
        // The MDC filter already set correlationId; this adds the application-level key
        // so every downstream log line (service, calculator, simulator) carries both.
        // The filter's finally block clears the MDC after the response is committed.
        MDC.put(AttributionMdcFilter.MDC_REQUEST_ID, request.getRequestId());

        log.info("Attribution request received [requestId={}, portfolioId={}]",
                request.getRequestId(), request.getPortfolioId());

        AttributionResponse response = attributionService.processAttribution(request);

        return ResponseEntity.ok(response);
    }
}
