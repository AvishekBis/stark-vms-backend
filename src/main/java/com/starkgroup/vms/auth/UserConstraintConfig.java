package com.starkgroup.vms.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.UniqueConstraintRule;

/**
 * Safety net behind the service check: a violation of {@code uk_users_email} (two admins creating the same e-mail
 * at once) is reported as the same 409 duplicate-email problem as the check in {@link UserService}.
 */
@Configuration
public class UserConstraintConfig {

    @Bean
    public UniqueConstraintRule userEmailUniqueRule() {
        return new UniqueConstraintRule(User.EMAIL_UNIQUE_CONSTRAINT, ApiErrorType.DUPLICATE_EMAIL,
                UserService.DUPLICATE_EMAIL_TITLE, UserService.EMAIL_FIELD, "This e-mail is already registered.");
    }
}
