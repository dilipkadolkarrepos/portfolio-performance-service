package com.portfolio.performance.repository;

import com.portfolio.performance.model.entity.DailyReturnAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class DailyReturnAuditRepositoryTest {

    @Autowired
    private DailyReturnAuditRepository repository;

    private DailyReturnAudit testAudit;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now();
        testAudit = DailyReturnAudit.builder()
                .portfolioId("PF-TEST")
                .valuationDate(today)
                .beginMarketValue(new BigDecimal("10000.00"))
                .endMarketValue(new BigDecimal("10500.00"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("3.50"))
                .currency("USD")
                .requestedBy("testuser")
                .portfolioReturnPct(new BigDecimal("5.00"))
                .excessReturnPct(new BigDecimal("1.50"))
                .status("VALID")
                .reasons(null)
                .processedAt(Instant.now())
                .build();

        repository.save(testAudit);
    }

    @Test
    void testFindByPortfolioId_ReturnsExactlyOne() {
        List<DailyReturnAudit> result = repository.findByPortfolioId("PF-TEST");
        assertEquals(1, result.size(), "findByPortfolioId('PF-TEST') should return exactly 1 result");
    }

    @Test
    void testFindByStatus_ReturnsAtLeastOne() {
        List<DailyReturnAudit> result = repository.findByStatus("VALID");
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "findByStatus('VALID') should return at least 1 result");
        assertTrue(result.size() >= 1, "findByStatus('VALID') should return at least 1 result");
    }

    @Test
    void testFindByPortfolioIdAndValuationDate_ReturnsOne() {
        List<DailyReturnAudit> result = repository.findByPortfolioIdAndValuationDate("PF-TEST", today);
        assertEquals(1, result.size(), "findByPortfolioIdAndValuationDate('PF-TEST', today) should return 1 result");
    }

    @Test
    void testFindByPortfolioId_UnknownId_ReturnsEmpty() {
        List<DailyReturnAudit> result = repository.findByPortfolioId("PF-UNKNOWN");
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "findByPortfolioId('PF-UNKNOWN') should return an empty list");
    }
}

