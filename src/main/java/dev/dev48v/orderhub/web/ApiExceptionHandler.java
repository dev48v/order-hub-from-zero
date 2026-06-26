package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.service.OrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// Day 5 — one consistent error shape for the whole API, using RFC-7807 ProblemDetail.
// WHY ProblemDetail (built into Spring Boot 3): instead of every endpoint inventing its
// own error JSON, RFC-7807 gives a standard envelope — type/title/status/detail — that
// clients can rely on. We keep the @RestControllerAdvice idea from Day 4 (one place for
// every controller) and map each failure mode to the right HTTP status:
//   bad input -> 400, missing order -> 404, illegal transition -> 409, anything else -> 500.
// The catch-all deliberately returns a generic message so we never leak a stack trace.
@RestControllerAdvice
public class ApiExceptionHandler {

    // 400 — the request body failed @Valid. We surface every offending field so the
    // client can fix them all at once, attached as an RFC-7807 "extension member".
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // LinkedHashMap keeps field order stable in the response, which is nicer to read.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // First message wins if a field has multiple violations.
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", fieldErrors);
        return stamped(problem);
    }

    // 404 — the order id doesn't exist. The exception message already reads
    // "Order <id> not found", so it makes a perfect human-readable detail.
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Order not found");
        return stamped(problem);
    }

    // 400 — a service-layer guard rejected the input (e.g. quantity above the configured
    // app.orders.max-quantity limit). It's still bad input, so it gets the same 400 shape as
    // a failed @Valid — just surfaced from a business rule rather than a field annotation.
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return stamped(problem);
    }

    // 409 — a business-rule violation from the domain (e.g. confirming an order that
    // isn't PLACED). 409 Conflict is the right semantic: the request clashes with the
    // current state of the resource.
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid order state");
        return stamped(problem);
    }

    // 500 — the safety net for anything we didn't anticipate. We log nothing sensitive
    // back to the client and return a fixed, generic detail: never leak the exception
    // message or stack trace, which could expose internals.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setTitle("Internal error");
        return stamped(problem);
    }

    // Every problem carries a timestamp extension so clients/logs can correlate it.
    private ProblemDetail stamped(ProblemDetail problem) {
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
