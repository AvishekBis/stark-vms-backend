package com.starkgroup.vms.common.validation;

import java.time.Clock;
import java.time.Year;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Created by Spring's validator factory, so the application {@link Clock} is injected.
 */
public class ManufactureYearValidator implements ConstraintValidator<ManufactureYear, Integer> {

    private final Clock clock;
    private int min;
    private int maxYearsAhead;

    public ManufactureYearValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void initialize(ManufactureYear annotation) {
        this.min = annotation.min();
        this.maxYearsAhead = annotation.maxYearsAhead();
    }

    @Override
    public boolean isValid(Integer year, ConstraintValidatorContext context) {
        if (year == null) {
            return true;
        }
        int max = Year.now(clock).getValue() + maxYearsAhead;
        if (year >= min && year <= max) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("Manufacture year must be between " + min + " and " + max + ".")
                .addConstraintViolation();
        return false;
    }
}
