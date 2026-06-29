package com.portfolio.performance.model.enums;

/**
 * Indicates how a group's return percentage was sourced during attribution processing.
 */
public enum PricingMode {

    /** Group return was provided directly via {@code returnPct}. */
    PRIMARY,

    /** Group used {@code fallbackReturnPct} because {@code returnPct} was null. */
    FALLBACK_USED,

    /** Group has neither {@code returnPct} nor {@code fallbackReturnPct}; return is unavailable. */
    UNAVAILABLE
}
