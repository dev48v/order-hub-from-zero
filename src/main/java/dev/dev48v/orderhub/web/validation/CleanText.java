package dev.dev48v.orderhub.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

// Day 4 — a custom Bean Validation constraint.
// WHY: the built-in annotations cover shape (presence, size, range), but real apps
// also have domain rules — here, free-text fields must not be blank-after-trim and
// must not contain a small blocklist of words. Wrapping that as a reusable annotation
// keeps the rule declarative on the DTO and out of the controller/service.
//
// @Constraint links this marker annotation to the class that does the actual checking.
@Documented
@Constraint(validatedBy = CleanTextValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface CleanText {

    String message() default "must not be blank or contain disallowed words";

    // Required boilerplate for every constraint: grouping + metadata carriers.
    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
