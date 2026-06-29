package com.portfolio.performance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.performance.model.entity.AttributionAudit;
import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.model.response.GroupContributionResult;
import com.portfolio.performance.repository.AttributionAuditRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Handles idempotency for the attribution API.
 *
 * <p>Before the attribution engine runs, the controller calls
 * {@link #findExistingResult(String)} with the incoming {@code requestId}.
 * If a stored result is found the engine is bypassed entirely and the
 * reconstructed {@link AttributionResponse} is returned to the caller.
 *
 * <p>After a successful calculation the controller calls
 * {@link #persistResult(AttributionRequest, AttributionResponse)} to write
 * the result to {@code attribution_audit} so future duplicates can be served
 * from the database.
 *
 * <p>JSON serialization of {@code groupContributions} and {@code warnings}
 * is delegated to the Spring-managed {@link ObjectMapper}, which carries the
 * {@code JavaTimeModule} and other project-wide configuration automatically.
 */
@Slf4j
@Service
public class IdempotencyService {

    private static final TypeReference<List<GroupContributionResult>> GROUP_LIST_TYPE =
            new TypeReference<>() {};

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {};

    private final AttributionAuditRepository repository;
    private final ObjectMapper               objectMapper;

    public IdempotencyService(AttributionAuditRepository repository, ObjectMapper objectMapper) {
        this.repository   = repository;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Checks whether an attribution result for {@code requestId} has already
     * been persisted and, if so, reconstructs and returns it.
     *
     * @param requestId the client-supplied idempotency key
     * @return an {@link Optional} containing the reconstructed response, or
     *         {@link Optional#empty()} if this is the first submission
     */
    public Optional<AttributionResponse> findExistingResult(String requestId) {
        return repository.findByRequestId(requestId)
                .map(audit -> {
                    log.info("Idempotency hit: requestId='{}' already processed for portfolioId='{}'. "
                             + "Returning stored result.", requestId, audit.getPortfolioId());
                    return reconstruct(audit);
                });
    }

    /**
     * Persists the attribution result so that subsequent submissions carrying
     * the same {@code requestId} can be served without re-running the engine.
     *
     * @param request  the original request (supplies metadata fields)
     * @param response the computed attribution result to store
     */
    public void persistResult(AttributionRequest request, AttributionResponse response) {
        AttributionAudit audit = AttributionAudit.builder()
                .requestId(request.getRequestId())
                .portfolioId(request.getPortfolioId())
                .valuationDate(request.getValuationDate())
                .status(response.getStatus() == null ? null : response.getStatus().name())
                .totalContributionPct(response.getTotalContributionPct())
                .degraded(response.isDegraded())
                .warnings(serializeQuietly(response.getWarnings()))
                .groupContributionsJson(serializeQuietly(response.getGroupContributions()))
                .requestedBy(request.getRequestedBy())
                .currency(request.getCurrency())
                .processedAt(response.getProcessedAt())
                .build();

        repository.save(audit);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Reconstructs a full {@link AttributionResponse} from a persisted
     * {@link AttributionAudit} row.  Field mapping is explicit so the compiler
     * catches any future field additions to either class.
     */
    private AttributionResponse reconstruct(AttributionAudit audit) {
        return AttributionResponse.builder()
                .requestId(audit.getRequestId())
                .portfolioId(audit.getPortfolioId())
                .valuationDate(audit.getValuationDate())
                .status(parseStatus(audit.getStatus()))
                .totalContributionPct(audit.getTotalContributionPct())
                .degraded(audit.isDegraded())
                .warnings(deserializeWarnings(audit.getWarnings()))
                .groupContributions(deserializeGroups(audit.getGroupContributionsJson()))
                .processedAt(audit.getProcessedAt())
                .build();
    }

    /** Converts a stored status string back to the {@link AttributionStatus} enum. */
    private AttributionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AttributionStatus.valueOf(status);
    }

    /**
     * Deserializes a JSON array string back to {@code List<GroupContributionResult>}.
     * Returns an empty list if the stored value is null or blank.
     */
    private List<GroupContributionResult> deserializeGroups(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, GROUP_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize groupContributionsJson — returning empty list. Cause: {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Deserializes a JSON array string back to {@code List<String>}.
     * Returns an empty list if the stored value is null or blank.
     */
    private List<String> deserializeWarnings(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize warnings — returning empty list. Cause: {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Serializes any object to a JSON string.
     * Returns {@code null} if the value is {@code null} or serialization fails,
     * so a failed serialization never blocks a save.
     */
    private String serializeQuietly(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize value of type {} — storing null. Cause: {}",
                    value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
