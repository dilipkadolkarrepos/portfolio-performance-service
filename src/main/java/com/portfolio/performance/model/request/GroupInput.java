package com.portfolio.performance.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Represents a single asset group within an attribution request.
 * Both {@code returnPct} and {@code fallbackReturnPct} are nullable to
 * accommodate delayed or unavailable pricing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupInput {

    /** Display name that uniquely identifies this group within the request. */
    @NotBlank
    @JsonProperty("group_name")
    private String groupName;

    /** Percentage weight of this group in the portfolio (0.0 – 100.0). */
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    @JsonProperty("weight_pct")
    private BigDecimal weightPct;

    /**
     * Primary return for this group as a percentage.
     * Nullable — a {@code null} value signals delayed or unavailable pricing;
     * {@code fallbackReturnPct} will be used in that case.
     */
    @JsonProperty("return_pct")
    private BigDecimal returnPct;

    /**
     * Fallback return for this group as a percentage.
     * Nullable — used only when {@code returnPct} is absent.
     * When both fields are {@code null} the group is marked
     * {@code PricingMode.UNAVAILABLE}.
     */
    @JsonProperty("fallback_return_pct")
    private BigDecimal fallbackReturnPct;
}
