package dev.dev48v.orderhub.web.dto;

import java.util.List;

// Day 35 — the /auth/login success response: the signed JWT plus a little context the client can use
// without decoding the token itself.
//   token     — the compact HS256-signed JWT to send back as "Authorization: Bearer <token>".
//   tokenType — always "Bearer"; names the scheme so the client knows how to present it.
//   roles     — the granted authorities, so a UI can render role-aware controls (a courtesy; the server
//               still enforces the roles on every request from the token's own claims).
public record TokenResponse(
        String token,
        String tokenType,
        List<String> roles
) {
}
