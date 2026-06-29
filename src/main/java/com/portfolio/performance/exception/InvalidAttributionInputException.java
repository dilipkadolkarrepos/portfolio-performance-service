package com.portfolio.performance.exception;

/**
 * Thrown when an {@code AttributionRequest} fails business-rule validation
 * before the attribution engine begins processing.
 *
 * <p>This is an unchecked exception so callers are not forced to declare it,
 * but it is expected to be caught and mapped to HTTP 400 by
 * {@link GlobalExceptionHandler}.
 *
 * <p>Example message:
 * {@code "Total weight 95.00% is outside the allowed range of 99-101%"}
 */
public class InvalidAttributionInputException extends RuntimeException {

    public InvalidAttributionInputException(String message) {
        super(message);
    }
}
