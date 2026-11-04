package org.elibanking.accountservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPathAndStartupTest {

    @Test
    void theLegacyPathCoversExactlyTheFourOldEndpointsWithTheirMethods() {
        assertTrue(LegacyHeaderFilter.isLegacy(request("GET", "/api/v1/accounts/1234567890/balance")));
        assertTrue(LegacyHeaderFilter.isLegacy(request("GET", "/api/v1/accounts/1234567890")));
        assertTrue(LegacyHeaderFilter.isLegacy(request("PUT", "/api/v1/accounts/1234567890/deduct")));
        assertTrue(LegacyHeaderFilter.isLegacy(request("PUT", "/api/v1/accounts/1234567890/credit")));
    }

    @Test
    void everythingElseIsNotOnTheLegacyPath() {
        assertFalse(LegacyHeaderFilter.isLegacy(request("GET", "/api/v1/accounts/1234567890/deduct")));
        assertFalse(LegacyHeaderFilter.isLegacy(request("PUT", "/api/v1/accounts/1234567890/balance")));
        assertFalse(LegacyHeaderFilter.isLegacy(request("PUT", "/api/v1/accounts/1234567890")));
        assertFalse(LegacyHeaderFilter.isLegacy(request("PUT", "/api/v1/accounts/1234567890/block")));
        assertFalse(LegacyHeaderFilter.isLegacy(request("GET", "/api/v1/accounts/me")));
        assertFalse(LegacyHeaderFilter.isLegacy(request("GET", "/api/v1/accounts/123")), "account numbers are 10 digits");
        assertFalse(LegacyHeaderFilter.isLegacy(request("POST", "/api/v1/accounts/1234567890")));
        assertFalse(LegacyHeaderFilter.isLegacy(request("GET", "/internal/v1/ledger/accounts/1234567890")));
    }

    @Test
    void theLegacyFlagCannotBeOnUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new LegacyHeadersStartupCheck(true, prod));
    }

    @Test
    void theLegacyFlagIsAllowedOutsideProd() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new LegacyHeadersStartupCheck(true, dev));
    }

    @Test
    void prodIsFineWhenTheFlagIsOff() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new LegacyHeadersStartupCheck(false, prod));
    }

    @Test
    void aShortTokenSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
                () -> TokenConfig.hs256("too-short", "JWT_SECRET", TokenConfig.serviceValidator("elibanking-internal", "account-service")));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
