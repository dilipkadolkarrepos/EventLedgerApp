package com.eventledger.exception;

import com.eventledger.dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Fired by Spring when @Valid fails on a @RequestBody.
     * Each FieldError carries the message from the constraint annotation
     * (e.g. @NotBlank, @Pattern). Messages are sorted so the response
     * is deterministic and easy to assert in tests.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        log.debug("Validation failed: {}", details);

        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                "One or more fields failed validation",
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Fired by Spring AOP when {@code @Min}/{@code @Max} constraints on controller
     * method parameters fail (e.g. pagination {@code page}/{@code size}).
     * Distinct from {@link MethodArgumentNotValidException}, which covers
     * {@code @Valid} on request bodies. Both return the same 400 shape so callers
     * need only one error-handling path.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations()
                .stream()
                .map(ConstraintViolation::getMessage)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        log.debug("Query parameter validation failed: {}", details);

        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                "One or more query parameters failed validation",
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Business-rule violation: timestamp unparseable, etc.
     * Always a client error — logged at DEBUG only.
     */
    @ExceptionHandler(InvalidEventException.class)
    public ResponseEntity<ErrorResponse> handleInvalidEvent(InvalidEventException ex) {
        log.debug("Invalid event: {}", ex.getMessage());

        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid event",
                ex.getMessage()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Resource not found: unknown eventId or accountId with no recorded events.
     */
    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(EventNotFoundException ex) {
        log.debug("Resource not found: {}", ex.getMessage());

        ErrorResponse body = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Not found",
                ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Fired when the request body is missing entirely or Jackson cannot parse it
     * (e.g. malformed JSON, wrong Content-Type). The raw parse detail from Jackson
     * is intentionally withheld from the response to avoid leaking internal structure.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());

        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Malformed request",
                "Request body is missing or cannot be parsed"
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Safety net for any exception not matched by a more specific handler.
     * Logged at ERROR with the full stack trace so it is visible in application
     * logs without exposing internals to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);

        ErrorResponse body = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal server error",
                "An unexpected error occurred"
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
