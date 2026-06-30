package com.portfolio.performance.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Simulates real-world pricing-data retrieval that may be slow or intermittently
 * unavailable.
 *
 * <p>In production, this class would be replaced (or extended) with logic that
 * calls an external pricing service with a real timeout and circuit-breaker.
 * The {@link Thread#sleep} of 50 ms represents the time spent probing a secondary
 * source after a primary-pricing miss.
 *
 * <p>Warnings are appended directly to the caller-supplied {@code warnings} list
 * so that all messaging stays consistent with the rest of the attribution pipeline.
 *
 * <p>This class is stateless; the {@link Component} annotation makes it injectable
 * as a singleton Spring bean with no thread-safety concerns.
 */
@Slf4j
@Component
public class PricingResilienceSimulator {

    /**
     * Resolves the return percentage to use for a single asset group, applying
     * fallback logic when the primary return is unavailable.
     *
     * <h2>Resolution rules</h2>
     * <ol>
     *   <li>If {@code primaryReturn} is non-null, it is returned immediately —
     *       no delay, no warning.</li>
     *   <li>If {@code primaryReturn} is null, a 50 ms probe delay is simulated.
     *     <ul>
     *       <li>If {@code fallbackReturn} is non-null: logged at INFO, a warning
     *           is appended, and the fallback value is returned.</li>
     *       <li>If {@code fallbackReturn} is also null: logged at WARN, a warning
     *           is appended, and {@code null} is returned — the caller must treat
     *           the group as having no pricing.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param groupName      display name of the asset group (used in log messages and warnings)
     * @param primaryReturn  the group's primary return percentage, or {@code null} if unavailable
     * @param fallbackReturn the group's secondary return percentage, or {@code null} if also unavailable
     * @param warnings       mutable list to which human-readable warning messages are appended;
     *                       never {@code null}
     * @return the resolved return to use for contribution calculation, or {@code null} if neither
     *         source is available
     */
    public BigDecimal fetchReturnWithFallback(String groupName,
                                              BigDecimal primaryReturn,
                                              BigDecimal fallbackReturn,
                                              List<String> warnings) {
        // Fast path — primary pricing is present, no probe needed
        if (primaryReturn != null) {
            return primaryReturn;
        }

        // Primary absent — simulate a timeout probe before trying the fallback
        log.warn("Primary pricing unavailable for group [{}], attempting fallback", groupName);

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Fallback probe interrupted for group [{}]", groupName);
        }

        if (fallbackReturn != null) {
            log.info("Fallback pricing applied for group [{}]", groupName);
            warnings.add("Fallback pricing used for " + groupName);
            return fallbackReturn;
        }

        // Neither source is available
        log.warn("No pricing available for group [{}], group will be excluded", groupName);
        warnings.add("Return unavailable for " + groupName);
        return null;
    }
}
