package org.elibanking.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param jwtSecret               HS256 key shared with the API gateway (min 32 bytes)
 * @param jwtIssuer               "iss" claim; the gateway only accepts tokens with this issuer
 * @param accessTokenTtl          lifetime of the JWT access token
 * @param refreshTokenTtl         lifetime of a refresh token
 * @param verificationTokenTtl    lifetime of the email-verification link
 * @param passwordResetTokenTtl   lifetime of the password-reset token
 * @param publicBaseUrl           public URL of the gateway, used to build the link in the verification email
 * @param passwordResetUrl        page that lets the user choose a new password (token is appended as ?token=)
 * @param adminEmail              admin account created on first start (optional)
 * @param adminPassword           its initial password
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String jwtSecret,
        String jwtIssuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration verificationTokenTtl,
        Duration passwordResetTokenTtl,
        String publicBaseUrl,
        String passwordResetUrl,
        String adminEmail,
        String adminPassword
) {
}
