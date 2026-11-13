package org.elibanking.authservice.controller;

import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Minimal profile lookup for the allow-listed notification-service; passwords and tokens are never returned. */
@RestController
@RequestMapping("/internal/v1/notifications/users")
public class InternalNotificationRecipientController {

    public record Recipient(String email, String fullName) {}

    private final UserRepository users;

    public InternalNotificationRecipientController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/{userId}")
    public Recipient find(@PathVariable String userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return new Recipient(user.getEmail(), user.getFullName());
    }
}
