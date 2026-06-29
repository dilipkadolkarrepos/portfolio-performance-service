package com.portfolio.performance.service;

import com.portfolio.performance.model.entity.DailyReturnAudit;
import com.portfolio.performance.model.enums.PerformanceStatus;
import com.portfolio.performance.model.request.DailyReturnRequest;
import com.portfolio.performance.model.response.DailyReturnResponse;
import com.portfolio.performance.repository.DailyReturnAuditRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class PerformanceCalculationServiceImpl implements PerformanceCalculationService {

    private final DailyReturnAuditRepository repository;
    private static final List<String> SUPPORTED_CURRENCIES = Arrays.asList("USD", "GBP", "EUR", "CAD", "AUD");

    public PerformanceCalculationServiceImpl(DailyReturnAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public DailyReturnResponse calculate(DailyReturnRequest request) {
        Instant now = Instant.now();
        List<String> reasons = new ArrayList<>();

        BigDecimal begin = request.getBeginMarketValue() == null ? BigDecimal.ZERO : request.getBeginMarketValue();
        BigDecimal end = request.getEndMarketValue() == null ? BigDecimal.ZERO : request.getEndMarketValue();
        BigDecimal netCash = request.getNetCashFlow() == null ? BigDecimal.ZERO : request.getNetCashFlow();
        BigDecimal benchmark = request.getBenchmarkReturnPct() == null ? BigDecimal.ZERO : request.getBenchmarkReturnPct();

        // 1. Negative market values
        if ((begin != null && begin.compareTo(BigDecimal.ZERO) < 0) || (end != null && end.compareTo(BigDecimal.ZERO) < 0)) {
            DailyReturnResponse resp = buildInvalid(request, now, "Begin or end market value cannot be negative");
            persistAudit(request, resp, reasons);
            return resp;
        }

        // 2. Missing/unsupported currency
        if (request.getCurrency() == null || request.getCurrency().isBlank() || !SUPPORTED_CURRENCIES.contains(request.getCurrency())) {
            DailyReturnResponse resp = buildInvalid(request, now, "Currency is missing or not supported");
            persistAudit(request, resp, reasons);
            return resp;
        }

        // 3. Zero begin value with non-zero end value
        if (begin != null && begin.compareTo(BigDecimal.ZERO) == 0 && end != null && end.compareTo(BigDecimal.ZERO) != 0) {
            DailyReturnResponse resp = buildInvalid(request, now, "Cannot calculate return: begin value is zero but end value is non-zero");
            persistAudit(request, resp, reasons);
            return resp;
        }

        BigDecimal portfolioReturnPct = null;
        BigDecimal excessReturnPct = null;

        // 4. Handle zero begin value / zero-to-zero case
        if (begin != null && begin.compareTo(BigDecimal.ZERO) == 0 && end != null && end.compareTo(BigDecimal.ZERO) == 0) {
            portfolioReturnPct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else if (begin != null && begin.compareTo(BigDecimal.ZERO) != 0) {
            // 5. Calculate portfolio return: ((end - begin - netCashFlow) / begin) * 100
            BigDecimal numerator = end.subtract(begin).subtract(netCash == null ? BigDecimal.ZERO : netCash);
            BigDecimal division = numerator.divide(begin, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            portfolioReturnPct = division.setScale(2, RoundingMode.HALF_UP);

            // 6. Calculate excess return
            if (benchmark != null) {
                excessReturnPct = portfolioReturnPct.subtract(benchmark).setScale(2, RoundingMode.HALF_UP);
            }
        }

        // 7. REVIEW_REQUIRED checks
        if (portfolioReturnPct != null && benchmark != null) {
            BigDecimal diff = portfolioReturnPct.subtract(benchmark).abs();
            if (diff.compareTo(new BigDecimal("5.0")) > 0) {
                reasons.add("Portfolio return deviates from benchmark by more than 5%");
            }
        }

        if (netCash != null && begin != null) {
            BigDecimal threshold = begin.multiply(new BigDecimal("0.20"));
            if (netCash.abs().compareTo(threshold) > 0) {
                reasons.add("Net cash flow exceeds 20% of begin market value");
            }
        }

        // 8. Status decision
        PerformanceStatus status = reasons.isEmpty() ? PerformanceStatus.VALID : PerformanceStatus.REVIEW_REQUIRED;

        // Build response
        DailyReturnResponse response = DailyReturnResponse.builder()
                .portfolioId(request.getPortfolioId())
                .valuationDate(request.getValuationDate())
                .portfolioReturnPct(portfolioReturnPct)
                .benchmarkReturnPct(benchmark)
                .excessReturnPct(excessReturnPct)
                .status(status)
                .reasons(reasons)
                .processedAt(now)
                .build();

        // 9. Persist audit
        persistAudit(request, response, reasons);

        return response;
    }

    private DailyReturnResponse buildInvalid(DailyReturnRequest request, Instant now, String reason) {
        List<String> reasons = new ArrayList<>();
        reasons.add(reason);
        return DailyReturnResponse.builder()
                .portfolioId(request.getPortfolioId())
                .valuationDate(request.getValuationDate())
                .portfolioReturnPct(null)
                .benchmarkReturnPct(request.getBenchmarkReturnPct())
                .excessReturnPct(null)
                .status(PerformanceStatus.INVALID_INPUT)
                .reasons(reasons)
                .processedAt(now)
                .build();
    }

    private void persistAudit(DailyReturnRequest request, DailyReturnResponse response, List<String> reasons) {
        DailyReturnAudit audit = DailyReturnAudit.builder()
                .portfolioId(request.getPortfolioId())
                .valuationDate(request.getValuationDate())
                .beginMarketValue(request.getBeginMarketValue())
                .endMarketValue(request.getEndMarketValue())
                .netCashFlow(request.getNetCashFlow())
                .benchmarkReturnPct(request.getBenchmarkReturnPct())
                .currency(request.getCurrency())
                .requestedBy(request.getRequestedBy())
                .portfolioReturnPct(response.getPortfolioReturnPct())
                .excessReturnPct(response.getExcessReturnPct())
                .status(response.getStatus() == null ? null : response.getStatus().name())
                .reasons(reasons == null || reasons.isEmpty() ? null : String.join(",", reasons))
                .processedAt(response.getProcessedAt())
                .build();

        repository.save(audit);
    }
}

