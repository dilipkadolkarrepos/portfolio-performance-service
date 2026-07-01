package com.portfolio.performance.service;

import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.model.response.GroupContributionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests for {@link AttributionCalculator#calculate}.
 *
 * <p>No Spring context — {@code AttributionCalculator} is instantiated with
 * {@code new} to confirm it is a dependency-free POJO. All seven gate cases
 * are covered:
 *
 * <ol>
 *   <li>All groups have {@code returnPct} → VALID, degraded=false</li>
 *   <li>One group uses {@code fallbackReturnPct} → VALID, FALLBACK_USED, warning added</li>
 *   <li>One group has no return and no fallback → DEGRADED, degraded=true, warning added</li>
 *   <li>Two groups have no return and no fallback → REVIEW_REQUIRED, degraded=true, two warnings</li>
 *   <li>Mixed: one fallback used + one missing → DEGRADED, two warnings, correct totals</li>
 *   <li>Math verification: (60×1.5/100)+(30×0.4/100)+(10×0.05/100) = 1.025000</li>
 * </ol>
 */
class AttributionCalculatorTest {

    private PricingResilienceSimulator simulator;
    private AttributionCalculator      calculator;

    @BeforeEach
    void setUp() {
        simulator  = new PricingResilienceSimulator();
        calculator = new AttributionCalculator(simulator);
    }

    // ------------------------------------------------------------------
    // Shared request-builder helper
    // ------------------------------------------------------------------

    private static AttributionRequest requestWith(List<GroupInput> groups) {
        return AttributionRequest.builder()
                .requestId("REQ-TEST-001")
                .portfolioId("PF-TEST-42")
                .valuationDate(LocalDate.of(2026, 6, 30))
                .groups(groups)
                .currency("USD")
                .requestedBy("tester@example.com")
                .build();
    }

    private static GroupInput group(String name, String weight, String returnPct, String fallback) {
        return GroupInput.builder()
                .groupName(name)
                .weightPct(new BigDecimal(weight))
                .returnPct(returnPct  == null ? null : new BigDecimal(returnPct))
                .fallbackReturnPct(fallback == null ? null : new BigDecimal(fallback))
                .build();
    }

    /** Finds the {@link GroupContributionResult} for the given name; throws if absent. */
    private static GroupContributionResult resultFor(AttributionResponse resp, String name) {
        return resp.getGroupContributions().stream()
                .filter(g -> g.getGroupName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No contribution result for group: " + name));
    }

    // ------------------------------------------------------------------
    // Gate case 1 — all groups have returnPct
    // ------------------------------------------------------------------

    @Test
    @DisplayName("All groups PRIMARY — status=VALID, degraded=false, no warnings")
    void allPrimary_validStatus_noDegraded_noWarnings() {
        List<GroupInput> groups = List.of(
                group("Equities",    "60", "1.50",  null),
                group("Bonds",       "30", "0.40",  null),
                group("Alternatives","10", "0.05",  null)
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        assertAll("All-primary result",
                () -> assertEquals(AttributionStatus.VALID, resp.getStatus()),
                () -> assertFalse(resp.isDegraded(),       "degraded must be false"),
                () -> assertTrue(resp.getWarnings().isEmpty(), "warnings must be empty"),
                () -> assertEquals(PricingMode.PRIMARY, resultFor(resp, "Equities").getPricingMode()),
                () -> assertEquals(PricingMode.PRIMARY, resultFor(resp, "Bonds").getPricingMode()),
                () -> assertEquals(PricingMode.PRIMARY, resultFor(resp, "Alternatives").getPricingMode())
        );
    }

    // ------------------------------------------------------------------
    // Gate case 2 — one group uses fallbackReturnPct
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One FALLBACK_USED — status=VALID, pricingMode=FALLBACK_USED, warning added")
    void oneFallback_validStatus_fallbackWarning() {
        List<GroupInput> groups = List.of(
                group("Equities", "70", "2.00", null),
                group("Bonds",    "30",  null,  "0.50")   // fallback only
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        assertAll("One-fallback result",
                () -> assertEquals(AttributionStatus.VALID, resp.getStatus()),
                () -> assertFalse(resp.isDegraded()),
                () -> assertEquals(1, resp.getWarnings().size()),
                () -> assertTrue(resp.getWarnings().get(0).contains("Bonds"),
                        "Warning must mention the group name 'Bonds'"),
                () -> assertEquals(PricingMode.PRIMARY,      resultFor(resp, "Equities").getPricingMode()),
                () -> assertEquals(PricingMode.FALLBACK_USED,resultFor(resp, "Bonds").getPricingMode())
        );
    }

    // ------------------------------------------------------------------
    // Gate case 3 — one group has no return and no fallback (DEGRADED)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One UNAVAILABLE — status=DEGRADED, degraded=true, one unavailability warning")
    void oneMissing_degradedStatus_oneWarning() {
        List<GroupInput> groups = List.of(
                group("Equities",    "70", "2.00", null),
                group("Alternatives","30",  null,   null)  // no pricing
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        assertAll("One-missing result",
                () -> assertEquals(AttributionStatus.DEGRADED, resp.getStatus()),
                () -> assertTrue(resp.isDegraded()),
                () -> assertEquals(1, resp.getWarnings().size()),
                () -> assertTrue(resp.getWarnings().get(0).contains("Alternatives"),
                        "Warning must mention the unavailable group 'Alternatives'"),
                () -> assertEquals(PricingMode.UNAVAILABLE, resultFor(resp, "Alternatives").getPricingMode()),
                () -> assertEquals(0,
                        BigDecimal.ZERO.compareTo(resultFor(resp, "Alternatives").getContributionPct()),
                        "Unavailable group contribution must be zero")
        );
    }

    // ------------------------------------------------------------------
    // Gate case 4 — two groups have no pricing (REVIEW_REQUIRED)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Two UNAVAILABLE — status=REVIEW_REQUIRED, degraded=true, two warnings")
    void twoMissing_reviewRequiredStatus_twoWarnings() {
        List<GroupInput> groups = List.of(
                group("Equities",    "50", "1.00", null),
                group("Alternatives","30",  null,   null),
                group("Commodities", "20",  null,   null)
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        assertAll("Two-missing result",
                () -> assertEquals(AttributionStatus.REVIEW_REQUIRED, resp.getStatus()),
                () -> assertTrue(resp.isDegraded()),
                () -> assertEquals(2, resp.getWarnings().size(),
                        "Expected exactly two unavailability warnings"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("Alternatives")), "Warning must mention 'Alternatives'"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("Commodities")), "Warning must mention 'Commodities'")
        );
    }

    // ------------------------------------------------------------------
    // Gate case 5 — mixed: one fallback + one missing → DEGRADED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One FALLBACK_USED + one UNAVAILABLE — status=DEGRADED, two warnings, correct totals")
    void oneFallbackOneMissing_degraded_twoWarnings_correctTotal() {
        List<GroupInput> groups = List.of(
                group("Equities",    "60", "1.00",  null),  // PRIMARY
                group("Bonds",       "30",  null,  "0.50"), // FALLBACK_USED
                group("Alternatives","10",  null,   null)   // UNAVAILABLE
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        // Equities:     60 * 1.00 / 100 = 0.600000
        // Bonds:        30 * 0.50 / 100 = 0.150000
        // Alternatives: zero
        // Total = 0.750000
        BigDecimal expectedTotal = new BigDecimal("0.750000");

        assertAll("Mixed fallback+missing result",
                () -> assertEquals(AttributionStatus.DEGRADED, resp.getStatus()),
                () -> assertTrue(resp.isDegraded()),
                () -> assertEquals(2, resp.getWarnings().size(),
                        "Expected one fallback warning + one unavailability warning"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("Bonds") && w.toLowerCase().contains("fallback")),
                        "Fallback warning must mention 'Bonds'"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("Alternatives") && w.toLowerCase().contains("unavailable")),
                        "Unavailability warning must mention 'Alternatives'"),
                () -> assertEquals(0, expectedTotal.compareTo(resp.getTotalContributionPct()),
                        "Total contribution must be 0.750000")
        );
    }

    // ------------------------------------------------------------------
    // Gate case 6 — exact arithmetic verification
    // (60*1.5/100) + (30*0.4/100) + (10*0.05/100) = 0.9 + 0.12 + 0.005 = 1.025
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Math: (60×1.5/100)+(30×0.4/100)+(10×0.05/100) = 1.025000")
    void mathVerification_exactContributions_andTotal() {
        List<GroupInput> groups = List.of(
                group("Equities",    "60", "1.5",  null),
                group("Bonds",       "30", "0.4",  null),
                group("Alternatives","10", "0.05", null)
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        BigDecimal expectedEquities     = new BigDecimal("0.900000"); // 60 * 1.5  / 100
        BigDecimal expectedBonds        = new BigDecimal("0.120000"); // 30 * 0.4  / 100
        BigDecimal expectedAlternatives = new BigDecimal("0.005000"); // 10 * 0.05 / 100
        BigDecimal expectedTotal        = new BigDecimal("1.025000"); // sum

        assertAll("Exact arithmetic check",
                () -> assertEquals(0, expectedEquities.compareTo(
                        resultFor(resp, "Equities").getContributionPct()),
                        "Equities contribution must be 0.900000"),
                () -> assertEquals(0, expectedBonds.compareTo(
                        resultFor(resp, "Bonds").getContributionPct()),
                        "Bonds contribution must be 0.120000"),
                () -> assertEquals(0, expectedAlternatives.compareTo(
                        resultFor(resp, "Alternatives").getContributionPct()),
                        "Alternatives contribution must be 0.005000"),
                () -> assertEquals(0, expectedTotal.compareTo(resp.getTotalContributionPct()),
                        "Total contribution must be 1.025000")
        );
    }

    // ------------------------------------------------------------------
    // Gate case 7 — fallback path completes within 500 ms
    // (simulator sleeps ~50 ms per probe; a single-group request with
    //  one fallback must finish well within the 500 ms ceiling)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Fallback path: single group with fallback resolves within 500 ms")
    void fallbackPath_completesWithinTimeout() {
        List<GroupInput> groups = List.of(
                group("Bonds", "100", null, "0.50")   // primary absent, fallback present
        );

        assertTimeout(Duration.ofMillis(500), () -> calculator.calculate(requestWith(groups)),
                "Fallback resolution (including ~50 ms probe) must complete within 500 ms");
    }

    // ------------------------------------------------------------------
    // Gate case 8 — all three groups use fallback pricing
    //
    // Edge case: FALLBACK_USED groups are NOT "missing" — missingGroups stays
    // empty, so status must be VALID and degraded must be false even when every
    // group was priced via the secondary source.  Three warnings (one per group)
    // must be present, and the total must reflect all three fallback returns.
    //
    // (60×0.80/100) + (30×0.50/100) + (10×0.20/100) = 0.480 + 0.150 + 0.020
    //                                                 = 0.650000
    // ------------------------------------------------------------------

    @Test
    @DisplayName("All FALLBACK_USED — status=VALID, degraded=false, three warnings, correct total")
    void allFallback_validStatus_degradedFalse_threeWarnings_correctTotal() {
        List<GroupInput> groups = List.of(
                group("US Equities",  "60", null, "0.80"),  // primary absent
                group("Fixed Income", "30", null, "0.50"),  // primary absent
                group("Alternatives", "10", null, "0.20")   // primary absent
        );

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        BigDecimal expectedTotal = new BigDecimal("0.650000");

        assertAll("All-FALLBACK_USED result",
                // Status must be VALID — fallback-priced groups are not "missing"
                () -> assertEquals(AttributionStatus.VALID, resp.getStatus(),
                        "All fallback groups still produce a VALID result"),
                () -> assertFalse(resp.isDegraded(),
                        "degraded must be false when all groups resolved via fallback"),

                // All three groups must be tagged FALLBACK_USED
                () -> assertEquals(PricingMode.FALLBACK_USED,
                        resultFor(resp, "US Equities").getPricingMode()),
                () -> assertEquals(PricingMode.FALLBACK_USED,
                        resultFor(resp, "Fixed Income").getPricingMode()),
                () -> assertEquals(PricingMode.FALLBACK_USED,
                        resultFor(resp, "Alternatives").getPricingMode()),

                // Exactly three fallback warnings — one per group
                () -> assertEquals(3, resp.getWarnings().size(),
                        "Expected exactly three fallback warnings"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("US Equities")),
                        "Warning must mention 'US Equities'"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("Fixed Income")),
                        "Warning must mention 'Fixed Income'"),
                () -> assertTrue(resp.getWarnings().stream()
                        .anyMatch(w -> w.contains("Alternatives")),
                        "Warning must mention 'Alternatives'"),

                // Total must reflect all three fallback contributions
                () -> assertEquals(0, expectedTotal.compareTo(resp.getTotalContributionPct()),
                        "Total contribution must be 0.650000")
        );
    }

    // ------------------------------------------------------------------
    // Gate case 9 — response fields echoed correctly from the request
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Response mirrors requestId, portfolioId, and valuationDate from the request")
    void response_mirrorsRequestScalars() {
        List<GroupInput> groups = List.of(group("Only", "100", "1.00", null));

        AttributionResponse resp = calculator.calculate(requestWith(groups));

        assertAll("Scalar echo",
                () -> assertEquals("REQ-TEST-001",    resp.getRequestId()),
                () -> assertEquals("PF-TEST-42",      resp.getPortfolioId()),
                () -> assertEquals(LocalDate.of(2026,6,30), resp.getValuationDate()),
                () -> assertNotNull(resp.getProcessedAt(), "processedAt must be set")
        );
    }
}
