package com.portfolio.performance.controller;

import com.portfolio.performance.model.enums.PerformanceStatus;
import com.portfolio.performance.model.request.DailyReturnRequest;
import com.portfolio.performance.model.response.DailyReturnResponse;
import com.portfolio.performance.service.PerformanceCalculationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private final PerformanceCalculationService performanceCalculationService;

    public PerformanceController(PerformanceCalculationService performanceCalculationService) {
        this.performanceCalculationService = performanceCalculationService;
    }

    @PostMapping("/daily-return")
    public ResponseEntity<DailyReturnResponse> calculateDailyReturn(
            @RequestBody @Valid DailyReturnRequest request) {

        DailyReturnResponse response = performanceCalculationService.calculate(request);

        log.info("Processed daily-return request: portfolioId={}, valuationDate={}, status={}",
                request.getPortfolioId(), request.getValuationDate(), response.getStatus());

        if (PerformanceStatus.INVALID_INPUT == response.getStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }

        return ResponseEntity.ok(response);
    }
}
