package com.portfolio.performance.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entity that persists every attribution request for idempotency checks and audit.
 *
 * <p>Design notes:
 * <ul>
 *   <li>{@code requestId} carries a unique constraint so the service can detect and
 *       short-circuit duplicate submissions without re-running the attribution engine.</li>
 *   <li>{@code status} stores {@link com.portfolio.performance.model.enums.AttributionStatus#name()}
 *       as a plain string, keeping the column human-readable without an enum converter.</li>
 *   <li>{@code warnings} and {@code groupContributionsJson} store structured data as JSON
 *       strings so no additional child tables are required for this audit log.</li>
 * </ul>
 */
@Entity
@Table(name = "attribution_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Client-supplied idempotency key — must be unique across all persisted records. */
    @Column(name = "request_id", unique = true, nullable = false)
    private String requestId;

    @Column(name = "portfolio_id", nullable = false)
    private String portfolioId;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    /**
     * Stores {@link com.portfolio.performance.model.enums.AttributionStatus#name()}
     * (e.g. {@code "VALID"}, {@code "DEGRADED"}, {@code "REVIEW_REQUIRED"}, {@code "INVALID_INPUT"}).
     */
    @Column(name = "status")
    private String status;

    /**
     * Arithmetic sum of all group contribution percentages, rounded to 6 decimal places.
     * Explicit precision (19) and scale (6) prevent H2 from truncating trailing zeros on
     * round-trip, ensuring the stored value is numerically identical to the computed value.
     */
    @Column(name = "total_contribution_pct", precision = 19, scale = 6)
    private BigDecimal totalContributionPct;

    /** {@code true} when one or more groups used fallback pricing or had no pricing at all. */
    @Column(name = "degraded")
    private boolean degraded;

    /**
     * JSON-serialized {@code List<String>} of human-readable warning messages.
     * Length 4 000 covers up to ~80 average-length warnings before truncation.
     */
    @Column(name = "warnings", length = 4000)
    private String warnings;

    /**
     * Full {@code List<GroupContributionResult>} serialized as a JSON array.
     * Stored as {@code TEXT} to accommodate an unbounded number of asset groups.
     */
    @Column(name = "group_contributions_json", columnDefinition = "TEXT")
    private String groupContributionsJson;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "currency")
    private String currency;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
