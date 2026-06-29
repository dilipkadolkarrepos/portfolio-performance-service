package com.portfolio.performance.model.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReturnRequest {

    @NotBlank
    private String portfolioId;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate valuationDate;

    @NotNull
    private BigDecimal beginMarketValue;

    @NotNull
    private BigDecimal endMarketValue;

    @NotNull
    private BigDecimal netCashFlow;

    @NotNull
    private BigDecimal benchmarkReturnPct;

    @NotBlank
    private String currency;

    @NotBlank
    private String requestedBy;
}

