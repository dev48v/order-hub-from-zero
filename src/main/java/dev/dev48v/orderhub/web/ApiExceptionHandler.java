package dev.dev48v.orderhub.web;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

// Day 4 — turn validation failures into a clean 400 instead of Spring's noisy default.
// WHY: when @Valid rejects a request body, Spring throws MethodArgumentNotValidException.
// Without handling it, the client gets a sprawling, leaky error page. Here we catch it
// centrally (@RestControllerAdvice = one place for every controller) and return a small,
// predictable JSON body that maps each invalid field to its message.
//
// Day 5 upgrades this to full RFC-7807 ProblemDetail responses and broader exception handling.
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        // LinkedHashMap keeps field order stable in the response, which is nicer to read.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // First message wins if a field has multiple violations.
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", fieldErrors);
        return body;
    }
}
