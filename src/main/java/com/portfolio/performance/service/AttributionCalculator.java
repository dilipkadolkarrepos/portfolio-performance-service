package com.portfolio.performance.service;

import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.model.response.GroupContributionResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless Spring component that performs the core portfolio attribution calculation.
 *
 * <p>All arithmetic uses {@link BigDecimal} with {@link RoundingMode#HALF_UP} at
 * six decimal places — no {@code double} arithmetic anywhere in the engine.
 *
 * <p>Return resolution (primary vs. fallback vs. unavailable) is delegated to
 * {@link PricingResilienceSimulator}, which simulates the latency and fallback
 * logic of a real external pricing service. Warnings and {@link PricingMode}
 * classification are driven by the outcome of that delegation:
 * <ul>
 *   <li>{@code PRIMARY} — {@code group.returnPct} was non-null; simulator returned it instantly.</li>
 *   <li>{@code FALLBACK_USED} — primary was null, simulator applied the fallback.</li>
 *   <li>{@code UNAVAILABLE} — both sources were null; contribution is forced to zero.</li>
 * </ul>
 *
 * <h2>Processing order</h2>
 * <ol>
 *   <li>Resolve each group's return via {@link PricingResilienceSimulator} and compute contribution.</li>
 *   <li>Derive {@link AttributionStatus} from the count of unavailable groups.</li>
 *   <li>Set the {@code degraded} flag.</li>
 *   <li>Sum contributions and assemble the response.</li>
 * </ol>
 */
@Component
public class AttributionCalculator {

    private static final BigDecimal   ONE_HUNDRED = new BigDecimal("100");
    private static final int          SCALE       = 6;
    private static final RoundingMode MODE        = RoundingMode.HALF_UP;

    private final PricingResilienceSimulator simulator;

    public AttributionCalculator(PricingResilienceSimulator simulator) {
        this.simulator = simulator;
    }

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
        // Step 1 — resolve each group's return and compute its contribution
        // ----------------------------------------------------------------
        for (GroupInput group : request.getGroups()) {
            String     name   = group.getGroupName();
            BigDecimal weight = group.getWeightPct();

            // Delegate pricing resolution and warning generation to the simulator.
            // The simulator appends to `warnings` directly for FALLBACK_USED and
            // UNAVAILABLE paths, so this loop must not add those warnings itself.
            BigDecimal resolvedReturn = simulator.fetchReturnWithFallback(
                    name,
                    group.getReturnPct(),
                    group.getFallbackReturnPct(),
                    warnings);

            PricingMode pricingMode;
            BigDecimal  contribution;

            if (group.getReturnPct() != null) {
                // Primary was available — simulator returned it with no delay
                pricingMode  = PricingMode.PRIMARY;
                contribution = weight.multiply(resolvedReturn).divide(ONE_HUNDRED, SCALE, MODE);

            } else if (resolvedReturn != null) {
                // Primary was absent but fallback succeeded
                pricingMode  = PricingMode.FALLBACK_USED;
                contribution = weight.multiply(resolvedReturn).divide(ONE_HUNDRED, SCALE, MODE);

            } else {
                // No pricing available — contribution is zero; track for status derivation
                pricingMode  = PricingMode.UNAVAILABLE;
                contribution = BigDecimal.ZERO.setScale(SCALE, MODE);
                missingGroups.add(name);
            }

            contributions.add(GroupContributionResult.builder()
                    .groupName(name)
                    .contributionPct(contribution)
                    .pricingMode(pricingMode)
                    .build());
        }

        // ----------------------------------------------------------------
        // Step 2 — derive status from the count of unavailable groups
        //
        // Note: "Return unavailable for …" warnings were already appended
        // by PricingResilienceSimulator during the loop above; do not add them
        // again here.
        // ----------------------------------------------------------------
        AttributionStatus status;

        if (missingGroups.isEmpty()) {
            status = AttributionStatus.VALID;
        } else if (missingGroups.size() == 1) {
            status = AttributionStatus.DEGRADED;
        } else {
            status = AttributionStatus.REVIEW_REQUIRED;
        }

        // ----------------------------------------------------------------
        // Step 3 — degraded flag
        // ----------------------------------------------------------------
        boolean degraded = (status == AttributionStatus.DEGRADED
                         || status == AttributionStatus.REVIEW_REQUIRED);

        // ----------------------------------------------------------------
        // Step 4 — total contribution
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
