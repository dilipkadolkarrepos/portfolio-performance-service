package com.portfolio.performance.service;

import com.portfolio.performance.exception.InvalidAttributionInputException;
import com.portfolio.performance.model.request.GroupInput;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Stateless Spring component that enforces business-rule constraints on an
 * {@code AttributionRequest} before the attribution engine processes it.
 *
 * <p>The class carries no mutable state so it can still be exercised with a
 * plain {@code new AttributionValidator()} in pure unit tests while also being
 * injectable as a Spring bean in the service layer.
 */
@Component
public class AttributionValidator {

    /** Inclusive lower bound for the sum of all group weights (percent). */
    private static final BigDecimal MIN_TOTAL_WEIGHT = new BigDecimal("99.00");

    /** Inclusive upper bound for the sum of all group weights (percent). */
    private static final BigDecimal MAX_TOTAL_WEIGHT = new BigDecimal("101.00");

    /**
     * Validates that the sum of {@code weightPct} across all groups falls
     * within the inclusive range [99.00, 101.00].
     *
     * <p>Summation is performed entirely in {@link BigDecimal} arithmetic to
     * avoid the rounding errors inherent in {@code double} addition.
     *
     * @param groups the asset groups from the incoming request; must not be {@code null}
     * @throws InvalidAttributionInputException if the total weight is outside [99.00, 101.00]
     */
    public void validateWeights(List<GroupInput> groups) {
        BigDecimal total = groups.stream()
                .map(GroupInput::getWeightPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(MIN_TOTAL_WEIGHT) < 0 || total.compareTo(MAX_TOTAL_WEIGHT) > 0) {
            // Scale to 2 d.p. for a consistent, human-readable message regardless of
            // how many decimal places the caller supplied (e.g. 95 → "95.00").
            String formatted = total.setScale(2, RoundingMode.HALF_UP).toPlainString();
            throw new InvalidAttributionInputException(
                    "Total weight " + formatted + "% is outside the allowed range of 99-101%"
            );
        }
    }
}
