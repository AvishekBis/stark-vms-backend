package com.starkgroup.vms.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintValidatorContext;

/**
 * BR-03: manufacture year between 1980 and next year, where "now" is the injected Clock in Asia/Dhaka.
 */
class ManufactureYearValidatorTest {

    /** 31 Dec 2026 20:00 UTC is already 1 Jan 2027 in Dhaka (UTC+6), so "next year" is 2028. */
    private static final Clock NEW_YEAR_IN_DHAKA =
            Clock.fixed(Instant.parse("2026-12-31T20:00:00Z"), ZoneId.of("Asia/Dhaka"));

    private ManufactureYearValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new ManufactureYearValidator(NEW_YEAR_IN_DHAKA);
        ManufactureYear annotation = mock(ManufactureYear.class);
        when(annotation.min()).thenReturn(1980);
        when(annotation.maxYearsAhead()).thenReturn(1);
        validator.initialize(annotation);
        context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    @Test
    void br03_boundsAreInclusiveAndUseBusinessTimeZone() {
        assertThat(validator.isValid(1980, context)).isTrue();
        assertThat(validator.isValid(2028, context)).isTrue();
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void br03_yearsOutsideTheRangeAreRejectedWithConcreteBounds() {
        assertThat(validator.isValid(1979, context)).isFalse();
        assertThat(validator.isValid(2029, context)).isFalse();

        verify(context, times(2)).disableDefaultConstraintViolation();
        verify(context, times(2))
                .buildConstraintViolationWithTemplate("Manufacture year must be between 1980 and 2028.");
    }
}
