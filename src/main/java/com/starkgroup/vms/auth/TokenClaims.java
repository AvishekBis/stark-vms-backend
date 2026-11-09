package com.starkgroup.vms.auth;

import java.time.Instant;

/**
 * The verified content of an access token. {@code issuedAt} has millisecond precision (claim {@code iat_ms});
 * only a token without that claim (issued before v2.1.1) falls back to the whole-second JWT {@code iat}.
 */
public record TokenClaims(Long userId, String email, Instant issuedAt) {
}
