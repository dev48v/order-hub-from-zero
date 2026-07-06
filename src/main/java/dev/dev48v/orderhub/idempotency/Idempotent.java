package dev.dev48v.orderhub.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Day 16 — mark an unsafe (state-changing) endpoint as safe to retry.
// WHY an annotation + aspect (rather than baking the logic into each controller): idempotency is a
// cross-cutting concern — the exact same "reserve the key, replay on repeat, 409 on an in-flight
// race" dance applies to EVERY POST that creates a resource. Tagging a method with @Idempotent and
// letting IdempotencyAspect weave the behaviour around it keeps the controllers thin and makes the
// protection reusable: put it on create-order today, on any future create/charge/submit tomorrow,
// with zero duplicated code.
//
// The client opts in per request by sending an Idempotency-Key header (name configurable via
// app.idempotency.header-name). A method with no key on the request is processed normally — the
// annotation only ENABLES idempotency; the client decides when to use it by supplying a key.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
