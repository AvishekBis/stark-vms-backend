package com.starkgroup.vms.auth;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.auth.dto.CreateUserRequest;
import com.starkgroup.vms.auth.dto.ResetPasswordRequest;
import com.starkgroup.vms.auth.dto.UpdateUserRequest;
import com.starkgroup.vms.auth.dto.UserAccountResponse;
import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.DuplicateResourceException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.util.PageRequests;

/**
 * ADMIN user management (ADR 0024, contract 7.3): list, create, edit (name, role, enabled), password reset.
 * Users are never deleted, only disabled, because audit columns refer to their e-mail. Guards: an ADMIN cannot
 * disable themself or remove their own ADMIN role, and the last enabled ADMIN can never be disabled or demoted.
 */
@Service
public class UserService {

    private static final String RESOURCE_NAME = "User";
    private static final String RECORD_NOUN = "user";
    static final String EMAIL_FIELD = "email";
    static final String DUPLICATE_EMAIL_TITLE = "Duplicate e-mail";
    static final String SELF_MODIFICATION_TITLE = "Not allowed on your own account";
    static final String SELF_MODIFICATION_DETAIL =
            "You cannot disable your own account or remove your own ADMIN role.";
    static final String LAST_ADMIN_TITLE = "Last ADMIN";
    static final String LAST_ADMIN_DETAIL = "At least one enabled ADMIN must remain.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserAccountResponse> findAll(String search, Role role, Boolean enabled, int page, int size,
                                                     String sort) {
        PageRequest pageRequest = PageRequests.of(page, size, sort, UserSortField.apiToProperty(),
                UserSortField.DEFAULT_SORT);
        PageRequest stablePageRequest = pageRequest.withSort(pageRequest.getSort().and(UserSortField.TIE_BREAKER));
        Specification<User> specification = Specification.allOf(
                UserSpecifications.search(search),
                UserSpecifications.hasRole(role),
                UserSpecifications.isEnabled(enabled));
        return PageResponse.from(userRepository.findAll(specification, stablePageRequest)
                .map(UserService::toResponse));
    }

    @Transactional(readOnly = true)
    public UserAccountResponse findById(Long id) {
        return toResponse(getUser(id));
    }

    /** The new user is enabled; createdBy is the creating ADMIN (JPA auditing). */
    @Transactional
    public UserAccountResponse create(CreateUserRequest request) {
        String email = AuthService.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.fullName().trim(),
                request.role());
        try {
            return toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            String message = ex.getMostSpecificCause().getMessage();
            if (message != null && message.contains(User.EMAIL_UNIQUE_CONSTRAINT)) {
                throw duplicateEmail(email);
            }
            throw ex;
        }
    }

    /**
     * Order of the checks: 404, stale version (ADR 0023), self-modification, last ADMIN. Before a change that
     * disables or demotes an enabled ADMIN, the rows of all enabled ADMINs are locked and counted in this
     * transaction, so two ADMINs disabling each other at the same moment are serialised and the second one gets
     * last-admin.
     */
    @Transactional
    public UserAccountResponse update(AuthenticatedUser actor, Long id, UpdateUserRequest request) {
        User user = getUser(id);
        if (!Objects.equals(user.getVersion(), request.version())) {
            throw new StaleVersionException(RECORD_NOUN);
        }
        boolean enabled = request.enabled();
        boolean removesAdminRights = !enabled || request.role() != Role.ADMIN;
        if (actor.id().equals(id) && removesAdminRights) {
            throw new BusinessRuleException(ApiErrorType.SELF_MODIFICATION, SELF_MODIFICATION_TITLE,
                    SELF_MODIFICATION_DETAIL);
        }
        if (user.isEnabledAdmin() && removesAdminRights
                && userRepository.findEnabledByRoleForUpdate(Role.ADMIN).size() <= 1) {
            throw new BusinessRuleException(ApiErrorType.LAST_ADMIN, LAST_ADMIN_TITLE, LAST_ADMIN_DETAIL);
        }
        user.updateAccount(request.fullName().trim(), request.role(), enabled,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        return toResponse(userRepository.saveAndFlush(user));
    }

    /**
     * ADMIN reset: every token of the user issued before now stops working (ADR 0024), and the version increases.
     * Allowed on the ADMIN's own account too; their own session then ends on the next request.
     */
    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        User user = getUser(id);
        user.changePassword(passwordEncoder.encode(request.newPassword()),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        userRepository.saveAndFlush(user);
    }

    private User getUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_NAME, id));
    }

    private static DuplicateResourceException duplicateEmail(String email) {
        return new DuplicateResourceException(ApiErrorType.DUPLICATE_EMAIL, DUPLICATE_EMAIL_TITLE, EMAIL_FIELD,
                "E-mail " + email + " is already registered.");
    }

    private static UserAccountResponse toResponse(User user) {
        return new UserAccountResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.isEnabled(), user.getCreatedAt(), user.getUpdatedAt(), user.getCreatedBy(), user.getVersion());
    }
}
