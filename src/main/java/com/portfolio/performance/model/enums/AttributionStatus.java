package com.portfolio.performance.model.enums;

/**
 * Represents the overall quality and completeness of an attribution calculation result.
 */
public enum AttributionStatus {

    /** All groups were processed successfully, using primary or fallback return data. */
    VALID,

    /** Exactly one group had no return data and no fallback; result is usable but incomplete. */
    DEGRADED,

    /** More than one group had no return data and no fallback; result requires manual review. */
    REVIEW_REQUIRED,

    /** Total group weight falls outside the 99–101% tolerance range; input is rejected. */
    INVALID_INPUT
}
