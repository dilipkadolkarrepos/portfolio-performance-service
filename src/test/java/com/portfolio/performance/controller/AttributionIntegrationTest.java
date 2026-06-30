package com.portfolio.performance.controller;

import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.repository.AttributionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack integration tests for the attribution endpoint.
 *
 * <p>Starts the complete Spring Boot application on a random port with an
 * auto-configured in-memory H2 database. {@link TestRestTemplate} exercises the
 * real HTTP stack — serialization, validation, service logic, JPA persistence,
 * and global error handling — without any mocking.
 *
 * <p>The audit table is cleared before every test so that idempotency checks
 * and row-count assertions are always performed against a clean slate.
 *
 * <h2>Test inventory</h2>
 * <ol>
 *   <li>Valid full pricing (all PRIMARY) — HTTP 200, VALID, correct arithmetic</li>
 *   <li>Fallback pricing used (one FALLBACK_USED) — HTTP 200, VALID, one warning</li>
 *   <li>Degraded — one group fully unavailable — HTTP 200, DEGRADED, zero contribution</li>
 *   <li>Review Required — two groups fully unavailable — HTTP 200, REVIEW_REQUIRED</li>
 *   <li>Invalid weight sum — HTTP 400, INVALID_INPUT</li>
 *   <li>Idempotent repeat request — both responses share the same processedAt</li>
 *   <li>Missing required field (null requestId) — HTTP 400, VALIDATION_FAILED</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase
class AttributionIntegrationTest {

    private static final String PATH          = "/api/performance/attribution";
    private static final LocalDate VALUATION  = LocalDate.of(2026, 6, 30);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AttributionAuditRepository auditRepository;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeEach
    void clearAuditTable() {
        auditRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Builder helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a {@link GroupInput} with all four fields explicitly controlled.
     * Pass {@code null} for {@code returnPct} / {@code fallbackReturnPct} to
     * simulate missing pricing data.
     */
    private static GroupInput group(String name, String weight,
                                    String returnPct, String fallback) {
        return GroupInput.builder()
                .groupName(name)
                .weightPct(new BigDecimal(weight))
                .returnPct(returnPct == null ? null : new BigDecimal(returnPct))
                .fallbackReturnPct(fallback == null ? null : new BigDecimal(fallback))
                .build();
    }

    /** Builds a fully populated {@link AttributionRequest} from the supplied groups. */
    private static AttributionRequest request(String requestId, List<GroupInput> groups) {
        return AttributionRequest.builder()
                .requestId(requestId)
                .portfolioId("PF-INT-" + requestId)
                .valuationDate(VALUATION)
                .groups(groups)
                .currency("USD")
                .requestedBy("integration-test")
                .build();
    }

    // -----------------------------------------------------------------------
    // Test 1 — Valid full pricing (all PRIMARY)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 1: All groups PRIMARY → HTTP 200, VALID, degraded=false, correct arithmetic")
    void allPrimary_returns200_validStatus_correctArithmetic() {
        // (60×1.50/100) + (30×0.40/100) + (10×0.05/100) = 0.90 + 0.12 + 0.005 = 1.025000
        List<GroupInput> groups = List.of(
                group("US Equities",  "60", "1.50", null),
                group("Fixed Income", "30", "0.40", null),
                group("Alternatives", "10", "0.05", null)
        );
        AttributionRequest req = request("REQ-INT-ALL-PRIMARY", groups);

        ResponseEntity<AttributionResponse> resp =
                restTemplate.postForEntity(PATH, req, AttributionResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AttributionResponse body = resp.getBody();
        assertNotNull(body);

        assertAll("All-PRIMARY response",
                () -> assertEquals(AttributionStatus.VALID,  body.getStatus()),
                () -> assertFalse(body.isDegraded(),         "degraded must be false"),
                () -> assertTrue(body.getWarnings().isEmpty(),"warnings must be empty"),

                // Per-group pricing modes
                () -> assertEquals(PricingMode.PRIMARY,
                        contributionFor(body, "US Equities").getPricingMode()),
                () -> assertEquals(PricingMode.PRIMARY,
                        contributionFor(body, "Fixed Income").getPricingMode()),
                () -> assertEquals(PricingMode.PRIMARY,
                        contributionFor(body, "Alternatives").getPricingMode()),

                // Arithmetic: total must equal 1.025000
                () -> assertEquals(0,
                        new BigDecimal("1.025000").compareTo(body.getTotalContributionPct()),
                        "totalContributionPct must be 1.025000"),

                // Per-group contributions
                () -> assertEquals(0,
                        new BigDecimal("0.900000")
                                .compareTo(contributionFor(body, "US Equities").getContributionPct()),
                        "US Equities contribution must be 0.900000"),
                () -> assertEquals(0,
                        new BigDecimal("0.120000")
                                .compareTo(contributionFor(body, "Fixed Income").getContributionPct()),
                        "Fixed Income contribution must be 0.120000"),
                () -> assertEquals(0,
                        new BigDecimal("0.005000")
                                .compareTo(contributionFor(body, "Alternatives").getContributionPct()),
                        "Alternatives contribution must be 0.005000")
        );
    }

    // -----------------------------------------------------------------------
    // Test 2 — Fallback pricing used (one FALLBACK_USED)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 2: One FALLBACK_USED → HTTP 200, VALID, degraded=false, one warning, correct total")
    void oneFallback_returns200_validStatus_oneWarning_correctTotal() {
        // Equities: 70 × 2.00 / 100 = 1.400000
        // Bonds:    30 × 0.50 / 100 = 0.150000  (via fallback)
        // Total = 1.550000
        List<GroupInput> groups = List.of(
                group("Equities", "70", "2.00", null),
                group("Bonds",    "30",  null,  "0.50")   // primary absent, fallback present
        );
        AttributionRequest req = request("REQ-INT-FALLBACK", groups);

        ResponseEntity<AttributionResponse> resp =
                restTemplate.postForEntity(PATH, req, AttributionResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AttributionResponse body = resp.getBody();
        assertNotNull(body);

        assertAll("FALLBACK_USED response",
                () -> assertEquals(AttributionStatus.VALID, body.getStatus()),
                () -> assertFalse(body.isDegraded(),        "degraded must be false"),

                // Bonds must be tagged FALLBACK_USED
                () -> assertEquals(PricingMode.FALLBACK_USED,
                        contributionFor(body, "Bonds").getPricingMode()),
                () -> assertEquals(PricingMode.PRIMARY,
                        contributionFor(body, "Equities").getPricingMode()),

                // Exactly one warning that mentions the group name
                () -> assertEquals(1, body.getWarnings().size(),
                        "Expected exactly one fallback warning"),
                () -> assertTrue(body.getWarnings().get(0).contains("Bonds"),
                        "Warning must mention 'Bonds'"),

                // Total must include the fallback contribution
                () -> assertEquals(0,
                        new BigDecimal("1.550000").compareTo(body.getTotalContributionPct()),
                        "totalContributionPct must be 1.550000")
        );
    }

    // -----------------------------------------------------------------------
    // Test 3 — Degraded (one group fully unavailable)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 3: One UNAVAILABLE → HTTP 200, DEGRADED, degraded=true, one warning, zero contribution")
    void oneUnavailable_returns200_degradedStatus_zeroContribution() {
        // Equities: 70 × 1.00 / 100 = 0.700000
        // Alternatives: no pricing → contributes 0
        // Total = 0.700000
        List<GroupInput> groups = List.of(
                group("Equities",    "70", "1.00", null),
                group("Alternatives","30",  null,   null)   // no pricing at all
        );
        AttributionRequest req = request("REQ-INT-DEGRADED", groups);

        ResponseEntity<AttributionResponse> resp =
                restTemplate.postForEntity(PATH, req, AttributionResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AttributionResponse body = resp.getBody();
        assertNotNull(body);

        assertAll("DEGRADED response",
                () -> assertEquals(AttributionStatus.DEGRADED, body.getStatus()),
                () -> assertTrue(body.isDegraded(),            "degraded must be true"),

                // Exactly one unavailability warning mentioning the group
                () -> assertEquals(1, body.getWarnings().size(),
                        "Expected exactly one unavailability warning"),
                () -> assertTrue(body.getWarnings().get(0).contains("Alternatives"),
                        "Warning must mention 'Alternatives'"),

                // Unavailable group's contribution must be zero
                () -> assertEquals(PricingMode.UNAVAILABLE,
                        contributionFor(body, "Alternatives").getPricingMode()),
                () -> assertEquals(0,
                        BigDecimal.ZERO.compareTo(
                                contributionFor(body, "Alternatives").getContributionPct()),
                        "Alternatives contribution must be 0.0"),

                // Total must exclude the missing group
                () -> assertEquals(0,
                        new BigDecimal("0.700000").compareTo(body.getTotalContributionPct()),
                        "totalContributionPct must be 0.700000")
        );
    }

    // -----------------------------------------------------------------------
    // Test 4 — Review Required (two groups fully unavailable)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 4: Two UNAVAILABLE → HTTP 200, REVIEW_REQUIRED, degraded=true, two warnings")
    void twoUnavailable_returns200_reviewRequiredStatus_twoWarnings() {
        List<GroupInput> groups = List.of(
                group("Equities",    "50", "1.00", null),
                group("Alternatives","30",  null,   null),   // no pricing
                group("Commodities", "20",  null,   null)    // no pricing
        );
        AttributionRequest req = request("REQ-INT-REVIEW", groups);

        ResponseEntity<AttributionResponse> resp =
                restTemplate.postForEntity(PATH, req, AttributionResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AttributionResponse body = resp.getBody();
        assertNotNull(body);

        assertAll("REVIEW_REQUIRED response",
                () -> assertEquals(AttributionStatus.REVIEW_REQUIRED, body.getStatus()),
                () -> assertTrue(body.isDegraded(), "degraded must be true"),

                // Both unavailable groups must have their own warning
                () -> assertEquals(2, body.getWarnings().size(),
                        "Expected exactly two unavailability warnings"),
                () -> assertTrue(body.getWarnings().stream()
                        .anyMatch(w -> w.contains("Alternatives")),
                        "One warning must mention 'Alternatives'"),
                () -> assertTrue(body.getWarnings().stream()
                        .anyMatch(w -> w.contains("Commodities")),
                        "One warning must mention 'Commodities'"),

                // Both unavailable groups contribute zero
                () -> assertEquals(PricingMode.UNAVAILABLE,
                        contributionFor(body, "Alternatives").getPricingMode()),
                () -> assertEquals(PricingMode.UNAVAILABLE,
                        contributionFor(body, "Commodities").getPricingMode())
        );
    }

    // -----------------------------------------------------------------------
    // Test 5 — Invalid weight sum (< 99%)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 5: Groups summing to 80% → HTTP 400, error=INVALID_INPUT")
    @SuppressWarnings("unchecked")
    void invalidWeightSum_returns400_invalidInputError() {
        // 60 + 20 = 80 — deliberately below the 99% minimum
        List<GroupInput> groups = List.of(
                group("Equities", "60", "1.00", null),
                group("Bonds",    "20", "0.50", null)
        );
        AttributionRequest req = request("REQ-INT-BAD-WEIGHT", groups);

        ResponseEntity<Map> resp = restTemplate.postForEntity(PATH, req, Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertAll("INVALID_INPUT error body",
                () -> assertEquals("INVALID_INPUT", body.get("error"),
                        "error code must be INVALID_INPUT"),
                () -> assertNotNull(body.get("message"),
                        "message must be present"),
                () -> assertTrue(body.get("message").toString().contains("80.00"),
                        "message must quote the actual weight sum")
        );
    }

    // -----------------------------------------------------------------------
    // Test 6 — Idempotent repeat request
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 6: Same requestId sent twice → identical processedAt, exactly one audit row")
    void idempotentRepeat_sameProcessedAt_singleAuditRow() {
        List<GroupInput> groups = List.of(
                group("US Equities",  "60", "1.50", null),
                group("Fixed Income", "30", "0.40", null),
                group("Alternatives", "10", "0.05", null)
        );
        // Both calls share the exact same requestId
        AttributionRequest req = request("REQ-INT-IDEM-001", groups);

        ResponseEntity<AttributionResponse> first =
                restTemplate.postForEntity(PATH, req, AttributionResponse.class);
        ResponseEntity<AttributionResponse> second =
                restTemplate.postForEntity(PATH, req, AttributionResponse.class);

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());

        AttributionResponse firstBody  = first.getBody();
        AttributionResponse secondBody = second.getBody();
        assertNotNull(firstBody);
        assertNotNull(secondBody);

        // (60×1.50/100) + (30×0.40/100) + (10×0.05/100) = 1.025000
        BigDecimal expectedTotal = new BigDecimal("1.025000");

        assertAll("Idempotency guarantees",
                // The processedAt timestamp must be identical — the second call was not recomputed
                () -> assertEquals(firstBody.getProcessedAt(), secondBody.getProcessedAt(),
                        "processedAt must be the same for both calls (served from cache)"),

                // Both responses must reflect the same request
                () -> assertEquals(firstBody.getRequestId(),  secondBody.getRequestId()),
                () -> assertEquals(firstBody.getPortfolioId(), secondBody.getPortfolioId()),
                () -> assertEquals(firstBody.getStatus(),     secondBody.getStatus()),

                // Each response must carry the correct total (verifies no rounding on DB round-trip)
                () -> assertEquals(0, expectedTotal.compareTo(firstBody.getTotalContributionPct()),
                        "First response totalContributionPct must be 1.025000"),
                () -> assertEquals(0, expectedTotal.compareTo(secondBody.getTotalContributionPct()),
                        "Cached response totalContributionPct must be 1.025000 (no rounding in DB)"),

                // Database must have exactly one audit row for this requestId
                () -> assertEquals(1L, auditRepository.count(),
                        "Exactly one audit row must exist after two calls with the same requestId")
        );
    }

    // -----------------------------------------------------------------------
    // Test 7 — Missing required field (null requestId)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Test 7: Null requestId → HTTP 400, error=VALIDATION_FAILED, fieldErrors present")
    @SuppressWarnings("unchecked")
    void nullRequestId_returns400_validationFailed() {
        // requestId is intentionally omitted (null) — @NotBlank must trigger
        AttributionRequest req = AttributionRequest.builder()
                .requestId(null)
                .portfolioId("PF-INT-VAL")
                .valuationDate(VALUATION)
                .groups(List.of(group("Equities", "100", "1.00", null)))
                .currency("USD")
                .requestedBy("integration-test")
                .build();

        ResponseEntity<Map> resp = restTemplate.postForEntity(PATH, req, Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertAll("VALIDATION_FAILED error body",
                () -> assertEquals("VALIDATION_FAILED", body.get("error"),
                        "error code must be VALIDATION_FAILED"),
                () -> assertEquals("Request validation failed", body.get("message")),
                () -> assertNotNull(body.get("fieldErrors"),
                        "fieldErrors array must be present")
        );
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Locates the {@link com.portfolio.performance.model.response.GroupContributionResult}
     * for the given group name within the response; throws {@link AssertionError} if absent.
     */
    private static com.portfolio.performance.model.response.GroupContributionResult
            contributionFor(AttributionResponse body, String groupName) {
        return body.getGroupContributions().stream()
                .filter(g -> groupName.equals(g.getGroupName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No GroupContributionResult found for group: " + groupName));
    }
}
