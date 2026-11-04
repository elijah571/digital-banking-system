package org.elibanking.accountservice.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with the legacy path enabled under the prod profile (wallet-plan 8.19). Logs a warning when it is
 * enabled anywhere else, so it is never on by accident.
 */
@Component
public class LegacyHeadersStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(LegacyHeadersStartupCheck.class);

    public LegacyHeadersStartupCheck(@Value("${account.legacy-headers.enabled:false}") boolean enabled,
                                     Environment environment) {
        if (enabled && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("ACCOUNT_LEGACY_HEADERS_ENABLED must be false under the prod profile");
        }
        if (enabled) {
            log.warn("ACCOUNT_LEGACY_HEADERS_ENABLED=true: deduct, credit, balance and GET /{accountNumber} accept "
                    + "requests without a token. Development only.");
        }
    }
}
