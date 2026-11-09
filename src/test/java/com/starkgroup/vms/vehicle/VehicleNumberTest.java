package com.starkgroup.vms.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BR-01 / FR-V05: the normalization used for duplicate detection.
 */
class VehicleNumberTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" normalizes to DHK1234")
    @ValueSource(strings = {"DHK-1234", "dhk 1234", " DHK1234 ", "DHK1234", "dhk-12 34", "\tDHK\t-1234\n",
            "D H K - 1 2 3 4"})
    void frV05_variantsOfTheSameNumberNormalizeToTheSameKey(String raw) {
        assertThat(VehicleNumber.normalize(raw)).isEqualTo("DHK1234");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource({
            "DHAKA METRO-GA 11-2345, DHAKAMETROGA112345",
            "dhaka metro-ga 11-2345, DHAKAMETROGA112345",
            "CTG-9999, CTG9999"
    })
    void frV05_bangladeshiNumbersNormalize(String raw, String expected) {
        assertThat(VehicleNumber.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void frV05_differentNumbersStayDifferent() {
        assertThat(VehicleNumber.normalize("DHK-1234")).isNotEqualTo(VehicleNumber.normalize("DHK-1235"));
    }

    @Test
    void nullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> VehicleNumber.normalize(null));
    }
}
