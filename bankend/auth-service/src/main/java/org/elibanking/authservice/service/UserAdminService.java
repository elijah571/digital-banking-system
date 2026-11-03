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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final OpaqueTokenService tokenService;

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
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
        if (self && (request.role() == Role.USER || Boolean.FALSE.equals(request.enabled()))) {
            throw new AuthException(HttpStatus.CONFLICT, "Admins cannot demote or disable themselves");
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
            if (!request.enabled()) {
                tokenService.revokeAll(user, TokenType.REFRESH);
            }
        }
        log.info("Admin {} updated user {}: role={} enabled={}", adminId, id, user.getRole(), user.isEnabled());
        return UserResponse.from(user);
    }

    private User find(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "User not found: " + id));
    }
}
