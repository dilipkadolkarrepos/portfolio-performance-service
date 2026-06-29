package com.portfolio.performance.controller;

import com.portfolio.performance.model.enums.PerformanceStatus;
import com.portfolio.performance.model.request.DailyReturnRequest;
import com.portfolio.performance.model.response.DailyReturnResponse;
import com.portfolio.performance.repository.DailyReturnAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase
class PerformanceControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DailyReturnAuditRepository auditRepository;

    private static final String PATH = "/api/performance/daily-return";
    private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 6, 14);

    @BeforeEach
    void clearAuditTable() {
        auditRepository.deleteAll();
    }

    private DailyReturnRequest buildRequest(String portfolioId,
                                            double begin, double end,
                                            double cashFlow, double benchmark,
                                            String currency) {
        return DailyReturnRequest.builder()
                .portfolioId(portfolioId)
                .valuationDate(VALUATION_DATE)
                .beginMarketValue(BigDecimal.valueOf(begin))
                .endMarketValue(BigDecimal.valueOf(end))
                .netCashFlow(BigDecimal.valueOf(cashFlow))
                .benchmarkReturnPct(BigDecimal.valueOf(benchmark))
                .currency(currency)
                .requestedBy("integration-test")
                .build();
    }

    @Test
    void givenValidRequest_whenPostDailyReturn_thenReturns200AndValidStatus() {
        DailyReturnRequest request = buildRequest("PF-IT-001", 1_000_000, 1_035_000, 10_000, 1.8, "USD");

        ResponseEntity<DailyReturnResponse> response = restTemplate.postForEntity(PATH, request, DailyReturnResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DailyReturnResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(PerformanceStatus.VALID, body.getStatus());
        assertEquals(new BigDecimal("2.50"), body.getPortfolioReturnPct());
        assertEquals(new BigDecimal("0.70"), body.getExcessReturnPct());
        assertTrue(body.getReasons().isEmpty());

        var auditRows = auditRepository.findByPortfolioId("PF-IT-001");
        assertEquals(1, auditRows.size());
        assertEquals("VALID", auditRows.get(0).getStatus());
    }

    @Test
    void givenHighBenchmarkDeviation_whenPostDailyReturn_thenReturns200AndReviewRequired() {
        DailyReturnRequest request = buildRequest("PF-IT-002", 1_000_000, 1_100_000, 0, 1.0, "USD");

        ResponseEntity<DailyReturnResponse> response = restTemplate.postForEntity(PATH, request, DailyReturnResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DailyReturnResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(PerformanceStatus.REVIEW_REQUIRED, body.getStatus());
        assertTrue(body.getReasons().contains("Portfolio return deviates from benchmark by more than 5%"));
    }

    @Test
    void givenHighCashFlow_whenPostDailyReturn_thenReturns200AndReviewRequired() {
        DailyReturnRequest request = buildRequest("PF-IT-003", 1_000_000, 1_005_000, 300_000, 1.0, "USD");

        ResponseEntity<DailyReturnResponse> response = restTemplate.postForEntity(PATH, request, DailyReturnResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DailyReturnResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(PerformanceStatus.REVIEW_REQUIRED, body.getStatus());
        assertTrue(body.getReasons().contains("Net cash flow exceeds 20% of begin market value"));
    }

    @Test
    void givenNegativeBeginMarketValue_whenPostDailyReturn_thenReturns422() {
        DailyReturnRequest request = buildRequest("PF-IT-004", -100, 1_000, 0, 1.0, "USD");

        ResponseEntity<DailyReturnResponse> response = restTemplate.postForEntity(PATH, request, DailyReturnResponse.class);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        DailyReturnResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(PerformanceStatus.INVALID_INPUT, body.getStatus());
        assertNull(body.getPortfolioReturnPct());
    }

    @Test
    @SuppressWarnings("unchecked")
    void givenBlankCurrency_whenPostDailyReturn_thenReturns400ValidationFailed() {
        DailyReturnRequest request = buildRequest("PF-IT-005", 1_000_000, 1_035_000, 0, 1.0, "");

        ResponseEntity<Map> response = restTemplate.postForEntity(PATH, request, Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Validation Failed", body.get("error"));
    }

    @Test
    void givenZeroBeginValueAndNonZeroEndValue_whenPostDailyReturn_thenReturns422InvalidInput() {
        DailyReturnRequest request = buildRequest("PF-IT-006", 0, 50_000, 0, 1.0, "USD");

        ResponseEntity<DailyReturnResponse> response = restTemplate.postForEntity(PATH, request, DailyReturnResponse.class);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        DailyReturnResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(PerformanceStatus.INVALID_INPUT, body.getStatus());
        assertFalse(body.getReasons().isEmpty());
        assertTrue(body.getReasons().stream()
                .anyMatch(r -> r.contains("zero") || r.contains("begin")));
    }
}
