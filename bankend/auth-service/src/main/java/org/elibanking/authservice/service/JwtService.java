package org.elibanking.authservice.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.elibanking.authservice.config.AuthProperties;
import org.elibanking.authservice.entity.User;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Issues HS256-signed access tokens. The API gateway validates them with the same JWT_SECRET. */
@Service
public class JwtService {

    private final MACSigner signer;
    private final AuthProperties props;

    public JwtService(AuthProperties props) {
        this.props = props;
        byte[] secret = props.jwtSecret() == null ? new byte[0] : props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits); generate one with: openssl rand -base64 48");
        }
        try {
            this.signer = new MACSigner(secret);
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid JWT_SECRET", e);
        }
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.jwtIssuer())
                .subject(user.getId())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.accessTokenTtl())))
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("role", user.getRole().name())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign JWT", e);
        }
        return jwt.serialize();
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }
}
