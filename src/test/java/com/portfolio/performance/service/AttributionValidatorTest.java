package com.portfolio.performance.service;

import com.portfolio.performance.exception.InvalidAttributionInputException;
import com.portfolio.performance.model.request.GroupInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests for {@link AttributionValidator#validateWeights}.
 *
 * <p>No Spring context is loaded — {@code AttributionValidator} is instantiated
 * directly with {@code new}, confirming it is a dependency-free POJO.
 * The five cases below cover the exact boundary conditions specified in the gate:
 *
 * <ol>
 *   <li>Exactly 100 % — valid, no exception</li>
 *   <li>Exactly 99.00 % (low boundary) — valid, no exception</li>
 *   <li>Exactly 101.00 % (high boundary) — valid, no exception</li>
 *   <li>95.00 % (below low boundary) — exception thrown, message contains "95.00"</li>
 *   <li>102.00 % (above high boundary) — exception thrown</li>
 * </ol>
 */
class AttributionValidatorTest {

    private AttributionValidator validator;

    @BeforeEach
    void setUp() {
        // Plain instantiation — no Spring, no mocks required.
        validator = new AttributionValidator();
    }

    // ------------------------------------------------------------------
    // Helper — builds a GroupInput list whose weights sum to the given total.
    // Splits the total across two groups to exercise real summation.
    // ------------------------------------------------------------------

    /**
     * Returns a two-group list whose {@code weightPct} values sum to {@code total}.
     * The split (first group = total − 10, second group = 10) is arbitrary;
     * what matters is that the validator must add them up rather than inspect
     * individual values.
     */
    private static List<GroupInput> groupsWithTotal(String total) {
        BigDecimal second = new BigDecimal("10.00");
        BigDecimal first  = new BigDecimal(total).subtract(second);

        return List.of(
                GroupInput.builder()
                        .groupName("Group A")
                        .weightPct(first)
                        .build(),
                GroupInput.builder()
                        .groupName("Group B")
                        .weightPct(second)
                        .build()
        );
    }

    // ------------------------------------------------------------------
    // Valid cases — no exception must be thrown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Valid: weights summing to exactly 100.00% — no exception thrown")
    void weightsExactly100_noExceptionThrown() {
        assertDoesNotThrow(
                () -> validator.validateWeights(groupsWithTotal("100.00")),
                "Weights that sum to exactly 100% must not trigger an exception"
        );
    }

    @Test
    @DisplayName("Valid boundary low: weights summing to exactly 99.00% — no exception thrown")
    void weightsBoundaryLow99_noExceptionThrown() {
        assertDoesNotThrow(
                () -> validator.validateWeights(groupsWithTotal("99.00")),
                "99.00% is the inclusive lower bound and must be accepted"
        );
    }

    @Test
    @DisplayName("Valid boundary high: weights summing to exactly 101.00% — no exception thrown")
    void weightsBoundaryHigh101_noExceptionThrown() {
        assertDoesNotThrow(
                () -> validator.validateWeights(groupsWithTotal("101.00")),
                "101.00% is the inclusive upper bound and must be accepted"
        );
    }

    // ------------------------------------------------------------------
    // Invalid cases — exception must be thrown with a descriptive message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Invalid low: weights summing to 95.00% — exception thrown with '95.00' in message")
    void weightsTooLow_95_exceptionThrownWithCorrectMessage() {
        InvalidAttributionInputException ex = assertThrows(
                InvalidAttributionInputException.class,
                () -> validator.validateWeights(groupsWithTotal("95.00")),
                "Weights below 99% must throw InvalidAttributionInputException"
        );

        assertNotNull(ex.getMessage(), "Exception message must not be null");
        assertTrue(
                ex.getMessage().contains("95.00"),
                "Exception message must contain the actual total '95.00' but was: " + ex.getMessage()
        );
    }

    @Test
    @DisplayName("Invalid high: weights summing to 102.00% — exception thrown")
    void weightsTooHigh_102_exceptionThrown() {
        InvalidAttributionInputException ex = assertThrows(
                InvalidAttributionInputException.class,
                () -> validator.validateWeights(groupsWithTotal("102.00")),
                "Weights above 101% must throw InvalidAttributionInputException"
        );

        assertNotNull(ex.getMessage(), "Exception message must not be null");
        assertTrue(
                ex.getMessage().contains("102.00"),
                "Exception message must contain the actual total '102.00' but was: " + ex.getMessage()
        );
    }
}
