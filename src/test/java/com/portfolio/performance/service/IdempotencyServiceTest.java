package com.portfolio.performance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.model.response.GroupContributionResult;
import com.portfolio.performance.repository.AttributionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice test for {@link IdempotencyService}.
 *
 * <p>Uses {@code @DataJpaTest} so H2 + Hibernate are wired automatically with a
 * rolled-back transaction per test. {@code @Import(IdempotencyService.class)} pulls
 * the service under test into the slice context. The inner {@link JacksonConfig}
 * provides an {@link ObjectMapper} with {@code JavaTimeModule} registered —
 * exactly what Spring Boot auto-configuration would supply in production.
 *
 * <p>Gate cases covered:
 * <ol>
 *   <li>First call — {@code findExistingResult} returns empty</li>
 *   <li>After {@code persistResult} — second call returns stored response with matching {@code portfolioId}</li>
 *   <li>No duplicate rows created — {@code count()} remains 1</li>
 * </ol>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(IdempotencyService.class)
class IdempotencyServiceTest {

    /**
     * Provides the ObjectMapper bean required by {@link IdempotencyService}.
     * Mirrors the production auto-configuration: {@code JavaTimeModule} registered,
     * timestamp serialization disabled so {@link java.time.Instant} writes as ISO-8601.
     */
    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private AttributionAuditRepository repository;

    // ------------------------------------------------------------------
    // Shared fixtures
    // ------------------------------------------------------------------

    private static final String     REQUEST_ID    = "ATTR-TEST-01";
    private static final String     PORTFOLIO_ID  = "PF-IDEM-42";
    private static final LocalDate  VALUATION_DATE = LocalDate.of(2026, 6, 30);
    private static final Instant    PROCESSED_AT  = Instant.parse("2026-06-30T10:45:00Z");

    private AttributionRequest  request;
    private AttributionResponse response;

    @BeforeEach
    void buildFixtures() {
        GroupInput group1 = GroupInput.builder()
                .groupName("US Equities")
                .weightPct(new BigDecimal("60.00"))
                .returnPct(new BigDecimal("1.50"))
                .build();

        GroupInput group2 = GroupInput.builder()
                .groupName("Fixed Income")
                .weightPct(new BigDecimal("30.00"))
                .returnPct(null)
                .fallbackReturnPct(new BigDecimal("0.40"))
                .build();

        GroupInput group3 = GroupInput.builder()
                .groupName("Alternatives")
                .weightPct(new BigDecimal("10.00"))
                .returnPct(new BigDecimal("0.05"))
                .build();

        request = AttributionRequest.builder()
                .requestId(REQUEST_ID)
                .portfolioId(PORTFOLIO_ID)
                .valuationDate(VALUATION_DATE)
                .groups(List.of(group1, group2, group3))
                .currency("USD")
                .requestedBy("analyst@example.com")
                .build();

        GroupContributionResult result1 = GroupContributionResult.builder()
                .groupName("US Equities")
                .contributionPct(new BigDecimal("0.900000"))
                .pricingMode(PricingMode.PRIMARY)
                .build();

        GroupContributionResult result2 = GroupContributionResult.builder()
                .groupName("Fixed Income")
                .contributionPct(new BigDecimal("0.120000"))
                .pricingMode(PricingMode.FALLBACK_USED)
                .build();

        GroupContributionResult result3 = GroupContributionResult.builder()
                .groupName("Alternatives")
                .contributionPct(new BigDecimal("0.005000"))
                .pricingMode(PricingMode.PRIMARY)
                .build();

        response = AttributionResponse.builder()
                .requestId(REQUEST_ID)
                .portfolioId(PORTFOLIO_ID)
                .valuationDate(VALUATION_DATE)
                .totalContributionPct(new BigDecimal("1.025000"))
                .groupContributions(List.of(result1, result2, result3))
                .status(AttributionStatus.VALID)
                .degraded(false)
                .warnings(List.of("Fallback pricing used for Fixed Income"))
                .processedAt(PROCESSED_AT)
                .build();
    }

    // ------------------------------------------------------------------
    // Gate case 1 — first call returns empty
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gate 1: findExistingResult returns empty Optional before any persistResult call")
    void findExistingResult_beforePersist_returnsEmpty() {
        Optional<AttributionResponse> result = idempotencyService.findExistingResult(REQUEST_ID);

        assertTrue(result.isEmpty(),
                "findExistingResult must return empty when no record has been persisted yet");
    }

    // ------------------------------------------------------------------
    // Gate case 2 — after persistResult, second call returns stored response
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gate 2: findExistingResult returns stored response with matching portfolioId after persistResult")
    void findExistingResult_afterPersist_returnsStoredResponse() {
        idempotencyService.persistResult(request, response);

        Optional<AttributionResponse> result = idempotencyService.findExistingResult(REQUEST_ID);

        assertTrue(result.isPresent(),
                "findExistingResult must return a non-empty Optional after persistResult");

        AttributionResponse stored = result.get();
        assertAll("Reconstructed response fields",
                () -> assertEquals(PORTFOLIO_ID,    stored.getPortfolioId()),
                () -> assertEquals(REQUEST_ID,      stored.getRequestId()),
                () -> assertEquals(VALUATION_DATE,  stored.getValuationDate()),
                () -> assertEquals(AttributionStatus.VALID, stored.getStatus()),
                () -> assertFalse(stored.isDegraded()),
                () -> assertEquals(0, new BigDecimal("1.025000")
                                          .compareTo(stored.getTotalContributionPct())),
                () -> assertEquals(PROCESSED_AT,    stored.getProcessedAt())
        );
    }

    @Test
    @DisplayName("Gate 2b: reconstructed groupContributions round-trips correctly")
    void findExistingResult_afterPersist_groupContributionsRoundTrip() {
        idempotencyService.persistResult(request, response);

        List<GroupContributionResult> groups = idempotencyService
                .findExistingResult(REQUEST_ID)
                .orElseThrow()
                .getGroupContributions();

        assertNotNull(groups, "groupContributions must not be null");
        assertEquals(3, groups.size(), "All three groups must survive the JSON round-trip");

        GroupContributionResult equities = groups.stream()
                .filter(g -> "US Equities".equals(g.getGroupName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("US Equities not found after round-trip"));

        assertAll("US Equities round-trip",
                () -> assertEquals(PricingMode.PRIMARY, equities.getPricingMode()),
                () -> assertEquals(0, new BigDecimal("0.900000")
                                          .compareTo(equities.getContributionPct()))
        );
    }

    @Test
    @DisplayName("Gate 2c: reconstructed warnings round-trip correctly")
    void findExistingResult_afterPersist_warningsRoundTrip() {
        idempotencyService.persistResult(request, response);

        List<String> warnings = idempotencyService
                .findExistingResult(REQUEST_ID)
                .orElseThrow()
                .getWarnings();

        assertNotNull(warnings, "warnings must not be null");
        assertEquals(1, warnings.size());
        assertEquals("Fallback pricing used for Fixed Income", warnings.get(0));
    }

    // ------------------------------------------------------------------
    // Gate case 3 — no duplicate rows created for the same requestId
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gate 3: only one AttributionAudit row exists after multiple findExistingResult calls")
    void persistResult_onlyOneRowCreated_countRemainsOne() {
        // Persist once
        idempotencyService.persistResult(request, response);

        // Simulate the idempotency check that would happen on a second submission —
        // the service finds the existing record and does NOT call persistResult again.
        // We verify this by counting directly via the repository.
        Optional<AttributionResponse> hit = idempotencyService.findExistingResult(REQUEST_ID);
        assertTrue(hit.isPresent(), "Second lookup must return the stored result");

        long rowCount = repository.count();
        assertEquals(1L, rowCount,
                "Exactly one AttributionAudit row must exist — no duplicate was created");
    }

    @Test
    @DisplayName("Gate 3b: the unique constraint prevents a duplicate row even if persistResult is called twice")
    void persistResult_calledTwice_uniqueConstraintFires() {
        idempotencyService.persistResult(request, response);

        // A second call with the same requestId must violate the unique constraint.
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> {
                    idempotencyService.persistResult(request, response);
                    // flush forces the INSERT so the constraint fires inside this test
                    repository.flush();
                },
                "Calling persistResult twice for the same requestId must throw DataIntegrityViolationException"
        );
    }
}
