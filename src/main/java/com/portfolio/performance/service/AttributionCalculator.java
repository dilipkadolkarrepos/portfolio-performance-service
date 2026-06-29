package com.portfolio.performance.service;

import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.model.response.GroupContributionResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless POJO that performs the core portfolio attribution calculation.
 *
 * <p>All arithmetic uses {@link BigDecimal} with {@link RoundingMode#HALF_UP} at
 * six decimal places — no {@code double} arithmetic anywhere in the engine.
 *
 * <p>Intentionally free of Spring annotations so every scenario can be driven
 * with a plain {@code new AttributionCalculator()} in unit tests.
 *
 * <h2>Processing order</h2>
 * <ol>
 *   <li>Classify each group by pricing availability and compute its contribution.</li>
 *   <li>Collect fallback warnings and track groups with no pricing at all.</li>
 *   <li>Derive {@link AttributionStatus} from the count of unavailable groups.</li>
 *   <li>Sum contributions and assemble the response.</li>
 * </ol>
 */
public class AttributionCalculator {

    private static final BigDecimal ONE_HUNDRED  = new BigDecimal("100");
    private static final int        SCALE        = 6;
    private static final RoundingMode MODE       = RoundingMode.HALF_UP;

    /**
     * Calculates the weighted return contribution for every group in the request
     * and returns a fully populated {@link AttributionResponse}.
     *
     * @param request a validated attribution request; must not be {@code null}
     * @return the attribution result including per-group contributions, status, and warnings
     */
    public AttributionResponse calculate(AttributionRequest request) {

        List<GroupContributionResult> contributions = new ArrayList<>();
        List<String>                 warnings      = new ArrayList<>();
        List<String>                 missingGroups = new ArrayList<>();

        // ----------------------------------------------------------------
        // Step 1 — classify each group and compute its contributionPct
        // ----------------------------------------------------------------
        for (GroupInput group : request.getGroups()) {
            String      name        = group.getGroupName();
            BigDecimal  weight      = group.getWeightPct();
            PricingMode pricingMode;
            BigDecimal  contribution;

            if (group.getReturnPct() != null) {
                // (a) Primary pricing available
                contribution = weight
                        .multiply(group.getReturnPct())
                        .divide(ONE_HUNDRED, SCALE, MODE);
                pricingMode = PricingMode.PRIMARY;

            } else if (group.getFallbackReturnPct() != null) {
                // (b) Primary absent — fall back to secondary return
                contribution = weight
                        .multiply(group.getFallbackReturnPct())
                        .divide(ONE_HUNDRED, SCALE, MODE);
                pricingMode = PricingMode.FALLBACK_USED;
                warnings.add("Fallback pricing used for " + name);

            } else {
                // (c) No pricing at all — contribution is zero, track as missing
                contribution = BigDecimal.ZERO.setScale(SCALE, MODE);
                pricingMode = PricingMode.UNAVAILABLE;
                missingGroups.add(name);
            }

            contributions.add(GroupContributionResult.builder()
                    .groupName(name)
                    .contributionPct(contribution)
                    .pricingMode(pricingMode)
                    .build());
        }

        // ----------------------------------------------------------------
        // Step 2 — derive status and unavailability warnings
        // ----------------------------------------------------------------
        AttributionStatus status;

        if (missingGroups.isEmpty()) {
            status = AttributionStatus.VALID;

        } else if (missingGroups.size() == 1) {
            status = AttributionStatus.DEGRADED;
            warnings.add("Return unavailable for " + missingGroups.get(0));

        } else {
            // 2 or more groups missing
            status = AttributionStatus.REVIEW_REQUIRED;
            for (String missingName : missingGroups) {
                warnings.add("Return unavailable for " + missingName);
            }
        }

        // ----------------------------------------------------------------
        // Step 3 — degraded flag
        // ----------------------------------------------------------------
        boolean degraded = (status == AttributionStatus.DEGRADED
                         || status == AttributionStatus.REVIEW_REQUIRED);

        // ----------------------------------------------------------------
        // Step 4 — total contribution (sum of all group contributions)
        // ----------------------------------------------------------------
        BigDecimal total = contributions.stream()
                .map(GroupContributionResult::getContributionPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, MODE);

        // ----------------------------------------------------------------
        // Step 5 — assemble and return the response
        // ----------------------------------------------------------------
        return AttributionResponse.builder()
                .requestId(request.getRequestId())
                .portfolioId(request.getPortfolioId())
                .valuationDate(request.getValuationDate())
                .totalContributionPct(total)
                .groupContributions(contributions)
                .status(status)
                .degraded(degraded)
                .warnings(warnings)
                .processedAt(Instant.now())
                .build();
    }
}
