package dev.dev48v.inventory.web;

import dev.dev48v.inventory.stock.InsufficientStockException;
import dev.dev48v.inventory.stock.UnknownSkuException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Day 17 — the inventory service's own error mapping, in the RFC-7807 ProblemDetail style
// order-service adopted on Day 5. Each service owns its own edge: it turns its domain exceptions
// into clean HTTP problem responses so callers (a human with curl today, order-service over Feign
// tomorrow) get a consistent, machine-readable error instead of a stack trace.
@RestControllerAdvice
public class InventoryExceptionHandler {

    // Unknown SKU → 404 Not Found.
    @ExceptionHandler(UnknownSkuException.class)
    public ProblemDetail onUnknownSku(UnknownSkuException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Unknown SKU");
        return pd;
    }

    // Not enough stock → 409 Conflict, with the numbers so the caller can react.
    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail onInsufficientStock(InsufficientStockException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Insufficient stock");
        pd.setProperty("sku", ex.sku());
        pd.setProperty("requested", ex.requested());
        pd.setProperty("available", ex.available());
        return pd;
    }
}
