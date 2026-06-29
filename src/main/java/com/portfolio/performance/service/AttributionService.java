package com.portfolio.performance.service;

import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.response.AttributionResponse;

/**
 * Primary entry point for portfolio attribution processing.
 *
 * <p>Implementations are responsible for orchestrating weight validation,
 * contribution calculation, idempotency checks, persistence, and logging.
 * Callers should interact only with this interface; the concrete implementation
 * is resolved by the Spring container.
 */
public interface AttributionService {

    /**
     * Processes a portfolio attribution request end-to-end.
     *
     * <p>If a result for the given {@code requestId} already exists in the audit
     * store, the cached response is returned immediately without re-calculating.
     *
     * @param request a validated attribution request; must not be {@code null}
     * @return the computed (or cached) {@link AttributionResponse}
     * @throws com.portfolio.performance.exception.InvalidAttributionInputException
     *         if the total group weight falls outside the 99–101% tolerance range
     */
    AttributionResponse processAttribution(AttributionRequest request);
}
