package com.starkgroup.vms.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Bean Validation form of {@link PasswordPolicy}, so a policy violation is a 400 field error together with the
 * other field errors of the same request. {@code null} is valid here (use {@code @NotBlank}).
 */
@Documented
@Constraint(validatedBy = ValidPassword.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default PasswordPolicy.TOO_SHORT;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Reports the message of the rule that failed (too short or too long). */
    class Validator implements ConstraintValidator<ValidPassword, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return PasswordPolicy.violation(value)
                    .map(message -> {
                        context.disableDefaultConstraintViolation();
                        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
                        return false;
                    })
                    .orElse(true);
        }
    }
}
