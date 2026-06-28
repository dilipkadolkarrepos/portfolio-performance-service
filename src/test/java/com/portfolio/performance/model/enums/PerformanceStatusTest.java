package com.portfolio.performance.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceStatusTest {

    @Test
    void enumValuesAndValueOf() {
        PerformanceStatus[] values = PerformanceStatus.values();
        assertEquals(3, values.length, "PerformanceStatus should have exactly 3 entries");

        assertDoesNotThrow(() -> PerformanceStatus.valueOf("VALID"));
        assertDoesNotThrow(() -> PerformanceStatus.valueOf("REVIEW_REQUIRED"));
        assertDoesNotThrow(() -> PerformanceStatus.valueOf("INVALID_INPUT"));
    }
}

