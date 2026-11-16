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
import org.elibanking.common.security.AdminRbac;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public List<UserResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        if (hasPermission(jwt, AdminRbac.ADMIN_USERS_READ)) return userAdminService.listUsers(true);
        requirePermission(jwt, AdminRbac.CUSTOMERS_READ);
        return userAdminService.listUsers(false);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a user")
    @ApiResponse(responseCode = "200", description = "User")
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public UserResponse get(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                            @PathVariable String id) {
        UserResponse user = userAdminService.getUser(id);
        requirePermission(jwt, user.role().isStaff() ? AdminRbac.ADMIN_USERS_READ : AdminRbac.CUSTOMERS_READ);
        return user;
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change a user's role and/or enable/disable them",
            description = "Disabling a user logs them out (refresh tokens revoked) and blocks future logins. "
                    + "An admin cannot demote or disable themselves.")
    @ApiResponse(responseCode = "200", description = "Updated user")
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Admin tried to demote/disable themselves",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public UserResponse update(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                               @PathVariable String id,
                               @RequestBody UpdateUserRequest request) {
        UserResponse existing = userAdminService.getUser(id);
        String managePermission = request.role() != null || existing.role().isStaff()
                ? AdminRbac.ADMIN_USERS_MANAGE : AdminRbac.CUSTOMERS_MANAGE;
        requirePermission(jwt, managePermission);
        return userAdminService.updateUser(jwt.getSubject(), id, request);
    }

    private static boolean hasPermission(Jwt jwt, String permission) {
        if (jwt == null) return false;
        java.util.List<String> authorities = java.util.Optional.ofNullable(jwt.getClaimAsStringList("authorities")).orElse(java.util.List.of());
        java.util.List<String> roles = java.util.Optional.ofNullable(jwt.getClaimAsStringList("roles")).orElse(java.util.List.of());
        boolean legacyAdmin = "ADMIN".equals(jwt.getClaimAsString("role")) && roles.isEmpty() && authorities.isEmpty();
        return legacyAdmin || authorities.contains(permission);
    }

    private static void requirePermission(Jwt jwt, String permission) {
        if (!hasPermission(jwt, permission)) {
            throw new AuthException(HttpStatus.FORBIDDEN, "Required permission: " + permission);
        }
    }
}
