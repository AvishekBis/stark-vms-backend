package com.starkgroup.vms.driver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-DR01 phone normalization: spaces, hyphens and parentheses removed, +880 becomes 0, then ^0\d{10}$.
 */
class DriverPhoneTest {

    @ParameterizedTest
    @CsvSource({
            "01711000001, 01711000001",
            "'  01711-000001 ', 01711000001",
            "'01711 000 001', 01711000001",
            "'(017) 11-000001', 01711000001",
            "+8801711000001, 01711000001",
            "'+880 1711-000001', 01711000001"
    })
    void frDr01_variantsNormalizeToTheSameNumber(String raw, String expected) {
        assertThat(DriverPhone.normalize(raw)).isEqualTo(expected);
        assertThat(DriverPhone.isValid(raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1711000001", "0171100000", "017110000012", "01711-00000A", "+8811711000001", ""})
    void frDr01_numbersThatAreNotBangladeshiMobilesAreInvalid(String raw) {
        assertThat(DriverPhone.isValid(raw)).isFalse();
    }
}
