package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.starkgroup.vms.auth.dto.CreateUserRequest;
import com.starkgroup.vms.auth.dto.ResetPasswordRequest;
import com.starkgroup.vms.auth.dto.UpdateUserRequest;
import com.starkgroup.vms.auth.dto.UserAccountResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BadRequestException;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.DuplicateResourceException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.util.PageRequests;

/**
 * ADR 0024 rules: e-mail normalization and uniqueness, guards (self-modification, last ADMIN), versions, reset.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T04:00:00.123456789Z");
    private static final AuthenticatedUser ACTOR = new AuthenticatedUser(1L, "admin@stark.local", Role.ADMIN);

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, Clock.fixed(NOW, ZoneId.of("Asia/Dhaka")));
    }

    @Test
    void createStoresTheEmailTrimmedAndLowerCaseWithAHashedPassword() {
        when(passwordEncoder.encode("Welcome@2026")).thenReturn("hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccountResponse created = userService.create(
                new CreateUserRequest(" Officer2@Stark.local ", " Second Officer ", Role.STAFF, "Welcome@2026"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("officer2@stark.local");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash");
        assertThat(created.fullName()).isEqualTo("Second Officer");
        assertThat(created.enabled()).isTrue();
        verify(userRepository).existsByEmail("officer2@stark.local");
    }

    @Test
    void createWithATakenEmailIsADuplicateOnTheEmailField() {
        when(userRepository.existsByEmail("officer2@stark.local")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest("OFFICER2@stark.local", "X", Role.STAFF, "Welcome@2026")))
                .isInstanceOfSatisfying(DuplicateResourceException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.DUPLICATE_EMAIL);
                    assertThat(ex.getField()).isEqualTo("email");
                    assertThat(ex.getMessage()).isEqualTo("E-mail officer2@stark.local is already registered.");
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void lostRaceOnTheEmailConstraintBecomesTheSame409() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uk_users_email\"")));

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest("officer2@stark.local", "X", Role.STAFF, "Welcome@2026")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateChangesNameRoleAndEnabled() {
        User staff = user(5L, Role.STAFF, true, 2L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));
        when(userRepository.saveAndFlush(staff)).thenReturn(staff);

        UserAccountResponse response = userService.update(ACTOR, 5L,
                new UpdateUserRequest(" New Name ", Role.ADMIN, false, 2L));

        assertThat(response.fullName()).isEqualTo("New Name");
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(response.enabled()).isFalse();
        verify(userRepository, never()).findEnabledByRoleForUpdate(any());
    }

    /** Review finding SEC-5: disabling records the token cut-off (from the Clock); enabling keeps it. */
    @Test
    void disablingRevokesTokensAndEnablingAgainKeepsThatCutOff() {
        User staff = user(5L, Role.STAFF, true, 2L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));
        when(userRepository.saveAndFlush(staff)).thenReturn(staff);

        userService.update(ACTOR, 5L, new UpdateUserRequest("Officer", Role.STAFF, true, 2L));
        assertThat(staff.getTokensRevokedAt()).as("no change of enabled, no cut-off").isNull();
        userService.update(ACTOR, 5L, new UpdateUserRequest("Officer", Role.STAFF, false, 2L));
        assertThat(staff.getTokensRevokedAt()).isEqualTo(Instant.parse("2026-11-07T04:00:00.123456Z"));
        userService.update(ACTOR, 5L, new UpdateUserRequest("Officer", Role.STAFF, true, 2L));

        assertThat(staff.getTokensRevokedAt()).isEqualTo(Instant.parse("2026-11-07T04:00:00.123456Z"));
    }

    @Test
    void updateWithAnOlderVersionIsStale() {
        User staff = user(5L, Role.STAFF, true, 3L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> userService.update(ACTOR, 5L, new UpdateUserRequest("X", Role.STAFF, true, 2L)))
                .isInstanceOf(StaleVersionException.class)
                .hasMessage("This user was changed by someone else since you opened it. Reload and try again.");
        assertThat(staff.getFullName()).isEqualTo("Officer");
    }

    @Test
    void adminCannotDisableThemself() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.ADMIN, true, 0L)));

        assertThatThrownBy(() -> userService.update(ACTOR, 1L, new UpdateUserRequest("Me", Role.ADMIN, false, 0L)))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.SELF_MODIFICATION);
                    assertThat(ex.getTitle()).isEqualTo("Not allowed on your own account");
                    assertThat(ex.getField()).isNull();
                    assertThat(ex.getMessage())
                            .isEqualTo("You cannot disable your own account or remove your own ADMIN role.");
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminCannotRemoveTheirOwnAdminRole() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.ADMIN, true, 0L)));

        assertThatThrownBy(() -> userService.update(ACTOR, 1L, new UpdateUserRequest("Me", Role.STAFF, true, 0L)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getType()).isEqualTo(ApiErrorType.SELF_MODIFICATION));
    }

    @Test
    void adminCanRenameThemself() {
        User me = user(1L, Role.ADMIN, true, 0L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(me));
        when(userRepository.saveAndFlush(me)).thenReturn(me);

        assertThat(userService.update(ACTOR, 1L, new UpdateUserRequest("Renamed", Role.ADMIN, true, 0L)).fullName())
                .isEqualTo("Renamed");
        verify(userRepository, never()).findEnabledByRoleForUpdate(any());
    }

    @Test
    void theLastEnabledAdminCanNeverBeDisabledOrDemoted() {
        User otherAdmin = user(2L, Role.ADMIN, true, 0L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherAdmin));
        // Under the row lock only this ADMIN is still enabled (e.g. the actor was disabled concurrently).
        when(userRepository.findEnabledByRoleForUpdate(Role.ADMIN)).thenReturn(List.of(otherAdmin));

        for (UpdateUserRequest change : List.of(new UpdateUserRequest("X", Role.ADMIN, false, 0L),
                new UpdateUserRequest("X", Role.STAFF, true, 0L))) {
            assertThatThrownBy(() -> userService.update(ACTOR, 2L, change))
                    .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                        assertThat(ex.getType()).isEqualTo(ApiErrorType.LAST_ADMIN);
                        assertThat(ex.getTitle()).isEqualTo("Last ADMIN");
                        assertThat(ex.getMessage()).isEqualTo("At least one enabled ADMIN must remain.");
                    });
        }
        assertThat(otherAdmin.isEnabledAdmin()).isTrue();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void anAdminCanBeDisabledWhileAnotherEnabledAdminRemains() {
        User otherAdmin = user(2L, Role.ADMIN, true, 0L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.findEnabledByRoleForUpdate(Role.ADMIN))
                .thenReturn(List.of(user(1L, Role.ADMIN, true, 0L), otherAdmin));
        when(userRepository.saveAndFlush(otherAdmin)).thenReturn(otherAdmin);

        assertThat(userService.update(ACTOR, 2L, new UpdateUserRequest("X", Role.ADMIN, false, 0L)).enabled())
                .isFalse();
    }

    @Test
    void changingAnAlreadyDisabledAdminNeedsNoAdminCount() {
        User disabledAdmin = user(2L, Role.ADMIN, false, 0L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(disabledAdmin));
        when(userRepository.saveAndFlush(disabledAdmin)).thenReturn(disabledAdmin);

        userService.update(ACTOR, 2L, new UpdateUserRequest("X", Role.STAFF, false, 0L));

        verify(userRepository, never()).findEnabledByRoleForUpdate(any());
    }

    @Test
    void resetStoresTheNewHashAndTheChangeTime() {
        User staff = user(5L, Role.STAFF, true, 0L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));
        when(passwordEncoder.encode("Temp@2026x")).thenReturn("new-hash");

        userService.resetPassword(5L, new ResetPasswordRequest("Temp@2026x"));

        assertThat(staff.getPasswordHash()).isEqualTo("new-hash");
        assertThat(staff.getPasswordChangedAt()).isEqualTo(Instant.parse("2026-11-07T04:00:00.123456Z"));
        verify(userRepository).saveAndFlush(staff);
    }

    @Test
    void missingUserIsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(99L)).isInstanceOf(NotFoundException.class)
                .hasMessage("User with id 99 was not found.");
        assertThatThrownBy(() -> userService.resetPassword(99L, new ResetPasswordRequest("Temp@2026x")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listDefaultsToEmailAscendingWithStableTieBreakerAndCapsThePageSize() {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(user(5L, Role.STAFF, true, 0L)),
                        invocation.getArgument(1), 1));

        userService.findAll(null, null, null, 0, 500, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PageRequests.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("email"), Sort.Order.asc("id"));
    }

    @Test
    void listWithAnUnknownSortFieldIsBadRequest() {
        assertThatThrownBy(() -> userService.findAll(null, null, null, 0, 20, "passwordHash,asc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown sort field 'passwordHash'");
    }

    private static User user(Long id, Role role, boolean enabled, Long version) {
        User user = new User("user" + id + "@stark.local", "hash", "Officer", role);
        user.updateAccount("Officer", role, enabled);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "version", version);
        return user;
    }
}
