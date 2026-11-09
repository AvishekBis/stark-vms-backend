package com.starkgroup.vms.document.dto;

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
 * BR-07: the expiry date is on or after the issue date. Reported as a field error on {@code expiryDate}; a
 * missing date is left to {@code @NotNull} (expiry) or allowed (issue).
 */
@Documented
@Constraint(validatedBy = ExpiryOnOrAfterIssue.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExpiryOnOrAfterIssue {

    String EXPIRY_DATE_FIELD = "expiryDate";

    String message() default "Expiry date must be on or after the issue date.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ExpiryOnOrAfterIssue, DocumentUploadRequest> {

        @Override
        public boolean isValid(DocumentUploadRequest request, ConstraintValidatorContext context) {
            if (request == null || request.issueDate() == null || request.expiryDate() == null
                    || !request.expiryDate().isBefore(request.issueDate())) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode(EXPIRY_DATE_FIELD)
                    .addConstraintViolation();
            return false;
        }
    }
}
