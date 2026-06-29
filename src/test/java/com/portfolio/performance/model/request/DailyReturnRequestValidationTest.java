package com.portfolio.performance.model.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;

import static org.junit.jupiter.api.Assertions.*;

class DailyReturnRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void close() {
        validatorFactory.close();
    }

    @Test
    void validRequest_hasNoViolations() {
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-1")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000"))
                .endMarketValue(new BigDecimal("1100"))
                .netCashFlow(new BigDecimal("0"))
                .benchmarkReturnPct(new BigDecimal("2.5"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        Set<ConstraintViolation<DailyReturnRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Valid request should have zero constraint violations");
    }

    @Test
    void blankPortfolioId_hasOneViolation() {
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000"))
                .endMarketValue(new BigDecimal("1100"))
                .netCashFlow(new BigDecimal("0"))
                .benchmarkReturnPct(new BigDecimal("2.5"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        Set<ConstraintViolation<DailyReturnRequest>> violations = validator.validate(req);
        assertEquals(1, violations.size());
        ConstraintViolation<DailyReturnRequest> v = violations.iterator().next();
        assertEquals("portfolioId", v.getPropertyPath().toString());
    }

    @Test
    void nullCurrency_hasOneViolation() {
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-1")
                .valuationDate(LocalDate.now())
                .beginMarketValue(new BigDecimal("1000"))
                .endMarketValue(new BigDecimal("1100"))
                .netCashFlow(new BigDecimal("0"))
                .benchmarkReturnPct(new BigDecimal("2.5"))
                .currency(null)
                .requestedBy("tester")
                .build();

        Set<ConstraintViolation<DailyReturnRequest>> violations = validator.validate(req);
        assertEquals(1, violations.size());
        ConstraintViolation<DailyReturnRequest> v = violations.iterator().next();
        assertEquals("currency", v.getPropertyPath().toString());
    }

    @Test
    void nullValuationDate_hasOneViolation() {
        DailyReturnRequest req = DailyReturnRequest.builder()
                .portfolioId("PF-1")
                .valuationDate(null)
                .beginMarketValue(new BigDecimal("1000"))
                .endMarketValue(new BigDecimal("1100"))
                .netCashFlow(new BigDecimal("0"))
                .benchmarkReturnPct(new BigDecimal("2.5"))
                .currency("USD")
                .requestedBy("tester")
                .build();

        Set<ConstraintViolation<DailyReturnRequest>> violations = validator.validate(req);
        assertEquals(1, violations.size());
        ConstraintViolation<DailyReturnRequest> v = violations.iterator().next();
        assertEquals("valuationDate", v.getPropertyPath().toString());
    }
}

