package com.portfolio.performance.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.performance.model.enums.PerformanceStatus;
import com.portfolio.performance.model.request.DailyReturnRequest;
import com.portfolio.performance.model.response.DailyReturnResponse;
import com.portfolio.performance.service.PerformanceCalculationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PerformanceController.class)
class PerformanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PerformanceCalculationService performanceCalculationService;

    private DailyReturnRequest validRequest() {
        return DailyReturnRequest.builder()
                .portfolioId("PF-1001")
                .valuationDate(LocalDate.of(2026, 6, 14))
                .beginMarketValue(new BigDecimal("1000000"))
                .endMarketValue(new BigDecimal("1035000"))
                .netCashFlow(new BigDecimal("10000"))
                .benchmarkReturnPct(new BigDecimal("1.8"))
                .currency("USD")
                .requestedBy("advisor01")
                .build();
    }

    @Test
    void calculateDailyReturn_validInput_returns200() throws Exception {
        DailyReturnResponse response = DailyReturnResponse.builder()
                .portfolioId("PF-1001")
                .valuationDate(LocalDate.of(2026, 6, 14))
                .portfolioReturnPct(new BigDecimal("2.50"))
                .benchmarkReturnPct(new BigDecimal("1.80"))
                .excessReturnPct(new BigDecimal("0.70"))
                .status(PerformanceStatus.VALID)
                .reasons(List.of())
                .processedAt(Instant.now())
                .build();

        when(performanceCalculationService.calculate(any())).thenReturn(response);

        mockMvc.perform(post("/api/performance/daily-return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.portfolioId").value("PF-1001"));
    }

    @Test
    void calculateDailyReturn_invalidInput_returns422() throws Exception {
        DailyReturnResponse response = DailyReturnResponse.builder()
                .portfolioId("PF-1002")
                .valuationDate(LocalDate.of(2026, 6, 14))
                .status(PerformanceStatus.INVALID_INPUT)
                .reasons(List.of("Begin market value must be non-negative"))
                .processedAt(Instant.now())
                .build();

        when(performanceCalculationService.calculate(any())).thenReturn(response);

        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-1002")
                .valuationDate(LocalDate.of(2026, 6, 14))
                .beginMarketValue(new BigDecimal("-100"))
                .endMarketValue(new BigDecimal("1000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("1.0"))
                .currency("USD")
                .requestedBy("advisor01")
                .build();

        mockMvc.perform(post("/api/performance/daily-return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("INVALID_INPUT"));
    }

    @Test
    void calculateDailyReturn_reviewRequired_returns200() throws Exception {
        DailyReturnResponse response = DailyReturnResponse.builder()
                .portfolioId("PF-1003")
                .valuationDate(LocalDate.of(2026, 6, 14))
                .portfolioReturnPct(new BigDecimal("8.00"))
                .benchmarkReturnPct(new BigDecimal("1.00"))
                .excessReturnPct(new BigDecimal("7.00"))
                .status(PerformanceStatus.REVIEW_REQUIRED)
                .reasons(List.of("Portfolio return deviates from benchmark by more than 5%"))
                .processedAt(Instant.now())
                .build();

        when(performanceCalculationService.calculate(any())).thenReturn(response);

        mockMvc.perform(post("/api/performance/daily-return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
    }

    @Test
    void calculateDailyReturn_blankPortfolioId_returns400() throws Exception {
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("")
                .valuationDate(LocalDate.of(2026, 6, 14))
                .beginMarketValue(new BigDecimal("1000000"))
                .endMarketValue(new BigDecimal("1035000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("1.0"))
                .currency("USD")
                .requestedBy("advisor01")
                .build();

        mockMvc.perform(post("/api/performance/daily-return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    void calculateDailyReturn_nullValuationDate_returns400() throws Exception {
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-1001")
                .valuationDate(null)
                .beginMarketValue(new BigDecimal("1000000"))
                .endMarketValue(new BigDecimal("1035000"))
                .netCashFlow(BigDecimal.ZERO)
                .benchmarkReturnPct(new BigDecimal("1.0"))
                .currency("USD")
                .requestedBy("advisor01")
                .build();

        mockMvc.perform(post("/api/performance/daily-return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calculateDailyReturn_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/performance/daily-return")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
