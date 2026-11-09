package com.starkgroup.vms.auth;

import java.time.Instant;

import com.starkgroup.vms.common.persistence.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Application user. The password hash never leaves the auth package's services. The e-mail is immutable after
 * creation (it is the login name and the value stored in audit columns, ADR 0024).
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    public static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Tokens issued before this moment are rejected (ADR 0024); {@code null} = never changed. */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * Tokens issued before this moment are rejected (ADR 0024, review finding SEC-5): set when the account is
     * disabled, so re-enabling it does not bring old tokens back. {@code null} = never disabled.
     */
    @Column(name = "tokens_revoked_at")
    private Instant tokensRevokedAt;

    /** Optimistic locking (ADR 0023): set and incremented by Hibernate only. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected User() {
    }

    public User(String email, String passwordHash, String fullName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = true;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEnabledAdmin() {
        return enabled && role == Role.ADMIN;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public Instant getTokensRevokedAt() {
        return tokensRevokedAt;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Admin edit (PUT /users/{id}); the e-mail cannot be changed. Disabling an enabled account also revokes every
     * token issued before {@code now}.
     */
    public void updateAccount(String fullName, Role role, boolean enabled, Instant now) {
        if (this.enabled && !enabled) {
            this.tokensRevokedAt = now;
        }
        updateAccount(fullName, role, enabled);
    }

    /** Name, role and enabled flag only (tests, fixtures); see {@link #updateAccount(String, Role, boolean, Instant)}. */
    void updateAccount(String fullName, Role role, boolean enabled) {
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
    }

    /** Admin reset or own change: every token issued before {@code changedAt} stops working. */
    public void changePassword(String passwordHash, Instant changedAt) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = changedAt;
    }
}
