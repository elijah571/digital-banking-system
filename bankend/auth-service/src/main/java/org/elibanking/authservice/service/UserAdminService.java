package org.elibanking.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.authservice.dto.UpdateUserRequest;
import org.elibanking.authservice.dto.UserResponse;
import org.elibanking.authservice.entity.Role;
import org.elibanking.authservice.entity.TokenType;
import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.exception.AuthException;
import org.elibanking.authservice.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final OpaqueTokenService tokenService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers(boolean includeStaff) {
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(user -> includeStaff || user.getRole() == Role.USER)
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(String id) {
        return UserResponse.from(find(id));
    }

    @Transactional
    public UserResponse updateUser(String adminId, String id, UpdateUserRequest request) {
        User user = find(id);
        boolean self = user.getId().equals(adminId);
        if (self && ((request.role() != null && request.role() != user.getRole())
                || Boolean.FALSE.equals(request.enabled()))) {
            throw new AuthException(HttpStatus.CONFLICT, "Admins cannot change their own role or disable themselves");
        }
        Role previousRole = user.getRole();
        boolean previousEnabled = user.isEnabled();
        if (request.role() != null) {
            boolean roleChanged = request.role() != user.getRole();
            user.setRole(request.role());
            if (roleChanged) tokenService.revokeAll(user, TokenType.REFRESH);
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
            if (!request.enabled()) {
                tokenService.revokeAll(user, TokenType.REFRESH);
            }
        }
        if (previousRole != user.getRole() || previousEnabled != user.isEnabled()) {
            jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details) "
                            + "VALUES (?, 'ADMIN_USER_ACCESS_UPDATED', 'USER', ?, "
                            + "JSON_OBJECT('previousRole', ?, 'role', ?, 'previousEnabled', ?, 'enabled', ?))",
                    adminId, id, previousRole.name(), user.getRole().name(), previousEnabled, user.isEnabled());
            log.info("Admin {} updated user access {}: role={} enabled={}", adminId, id, user.getRole(), user.isEnabled());
        }
        return UserResponse.from(user);
    }

    private User find(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "User not found: " + id));
    }
}
