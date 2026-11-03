package org.elibanking.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "JWT — send as `Authorization: Bearer <token>`") String accessToken,
        @Schema(description = "Opaque token for POST /api/v1/auth/refresh (single use, rotated on every refresh)") String refreshToken,
        String tokenType,
        @Schema(description = "Access token lifetime in seconds") long expiresIn,
        UserResponse user
) {
}
