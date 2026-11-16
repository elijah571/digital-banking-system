package org.elibanking.authservice.dto;

import org.elibanking.authservice.entity.Role;
import org.elibanking.authservice.entity.User;
import org.elibanking.common.security.AdminRbac;

import java.time.LocalDateTime;
import java.util.List;

public record UserResponse(
        String id,
        String fullName,
        String email,
        Role role,
        List<String> roles,
        List<String> authorities,
        boolean emailVerified,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static UserResponse from(User u) {
        String staffRole = u.getRole().staffRoleClaim();
        return new UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getRole(),
                staffRole == null ? List.of() : List.of(staffRole),
                AdminRbac.permissionsFor(u.getRole().name()).stream().sorted().toList(),
                u.isEmailVerified(), u.isEnabled(), u.getCreatedAt());
    }
}
