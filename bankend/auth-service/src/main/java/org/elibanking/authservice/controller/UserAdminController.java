package org.elibanking.authservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.elibanking.authservice.dto.UpdateUserRequest;
import org.elibanking.authservice.dto.UserResponse;
import org.elibanking.authservice.exception.AuthException;
import org.elibanking.authservice.service.UserAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ADMIN only — enforced by the gateway and checked again here. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User management (ADMIN)", description = "List users, change roles, enable/disable accounts")
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping
    @Operation(summary = "List all users (newest first)")
    @ApiResponse(responseCode = "200", description = "Users")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    public List<UserResponse> list(@Parameter(hidden = true) @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        return userAdminService.listUsers();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a user")
    @ApiResponse(responseCode = "200", description = "User")
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public UserResponse get(@Parameter(hidden = true) @RequestHeader(value = "X-User-Role", required = false) String role,
                            @PathVariable String id) {
        requireAdmin(role);
        return userAdminService.getUser(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change a user's role and/or enable/disable them",
            description = "Disabling a user logs them out (refresh tokens revoked) and blocks future logins. "
                    + "An admin cannot demote or disable themselves.")
    @ApiResponse(responseCode = "200", description = "Updated user")
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Admin tried to demote/disable themselves",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public UserResponse update(@Parameter(hidden = true) @RequestHeader(value = "X-User-Role", required = false) String role,
                               @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String adminId,
                               @PathVariable String id,
                               @RequestBody UpdateUserRequest request) {
        requireAdmin(role);
        return userAdminService.updateUser(adminId, id, request);
    }

    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new AuthException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
