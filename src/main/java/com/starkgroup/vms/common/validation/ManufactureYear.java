package com.starkgroup.vms.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * BR-03: a manufacture year between {@link #min()} and "current year + {@link #maxYearsAhead()}".
 * "Current year" comes from the injected Clock (Asia/Dhaka), not from the server's default zone.
 * {@code null} is valid - combine with {@code @NotNull} when the year is required.
 */
@Documented
@Constraint(validatedBy = ManufactureYearValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ManufactureYear {

    String message() default "Manufacture year must be between {min} and next year.";

    int min() default 1980;

    int maxYearsAhead() default 1;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
