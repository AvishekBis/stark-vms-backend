package com.starkgroup.vms.common.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * BR-08 with a fixed Clock: deterministic whatever day the tests run (TC-08, TC-09).
 */
class ExpiryCalculatorTest {

    /** 18 Nov 2026, 10:00 in Dhaka. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Dhaka"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    private final ExpiryCalculator calculator = new ExpiryCalculator(CLOCK);

    @Test
    void tc08_documentExpiringIn10DaysIsExpiringSoon() {
        ExpiryInfo info = calculator.evaluate(TODAY.plusDays(10));

        assertThat(info.status()).isEqualTo(ExpiryStatus.EXPIRING_SOON);
        assertThat(info.daysUntilExpiry()).isEqualTo(10);
    }

    @Test
    void tc09_documentThatExpiredYesterdayIsExpired() {
        ExpiryInfo info = calculator.evaluate(TODAY.minusDays(1));

        assertThat(info.status()).isEqualTo(ExpiryStatus.EXPIRED);
        assertThat(info.daysUntilExpiry()).isEqualTo(-1);
    }

    @Test
    void br08_expiringTodayIsExpiringSoonWithZeroDaysLeft() {
        ExpiryInfo info = calculator.evaluate(TODAY);

        assertThat(info.status()).isEqualTo(ExpiryStatus.EXPIRING_SOON);
        assertThat(info.daysUntilExpiry()).isZero();
    }

    @Test
    void br08_windowIs30DaysInclusive() {
        assertThat(calculator.statusOf(TODAY.plusDays(30))).isEqualTo(ExpiryStatus.EXPIRING_SOON);
        assertThat(calculator.statusOf(TODAY.plusDays(31))).isEqualTo(ExpiryStatus.VALID);
        assertThat(calculator.daysUntil(TODAY.plusDays(31))).isEqualTo(31);
        assertThat(calculator.expiringSoonUntil()).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void br08_todayIsTheDateInDhakaEvenWhenTheClockIsInUtc() {
        // 17 Nov 2026 19:30 UTC is already 18 Nov 01:30 in Dhaka (UTC+6).
        Clock utcClock = Clock.fixed(Instant.parse("2026-11-17T19:30:00Z"), ZoneOffset.UTC);
        ExpiryCalculator utcCalculator = new ExpiryCalculator(utcClock);

        assertThat(utcCalculator.today()).isEqualTo(TODAY);
        assertThat(utcCalculator.statusOf(LocalDate.of(2026, 11, 17))).isEqualTo(ExpiryStatus.EXPIRED);
    }
}
