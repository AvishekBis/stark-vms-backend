package com.starkgroup.vms.document.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.starkgroup.vms.document.DocumentType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * BR-07 at the DTO level: expiry before issue is a field error on expiryDate.
 */
class ExpiryOnOrAfterIssueTest {

    private static final LocalDate ISSUE = LocalDate.of(2026, 1, 15);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void br07_expiryBeforeIssueIsAFieldErrorOnExpiryDate() {
        List<ConstraintViolation<DocumentUploadRequest>> violations =
                List.copyOf(validator.validate(request(ISSUE, ISSUE.minusDays(1))));

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().getPropertyPath()).hasToString("expiryDate");
        assertThat(violations.getFirst().getMessage()).isEqualTo("Expiry date must be on or after the issue date.");
    }

    @Test
    void br07_sameDayOrLaterAndMissingIssueDateAreValid() {
        assertThat(validator.validate(request(ISSUE, ISSUE))).isEmpty();
        assertThat(validator.validate(request(ISSUE, ISSUE.plusYears(1)))).isEmpty();
        assertThat(validator.validate(request(null, ISSUE))).isEmpty();
    }

    private static DocumentUploadRequest request(LocalDate issueDate, LocalDate expiryDate) {
        return new DocumentUploadRequest(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1}),
                DocumentType.INSURANCE, "INS-1", issueDate, expiryDate);
    }
}
