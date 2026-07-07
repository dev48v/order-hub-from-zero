package dev.dev48v.orderhub.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

// Day 4 — the logic behind @CleanText.
// WHY: a ConstraintValidator is where a custom rule actually runs. It is given the
// field value and returns true/false. Two rules here:
//   1. reject text that is blank after trimming (whitespace-only sneaks past @Size),
//   2. reject text containing any word on a small blocklist (case-insensitive).
// null is treated as valid on purpose — presence is @NotBlank's job, so each
// constraint stays single-purpose and they compose cleanly.
public class CleanTextValidator implements ConstraintValidator<CleanText, String> {

    // A deliberately tiny, illustrative blocklist. A real system would source this
    // from configuration; the point is to show a working custom validator.
    private static final Set<String> BLOCKLIST = Set.of("badword", "spam", "scam");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // let @NotBlank own the "is it present?" question
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false; // whitespace-only is not clean text
        }

        String lower = trimmed.toLowerCase();
        for (String banned : BLOCKLIST) {
            if (lower.contains(banned)) {
                return false;
            }
        }
        return true;
    }
}
