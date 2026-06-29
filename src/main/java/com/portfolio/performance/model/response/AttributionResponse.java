package com.portfolio.performance.model.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.portfolio.performance.model.enums.AttributionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Top-level response body returned for a portfolio performance attribution request.
 *
 * <p>Key fields at a glance:
 * <ul>
 *   <li>{@code status} — overall quality of the calculation ({@link AttributionStatus}).</li>
 *   <li>{@code degraded} — {@code true} when at least one group used fallback pricing or
 *       had no pricing at all; convenience flag so clients need not iterate
 *       {@code groupContributions} to detect a degraded result.</li>
 *   <li>{@code warnings} — human-readable explanations for every fallback usage or
 *       missing-pricing event encountered during processing.</li>
 *   <li>{@code processedAt} — UTC timestamp serialized as an ISO-8601 string
 *       (e.g. {@code "2026-06-30T10:45:00Z"}) via {@link JsonFormat}.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionResponse {

    /** Echoes the client-supplied idempotency key from the original request. */
    @JsonProperty("request_id")
    private String requestId;

    /** Identifier of the portfolio that was attributed. */
    @JsonProperty("portfolio_id")
    private String portfolioId;

    /** Date for which the attribution was calculated (ISO-8601, e.g. {@code "2026-06-30"}). */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("valuation_date")
    private LocalDate valuationDate;

    /**
     * Arithmetic sum of all {@code GroupContributionResult.contributionPct} values,
     * rounded to 6 decimal places. Should be close to the portfolio's total return.
     */
    @JsonProperty("total_contribution_pct")
    private BigDecimal totalContributionPct;

    /** Per-group breakdown of weighted return contributions. */
    @JsonProperty("group_contributions")
    private List<GroupContributionResult> groupContributions;

    /**
     * Overall quality of the attribution result.
     * One of {@code VALID}, {@code DEGRADED}, {@code REVIEW_REQUIRED},
     * or {@code INVALID_INPUT}.
     */
    private AttributionStatus status;

    /**
     * {@code true} when one or more groups used fallback pricing or had no pricing at all.
     * Mirrors {@code status != VALID} for clients that only need a boolean signal.
     */
    private boolean degraded;

    /**
     * Human-readable messages explaining each fallback usage or missing-pricing event.
     * Empty when {@code status} is {@code VALID}.
     */
    private List<String> warnings;

    /**
     * UTC timestamp recording when the attribution engine completed processing.
     * Serialized as an ISO-8601 string (e.g. {@code "2026-06-30T10:45:00Z"}).
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @JsonProperty("processed_at")
    private Instant processedAt;
}
