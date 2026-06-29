package com.portfolio.performance.repository;

import com.portfolio.performance.model.entity.AttributionAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DataJpaTest} slice test for {@link AttributionAuditRepository}.
 *
 * <p>Spring spins up an in-memory H2 database, runs Hibernate DDL to create
 * {@code attribution_audit}, and wires only the JPA layer — no full application
 * context required. Each test method gets a clean database because
 * {@code @DataJpaTest} wraps every test in a transaction that is rolled back
 * on completion.
 */
@DataJpaTest
@ActiveProfiles("test")
class AttributionAuditRepositoryTest {

    @Autowired
    private AttributionAuditRepository repository;

    // ------------------------------------------------------------------
    // Shared fixture — one persisted record used across all tests
    // ------------------------------------------------------------------

    private static final String REQUEST_ID   = "REQ-AUDIT-001";
    private static final String PORTFOLIO_ID = "PF-GLOBAL-42";
    private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 6, 30);

    private AttributionAudit savedAudit;

    @BeforeEach
    void persistBaseRecord() {
        AttributionAudit audit = AttributionAudit.builder()
                .requestId(REQUEST_ID)
                .portfolioId(PORTFOLIO_ID)
                .valuationDate(VALUATION_DATE)
                .status("REVIEW_REQUIRED")
                .totalContributionPct(new BigDecimal("0.760000"))
                .degraded(true)
                .warnings("""
                        ["Fixed Income: returnPct null — fallback applied (0.45%)",\
                         "Alternatives: no returnPct or fallbackReturnPct — contribution set to 0"]\
                        """)
                .groupContributionsJson("""
                        [{"group_name":"US Equities","contribution_pct":0.625000,"pricing_mode":"PRIMARY"},\
                         {"group_name":"Fixed Income","contribution_pct":0.135000,"pricing_mode":"FALLBACK_USED"},\
                         {"group_name":"Alternatives","contribution_pct":0.000000,"pricing_mode":"UNAVAILABLE"}]\
                        """)
                .requestedBy("analyst@example.com")
                .currency("USD")
                .processedAt(Instant.parse("2026-06-30T10:45:00Z"))
                .build();

        savedAudit = repository.save(audit);
    }

    // ------------------------------------------------------------------
    // 1. findByRequestId — primary idempotency look-up
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("findByRequestId")
    class FindByRequestId {

        @Test
        @DisplayName("Returns a non-empty Optional for a known requestId")
        void knownRequestId_returnsNonEmptyOptional() {
            Optional<AttributionAudit> result = repository.findByRequestId(REQUEST_ID);

            assertTrue(result.isPresent(),
                    "findByRequestId must return a non-empty Optional for a persisted requestId");
        }

        @Test
        @DisplayName("Returned record has the correct portfolioId")
        void returnedRecord_hasCorrectPortfolioId() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertEquals(PORTFOLIO_ID, found.getPortfolioId(),
                    "portfolioId must match the value that was saved");
        }

        @Test
        @DisplayName("Returned record has the correct valuationDate")
        void returnedRecord_hasCorrectValuationDate() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertEquals(VALUATION_DATE, found.getValuationDate());
        }

        @Test
        @DisplayName("Returned record has the correct status string")
        void returnedRecord_hasCorrectStatus() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertEquals("REVIEW_REQUIRED", found.getStatus());
        }

        @Test
        @DisplayName("Returned record has degraded flag set to true")
        void returnedRecord_hasDegradedTrue() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertTrue(found.isDegraded(), "degraded flag must survive the persistence round-trip");
        }

        @Test
        @DisplayName("Returned record has non-null warnings string")
        void returnedRecord_hasNonNullWarnings() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertNotNull(found.getWarnings(), "warnings must not be null after retrieval");
        }

        @Test
        @DisplayName("Returned record has non-null groupContributionsJson string")
        void returnedRecord_hasNonNullGroupContributionsJson() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertNotNull(found.getGroupContributionsJson(),
                    "groupContributionsJson must not be null after retrieval");
        }

        @Test
        @DisplayName("Returns empty Optional for an unknown requestId")
        void unknownRequestId_returnsEmptyOptional() {
            Optional<AttributionAudit> result = repository.findByRequestId("REQ-DOES-NOT-EXIST");

            assertTrue(result.isEmpty(),
                    "findByRequestId must return an empty Optional for an unknown requestId");
        }
    }

    // ------------------------------------------------------------------
    // 2. save / findById — basic JPA health check
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("save and findById")
    class SaveAndFindById {

        @Test
        @DisplayName("Saved record receives a generated id")
        void save_assignsGeneratedId() {
            assertNotNull(savedAudit.getId(),
                    "JPA must assign a generated id after save");
        }

        @Test
        @DisplayName("findById returns the same record that was saved")
        void findById_returnsSavedRecord() {
            Optional<AttributionAudit> byId = repository.findById(savedAudit.getId());

            assertTrue(byId.isPresent(), "findById must find the saved record");
            assertEquals(REQUEST_ID, byId.get().getRequestId());
        }

        @Test
        @DisplayName("totalContributionPct round-trips with correct scale")
        void save_totalContributionPct_roundTrips() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertEquals(0,
                    new BigDecimal("0.760000").compareTo(found.getTotalContributionPct()),
                    "totalContributionPct must survive the persistence round-trip numerically");
        }

        @Test
        @DisplayName("processedAt round-trips as the same Instant")
        void save_processedAt_roundTrips() {
            AttributionAudit found = repository.findByRequestId(REQUEST_ID).orElseThrow();

            assertEquals(Instant.parse("2026-06-30T10:45:00Z"), found.getProcessedAt());
        }
    }

    // ------------------------------------------------------------------
    // 3. unique constraint on requestId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("requestId uniqueness constraint")
    class UniquenessConstraint {

        @Test
        @DisplayName("Saving a second record with the same requestId throws a DataIntegrityViolationException")
        void duplicateRequestId_throwsException() {
            AttributionAudit duplicate = AttributionAudit.builder()
                    .requestId(REQUEST_ID)          // same idempotency key
                    .portfolioId("PF-OTHER")
                    .valuationDate(VALUATION_DATE)
                    .status("VALID")
                    .degraded(false)
                    .processedAt(Instant.now())
                    .build();

            // saveAndFlush forces the INSERT immediately so the constraint fires inside the test
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> repository.saveAndFlush(duplicate),
                    "Persisting a duplicate requestId must violate the unique constraint"
            );
        }
    }
}
