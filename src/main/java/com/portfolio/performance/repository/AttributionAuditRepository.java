package com.portfolio.performance.repository;

import com.portfolio.performance.model.entity.AttributionAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link AttributionAudit} records.
 *
 * <p>{@link #findByRequestId(String)} is the primary idempotency look-up: before
 * running the attribution engine the service checks whether a record with the
 * incoming {@code requestId} already exists and, if so, returns the persisted
 * result directly without re-processing.
 */
@Repository
public interface AttributionAuditRepository extends JpaRepository<AttributionAudit, Long> {

    /**
     * Returns the audit record whose {@code requestId} matches exactly, or
     * {@link Optional#empty()} if no such record has been persisted yet.
     *
     * @param requestId the client-supplied idempotency key
     * @return an {@link Optional} containing the matching record, or empty
     */
    Optional<AttributionAudit> findByRequestId(String requestId);
}
