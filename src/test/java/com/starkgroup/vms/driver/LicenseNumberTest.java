package com.starkgroup.vms.driver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-DR01 licence normalization: trim, uppercase, remove whitespace and hyphens.
 */
class LicenseNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {"DK0123456L00001", "dk0123456l00001", " DK-0123456-L00001 ", "dk 0123456 l00001",
            "DK0123456\tL00001"})
    void frDr01_variantsNormalizeToTheSameKey(String raw) {
        assertThat(LicenseNumber.normalize(raw)).isEqualTo("DK0123456L00001");
    }
}
