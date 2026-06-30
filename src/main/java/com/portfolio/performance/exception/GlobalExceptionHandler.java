package com.portfolio.performance.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Centralized exception handler for all controllers in the portfolio-performance service.
 *
 * <p>Every handler returns an {@link ErrorResponse} with a machine-readable
 * {@code error} code, a human-readable {@code message}, and a UTC {@code timestamp}.
 * Validation failures additionally include a {@code fieldErrors} array that lists
 * each constraint violation individually.
 *
 * <p>Canonical error codes returned by this handler:
 * <table>
 *   <tr><th>Code</th><th>HTTP</th><th>Trigger</th></tr>
 *   <tr><td>{@code INVALID_INPUT}</td><td>400</td>
 *       <td>{@link InvalidAttributionInputException} — business-rule failure (e.g. bad weights)</td></tr>
 *   <tr><td>{@code VALIDATION_FAILED}</td><td>400</td>
 *       <td>{@link MethodArgumentNotValidException} — Jakarta Bean Validation constraint failure</td></tr>
 *   <tr><td>{@code MALFORMED_REQUEST}</td><td>400</td>
 *       <td>{@link HttpMessageNotReadableException} — missing or unparseable body</td></tr>
 *   <tr><td>{@code INTERNAL_ERROR}</td><td>500</td>
 *       <td>Any other unhandled {@link Exception}</td></tr>
 * </table>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------------------------
    // 1. Business-rule validation failure
    // ------------------------------------------------------------------

    /**
     * Handles {@link InvalidAttributionInputException} thrown when the attribution
     * request fails a business rule (e.g. total group weight outside 99–101 %).
     *
     * <p>Logs at WARN because this is caused by bad client input, not a server fault.
     */
    @ExceptionHandler(InvalidAttributionInputException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAttributionInput(
            InvalidAttributionInputException ex,
            HttpServletRequest request) {

        log.warn("Invalid attribution input [path={}]: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse body = new ErrorResponse(
                "INVALID_INPUT",
                ex.getMessage(),
                Instant.now(),
                null
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ------------------------------------------------------------------
    // 2. Jakarta Bean Validation failure
    // ------------------------------------------------------------------

    /**
     * Handles {@link MethodArgumentNotValidException} raised by {@code @Valid}
     * on controller method parameters. Each constraint violation is surfaced as
     * an element in the {@code fieldErrors} array.
     *
     * <p>Logs at WARN with a compact field-level summary.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        List<ErrorResponse.FieldErrorDetail> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ErrorResponse.FieldErrorDetail(
                        fe.getField(), fe.getDefaultMessage()))
                .toList();

        String summary = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("unknown field");

        log.warn("Validation failed [path={}] on field(s): {}", request.getRequestURI(), summary);

        ErrorResponse body = new ErrorResponse(
                "VALIDATION_FAILED",
                "Request validation failed",
                Instant.now(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ------------------------------------------------------------------
    // 3. Unreadable / missing request body
    // ------------------------------------------------------------------

    /**
     * Handles {@link HttpMessageNotReadableException} when the request body is
     * absent or cannot be parsed (e.g. malformed JSON).
     *
     * <p>Logs at WARN.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformed(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.warn("Malformed request body [path={}]: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse body = new ErrorResponse(
                "MALFORMED_REQUEST",
                "Request body is missing or cannot be parsed",
                Instant.now(),
                null
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ------------------------------------------------------------------
    // 4. Catch-all
    // ------------------------------------------------------------------

    /**
     * Catch-all for any exception not handled by the more specific methods above.
     *
     * <p>Logs at ERROR with the full stack trace because this represents an
     * unexpected server-side fault that requires investigation.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled exception [path={}]", request.getRequestURI(), ex);

        ErrorResponse body = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                Instant.now(),
                null
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
