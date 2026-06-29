package com.portfolio.performance.service;

import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.response.AttributionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Primary implementation of {@link AttributionService}.
 *
 * <p>Orchestration order for every incoming request:
 * <ol>
 *   <li>Check idempotency store — return early if this {@code requestId} was already processed.</li>
 *   <li>Validate group weights — {@link AttributionValidator} throws
 *       {@link com.portfolio.performance.exception.InvalidAttributionInputException}
 *       on out-of-range totals; the exception propagates to the caller unchecked.</li>
 *   <li>Compute contributions — {@link AttributionCalculator} performs all arithmetic.</li>
 *   <li>Persist result — {@link IdempotencyService} writes the audit row so future
 *       duplicates are short-circuited at step 1.</li>
 *   <li>Return the freshly computed {@link AttributionResponse}.</li>
 * </ol>
 */
@Slf4j
@Service
public class AttributionServiceImpl implements AttributionService {

    private final AttributionValidator validator;
    private final AttributionCalculator calculator;
    private final IdempotencyService    idempotencyService;

    public AttributionServiceImpl(AttributionValidator validator,
                                  AttributionCalculator calculator,
                                  IdempotencyService idempotencyService) {
        this.validator          = validator;
        this.calculator         = calculator;
        this.idempotencyService = idempotencyService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs at INFO on entry, on idempotency hit, and on successful completion.
     * Validation and unexpected exceptions propagate to the caller unchanged.
     */
    @Override
    public AttributionResponse processAttribution(AttributionRequest request) {

        // Step 1 — structured entry log
        log.info("Processing attribution request [requestId={}, portfolioId={}]",
                request.getRequestId(), request.getPortfolioId());

        // Step 2 — idempotency check: short-circuit if already processed
        return idempotencyService.findExistingResult(request.getRequestId())
                .map(cached -> {
                    log.info("Idempotent hit for requestId={} — returning cached response",
                            request.getRequestId());
                    return cached;
                })
                .orElseGet(() -> {

                    // Step 3 — weight validation (throws InvalidAttributionInputException if invalid)
                    validator.validateWeights(request.getGroups());

                    // Step 4 — attribution calculation
                    AttributionResponse response = calculator.calculate(request);

                    // Step 5 — persist so future duplicates are served from the audit store
                    idempotencyService.persistResult(request, response);

                    // Step 6 — completion log
                    log.info("Completed attribution [requestId={}, status={}, degraded={}]",
                            request.getRequestId(), response.getStatus(), response.isDegraded());

                    // Step 7 — return
                    return response;
                });
    }
}
