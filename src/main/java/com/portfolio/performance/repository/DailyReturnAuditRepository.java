package com.portfolio.performance.repository;

import com.portfolio.performance.model.entity.DailyReturnAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyReturnAuditRepository extends JpaRepository<DailyReturnAudit, Long> {

    /**
     * Find all audit records by portfolioId
     */
    List<DailyReturnAudit> findByPortfolioId(String portfolioId);

    /**
     * Find all audit records by status
     */
    List<DailyReturnAudit> findByStatus(String status);

    /**
     * Find audit records by portfolioId and valuationDate
     */
    List<DailyReturnAudit> findByPortfolioIdAndValuationDate(String portfolioId, LocalDate valuationDate);
}

