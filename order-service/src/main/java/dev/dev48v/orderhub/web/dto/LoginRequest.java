package dev.dev48v.orderhub.web.dto;

import jakarta.validation.constraints.NotBlank;

// Day 35 — the /auth/login request body: the credentials a client exchanges for a JWT.
// @NotBlank fails an empty username/password fast with a 400 (the same Bean-Validation contract the
// order endpoints use), before any authentication work runs.
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
