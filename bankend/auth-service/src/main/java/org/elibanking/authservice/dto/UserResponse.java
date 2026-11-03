package org.elibanking.authservice.dto;

import org.elibanking.authservice.entity.Role;
import org.elibanking.authservice.entity.User;

import java.time.LocalDateTime;

public record UserResponse(
        String id,
        String fullName,
        String email,
        Role role,
        boolean emailVerified,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getRole(),
                u.isEmailVerified(), u.isEnabled(), u.getCreatedAt());
    }
}
