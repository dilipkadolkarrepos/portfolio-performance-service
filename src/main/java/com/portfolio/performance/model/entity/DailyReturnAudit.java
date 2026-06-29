package com.portfolio.performance.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_return_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReturnAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private String portfolioId;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(name = "begin_market_value")
    private BigDecimal beginMarketValue;

    @Column(name = "end_market_value")
    private BigDecimal endMarketValue;

    @Column(name = "net_cash_flow")
    private BigDecimal netCashFlow;

    @Column(name = "benchmark_return_pct")
    private BigDecimal benchmarkReturnPct;

    @Column(name = "currency")
    private String currency;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "portfolio_return_pct")
    private BigDecimal portfolioReturnPct;

    @Column(name = "excess_return_pct")
    private BigDecimal excessReturnPct;

    @Column(name = "status")
    private String status;

    @Column(name = "reasons", length = 2000)
    private String reasons;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}

