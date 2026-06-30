package com.portfolio.performance.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Structured JSON error payload returned by {@link GlobalExceptionHandler} for
 * every error path in the application.
 *
 * <p>The {@code fieldErrors} component is omitted from the serialized JSON when
 * {@code null} (i.e. for non-validation errors) so clients receive a compact
 * body that only carries the fields relevant to the error type.
 *
 * <p>Canonical error codes:
 * <ul>
 *   <li>{@code INVALID_INPUT} — business-rule violation (e.g. weight out of range)</li>
 *   <li>{@code VALIDATION_FAILED} — Jakarta Bean Validation constraint failure</li>
 *   <li>{@code MALFORMED_REQUEST} — unreadable or unparseable request body</li>
 *   <li>{@code INTERNAL_ERROR} — unexpected server-side error</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(

        /** Machine-readable error code — one of the canonical values listed above. */
        String error,

        /** Human-readable summary of the problem. */
        String message,

        /** UTC instant at which the error was detected. */
        Instant timestamp,

        /**
         * Per-field validation details. Present only for {@code VALIDATION_FAILED};
         * omitted ({@code null}) for all other error types.
         */
        List<FieldErrorDetail> fieldErrors

) {

    /**
     * A single field-level constraint violation, included in the {@code fieldErrors}
     * list of a {@code VALIDATION_FAILED} response.
     *
     * @param field   the request field that failed validation (dot-notation for nested paths)
     * @param message the constraint violation message for that field
     */
    public record FieldErrorDetail(String field, String message) {}
}
