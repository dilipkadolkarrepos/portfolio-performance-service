package com.portfolio.performance.model.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * Top-level POST body for a portfolio performance attribution calculation.
 * {@code requestId} provides idempotency — duplicate submissions carrying
 * the same value must not produce duplicate attribution records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionRequest {

    /** Client-supplied identifier used for idempotency checks. */
    @NotBlank
    @JsonProperty("request_id")
    private String requestId;

    /** Identifier of the portfolio being attributed. */
    @NotBlank
    @JsonProperty("portfolio_id")
    private String portfolioId;

    /** Date for which the attribution is calculated (ISO-8601). */
    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("valuation_date")
    private LocalDate valuationDate;

    /**
     * Ordered list of asset groups that make up the portfolio.
     * Each element is validated independently via {@code @Valid}.
     * Total {@code weightPct} across all groups must sum to 99–101%.
     */
    @NotEmpty
    @Valid
    private List<GroupInput> groups;

    /** ISO-4217 currency code applied to all monetary values in this request (e.g. "USD"). */
    @NotBlank
    private String currency;

    /** Identity of the user or system that submitted the request. */
    @NotBlank
    @JsonProperty("requested_by")
    private String requestedBy;
}
