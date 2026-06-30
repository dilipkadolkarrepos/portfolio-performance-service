package com.portfolio.performance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.performance.exception.InvalidAttributionInputException;
import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.repository.AttributionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributionServiceImpl}.
 *
 * <p>Two complementary strategies are used:
 * <ul>
 *   <li>{@link UnitTests} — Mockito only, no Spring context, exercises the
 *       orchestration logic in pure isolation for the happy-path and invalid-weight
 *       gate cases.</li>
 *   <li>{@link IntegrationTests} — {@code @DataJpaTest} slice with real H2 and the
 *       full service stack wired together, verifying the idempotency row-count gate
 *       against an actual database.</li>
 * </ul>
 */
class AttributionServiceImplTest {

    // ===================================================================
    // Shared fixture helpers — used by both nested classes
    // ===================================================================

    /** Builds a request whose groups sum to exactly 100 %. */
    static AttributionRequest validRequest(String requestId) {
        return AttributionRequest.builder()
                .requestId(requestId)
                .portfolioId("PF-SVC-42")
                .valuationDate(LocalDate.of(2026, 6, 30))
                .groups(List.of(
                        GroupInput.builder()
                                .groupName("Equities")
                                .weightPct(new BigDecimal("60"))
                                .returnPct(new BigDecimal("1.50"))
                                .build(),
                        GroupInput.builder()
                                .groupName("Bonds")
                                .weightPct(new BigDecimal("30"))
                                .returnPct(new BigDecimal("0.40"))
                                .build(),
                        GroupInput.builder()
                                .groupName("Alternatives")
                                .weightPct(new BigDecimal("10"))
                                .returnPct(new BigDecimal("0.05"))
                                .build()
                ))
                .currency("USD")
                .requestedBy("tester@example.com")
                .build();
    }

    /** Builds a request whose groups sum to only 95 % — must fail validation. */
    static AttributionRequest invalidWeightRequest() {
        return AttributionRequest.builder()
                .requestId("REQ-BAD-WEIGHT")
                .portfolioId("PF-SVC-99")
                .valuationDate(LocalDate.of(2026, 6, 30))
                .groups(List.of(
                        GroupInput.builder()
                                .groupName("Equities")
                                .weightPct(new BigDecimal("75"))
                                .returnPct(new BigDecimal("1.00"))
                                .build(),
                        GroupInput.builder()
                                .groupName("Bonds")
                                .weightPct(new BigDecimal("20"))
                                .returnPct(new BigDecimal("0.50"))
                                .build()
                        // total = 95 — deliberately below 99
                ))
                .currency("USD")
                .requestedBy("tester@example.com")
                .build();
    }

    // ===================================================================
    // Nested class 1 — pure unit tests with Mockito (no Spring context)
    // ===================================================================

    @Nested
    @DisplayName("Unit tests — Mockito, no Spring context")
    @ExtendWith(MockitoExtension.class)
    class UnitTests {

        /**
         * Real collaborators instantiated directly — no mocks for the core engine.
         * Only {@link IdempotencyService} is mocked so we avoid needing a database.
         */
        private AttributionValidator  validator;
        private AttributionCalculator calculator;
        private IdempotencyService    idempotencyService;  // mocked via field below
        private AttributionServiceImpl service;

        // Mockito doesn't inject into @Nested without @ExtendWith on the outer class,
        // so we build the mock manually.
        private org.mockito.Mock idempotencyMock;

        @BeforeEach
        void setUp() {
            validator          = new AttributionValidator();
            calculator         = new AttributionCalculator(new PricingResilienceSimulator());
            idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
            service            = new AttributionServiceImpl(validator, calculator, idempotencyService);
        }

        // ------------------------------------------------------------------
        // Gate case 1 — happy path: VALID response returned and persisted
        // ------------------------------------------------------------------

        @Test
        @DisplayName("Gate 1: valid request → VALID response returned, persistResult called once")
        void happyPath_validRequest_returnsValidResponse_andPersists() {
            AttributionRequest req = validRequest("REQ-HAPPY-001");

            // No stored result yet
            org.mockito.Mockito.when(idempotencyService.findExistingResult("REQ-HAPPY-001"))
                    .thenReturn(java.util.Optional.empty());

            AttributionResponse response = service.processAttribution(req);

            assertAll("Happy-path response",
                    () -> assertNotNull(response),
                    () -> assertEquals(AttributionStatus.VALID, response.getStatus()),
                    () -> assertFalse(response.isDegraded()),
                    () -> assertTrue(response.getWarnings().isEmpty()),
                    () -> assertEquals("PF-SVC-42", response.getPortfolioId()),
                    () -> assertEquals("REQ-HAPPY-001", response.getRequestId())
            );

            // persistResult must be called exactly once with the same request and response
            org.mockito.Mockito.verify(idempotencyService, org.mockito.Mockito.times(1))
                    .persistResult(org.mockito.Mockito.eq(req),
                                   org.mockito.Mockito.any(AttributionResponse.class));
        }

        @Test
        @DisplayName("Gate 1: total contribution is mathematically correct (1.025000)")
        void happyPath_mathIsCorrect() {
            AttributionRequest req = validRequest("REQ-MATH-001");

            org.mockito.Mockito.when(idempotencyService.findExistingResult("REQ-MATH-001"))
                    .thenReturn(java.util.Optional.empty());

            AttributionResponse response = service.processAttribution(req);

            // (60×1.50/100) + (30×0.40/100) + (10×0.05/100) = 0.90 + 0.12 + 0.005 = 1.025
            assertEquals(0, new BigDecimal("1.025000").compareTo(response.getTotalContributionPct()),
                    "Total contribution must be 1.025000");
        }

        // ------------------------------------------------------------------
        // Gate case 2 — idempotency: second call returns cached response,
        //                             persistResult NOT called again
        // ------------------------------------------------------------------

        @Test
        @DisplayName("Gate 2: second call with same requestId returns cached response without re-persisting")
        void idempotency_secondCall_returnsCachedResponse_noPersist() {
            AttributionRequest req = validRequest("REQ-IDEM-001");

            // Build a canned cached response
            AttributionResponse cached = AttributionResponse.builder()
                    .requestId("REQ-IDEM-001")
                    .portfolioId("PF-SVC-42")
                    .valuationDate(LocalDate.of(2026, 6, 30))
                    .status(AttributionStatus.VALID)
                    .degraded(false)
                    .totalContributionPct(new BigDecimal("1.025000"))
                    .groupContributions(List.of())
                    .warnings(List.of())
                    .processedAt(java.time.Instant.now())
                    .build();

            org.mockito.Mockito.when(idempotencyService.findExistingResult("REQ-IDEM-001"))
                    .thenReturn(java.util.Optional.of(cached));

            AttributionResponse result = service.processAttribution(req);

            // Must be the exact cached object
            assertSame(cached, result, "Idempotent call must return the cached response object");

            // persistResult must NEVER be called for an idempotent hit
            org.mockito.Mockito.verify(idempotencyService, org.mockito.Mockito.never())
                    .persistResult(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        }

        // ------------------------------------------------------------------
        // Gate case 3 — invalid weights: exception propagates
        // ------------------------------------------------------------------

        @Test
        @DisplayName("Gate 3: weights summing to 95% → InvalidAttributionInputException thrown")
        void invalidWeights_exceptionPropagates() {
            AttributionRequest req = invalidWeightRequest();

            org.mockito.Mockito.when(idempotencyService.findExistingResult("REQ-BAD-WEIGHT"))
                    .thenReturn(java.util.Optional.empty());

            InvalidAttributionInputException ex = assertThrows(
                    InvalidAttributionInputException.class,
                    () -> service.processAttribution(req),
                    "Must throw InvalidAttributionInputException for out-of-range weights"
            );

            assertTrue(ex.getMessage().contains("95.00"),
                    "Exception message must include the actual total: " + ex.getMessage());
        }

        @Test
        @DisplayName("Gate 3: persistResult is never called when validation fails")
        void invalidWeights_persistResultNotCalled() {
            AttributionRequest req = invalidWeightRequest();

            org.mockito.Mockito.when(idempotencyService.findExistingResult("REQ-BAD-WEIGHT"))
                    .thenReturn(java.util.Optional.empty());

            assertThrows(InvalidAttributionInputException.class,
                    () -> service.processAttribution(req));

            org.mockito.Mockito.verify(idempotencyService, org.mockito.Mockito.never())
                    .persistResult(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        }
    }

    // ===================================================================
    // Nested class 2 — integration tests with real H2 and full stack
    // ===================================================================

    @Nested
    @DisplayName("Integration tests — DataJpaTest slice with real H2")
    @DataJpaTest
    @ActiveProfiles("test")
    @Import({AttributionServiceImpl.class,
             AttributionValidator.class,
             AttributionCalculator.class,
             PricingResilienceSimulator.class,
             IdempotencyService.class})
    class IntegrationTests {

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
        private AttributionService service;

        @Autowired
        private AttributionAuditRepository repository;

        @Test
        @DisplayName("Gate 2 (integration): audit table has exactly 1 row after two calls with same requestId")
        void idempotency_auditTableHasOneRow_afterTwoCalls() {
            AttributionRequest req = validRequest("REQ-INT-IDEM-001");

            // First call — calculates and persists
            AttributionResponse first = service.processAttribution(req);
            assertNotNull(first);
            assertEquals(AttributionStatus.VALID, first.getStatus());

            // Second call — must hit idempotency and NOT insert another row
            AttributionResponse second = service.processAttribution(req);
            assertNotNull(second);
            assertEquals("PF-SVC-42", second.getPortfolioId());

            // Database must still hold exactly one row for this requestId
            long count = repository.count();
            assertEquals(1L, count,
                    "Exactly one AttributionAudit row must exist after two calls with the same requestId");
        }

        @Test
        @DisplayName("Gate 2 (integration): second call returns same portfolioId as first")
        void idempotency_secondCallMatchesFirstPortfolioId() {
            AttributionRequest req = validRequest("REQ-INT-IDEM-002");

            AttributionResponse first  = service.processAttribution(req);
            AttributionResponse second = service.processAttribution(req);

            assertEquals(first.getPortfolioId(), second.getPortfolioId());
            assertEquals(first.getStatus(),      second.getStatus());
            assertEquals(0, first.getTotalContributionPct()
                                  .compareTo(second.getTotalContributionPct()));
        }
    }
}
