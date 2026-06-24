package dev.dev48v.orderhub.service;

// A domain-level exception that says exactly one thing: the order id you asked for
// doesn't exist. WHY a dedicated type instead of leaking an empty Optional or a bare
// NoSuchElementException? The service can throw it without knowing anything about HTTP,
// and the web layer (@RestControllerAdvice) maps it to a 404 in one place. That keeps
// the service honest (it owns the rule "missing id is an error") and the layering clean.
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String id) {
        super("Order " + id + " not found");
    }
}
