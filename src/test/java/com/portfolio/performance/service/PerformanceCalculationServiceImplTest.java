package com.portfolio.performance.service;

import com.portfolio.performance.model.request.DailyReturnRequest;
import com.portfolio.performance.model.response.DailyReturnResponse;
import com.portfolio.performance.model.enums.PerformanceStatus;
import com.portfolio.performance.repository.DailyReturnAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceCalculationServiceImplTest {

    @Mock
    private DailyReturnAuditRepository repository;

    @InjectMocks
    private PerformanceCalculationServiceImpl service;

    private void mockSave() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void happyPath_VALID() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-1")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000000"))
                .endMarketValue(new BigDecimal("1035000"))
                .netCashFlow(new BigDecimal("10000"))
                .benchmarkReturnPct(new BigDecimal("1.8"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.VALID, resp.getStatus());
        assertEquals(new BigDecimal("2.50"), resp.getPortfolioReturnPct());
        assertEquals(new BigDecimal("0.70"), resp.getExcessReturnPct());
        assertTrue(resp.getReasons().isEmpty());
        assertNotNull(resp.getProcessedAt());
    }

    @Test
    void reviewRequired_benchmarkDeviation() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-2")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000000"))
                .endMarketValue(new BigDecimal("1080000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("1.0"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.REVIEW_REQUIRED, resp.getStatus());
        assertTrue(resp.getReasons().contains("Portfolio return deviates from benchmark by more than 5%"));
    }

    @Test
    void reviewRequired_cashFlowThreshold() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-3")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000000"))
                .endMarketValue(new BigDecimal("1010000"))
                .netCashFlow(new BigDecimal("250000"))
                .benchmarkReturnPct(new BigDecimal("1.0"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.REVIEW_REQUIRED, resp.getStatus());
        assertTrue(resp.getReasons().contains("Net cash flow exceeds 20% of begin market value"));
    }

    @Test
    void invalidInput_negativeBegin() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-4")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("-500"))
                .endMarketValue(new BigDecimal("1000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("0"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.INVALID_INPUT, resp.getStatus());
        assertNull(resp.getPortfolioReturnPct());
    }

    @Test
    void invalidInput_negativeEnd() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-5")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000"))
                .endMarketValue(new BigDecimal("-100"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("0"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.INVALID_INPUT, resp.getStatus());
    }

    @Test
    void invalidInput_missingCurrency() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-6")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000"))
                .endMarketValue(new BigDecimal("2000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("0"))
                .currency("")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.INVALID_INPUT, resp.getStatus());
    }

    @Test
    void invalidInput_zeroBeginNonZeroEnd() {
        mockSave();
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-7")
                .valuationDate(LocalDate.now())
                .beginMarketValue(BigDecimal.ZERO)
                .endMarketValue(new BigDecimal("50000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("0"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        DailyReturnResponse resp = service.calculate(req);
        assertEquals(PerformanceStatus.INVALID_INPUT, resp.getStatus());
    }
}


