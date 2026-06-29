package com.portfolio.performance.service;

import com.portfolio.performance.model.request.DailyReturnRequest;
import com.portfolio.performance.model.response.DailyReturnResponse;

public interface PerformanceCalculationService {

    DailyReturnResponse calculate(DailyReturnRequest request);
}

