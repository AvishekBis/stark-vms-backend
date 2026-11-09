package com.starkgroup.vms.driver;

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
 * The phone number, after {@link DriverPhone#normalize normalization}, is a Bangladeshi mobile number.
 * Blank values are left to {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = ValidDriverPhone.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDriverPhone {

    String message() default "Phone must be a Bangladeshi mobile number, e.g. 01711000001.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidDriverPhone, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || DriverPhone.isValid(value);
        }
    }
}
