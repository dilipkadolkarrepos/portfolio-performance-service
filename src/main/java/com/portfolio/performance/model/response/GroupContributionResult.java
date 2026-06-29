package com.portfolio.performance.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.portfolio.performance.model.enums.PricingMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Computed attribution output for a single asset group.
 *
 * <p>{@code contributionPct} is always rounded to six decimal places before this
 * object is constructed, matching the precision contract of the attribution engine.
 * {@code pricingMode} records which pricing path was taken for this group so
 * callers can identify fallback or unavailable groups without inspecting warnings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupContributionResult {

    /** Display name that identifies this group — mirrors the value from the request. */
    @JsonProperty("group_name")
    private String groupName;

    /**
     * Weighted return contribution of this group expressed as a percentage,
     * rounded to 6 decimal places (e.g. {@code 0.625000}).
     */
    @JsonProperty("contribution_pct")
    private BigDecimal contributionPct;

    /**
     * Indicates how the group's return was sourced:
     * {@code PRIMARY}, {@code FALLBACK_USED}, or {@code UNAVAILABLE}.
     */
    @JsonProperty("pricing_mode")
    private PricingMode pricingMode;
}
