package org.elibanking.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.elibanking.authservice.entity.Role;

/** Admin update; null fields are left unchanged. */
public record UpdateUserRequest(
        @Schema(description = "USER or ADMIN", example = "ADMIN") Role role,
        @Schema(description = "false = user can no longer log in", example = "true") Boolean enabled
) {
}
