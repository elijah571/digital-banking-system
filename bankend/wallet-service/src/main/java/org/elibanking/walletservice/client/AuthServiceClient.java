package org.elibanking.walletservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** auth-service's internal password check (wallet-plan 3.5). The request carries the password: it is never logged. */
@FeignClient(name = "auth-service", url = "${auth-service.url}")
public interface AuthServiceClient {

    @PostMapping("/internal/v1/auth/verify-password")
    PasswordCheck verifyPassword(@RequestBody PasswordCheckRequest request);

    /** {@code toString} hides the password, so a logged request cannot leak it (threat model T4). */
    record PasswordCheckRequest(String userId, String password) {
        @Override
        public String toString() {
            return "PasswordCheckRequest[userId=" + userId + ", password=****]";
        }
    }

    record PasswordCheck(boolean valid) {
    }
}
