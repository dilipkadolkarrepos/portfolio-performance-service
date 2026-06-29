package com.portfolio.performance.model.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.portfolio.performance.model.enums.PerformanceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReturnResponse {

    private String portfolioId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate valuationDate;

    private BigDecimal portfolioReturnPct;

    private BigDecimal benchmarkReturnPct;

    private BigDecimal excessReturnPct;

    private PerformanceStatus status;

    private List<String> reasons;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant processedAt;
}

