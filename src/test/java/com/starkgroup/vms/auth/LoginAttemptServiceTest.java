package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.starkgroup.vms.auth.LoginAttemptService.LoginAttempt;
import com.starkgroup.vms.support.MutableClock;

/**
 * ADR 0022 rules with a clock the test moves: thresholds, window, lockout end, success, memory bound, concurrency,
 * and the security review fixes (reserve before the password check, locked entries never evicted, safe logging).
 */
class LoginAttemptServiceTest {

    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final String EMAIL = "officer@stark.local";
    private static final String IP = "203.0.113.7";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T04:00:00Z"),
            ZoneId.of("Asia/Dhaka"));
    private final LoginAttemptService service = new LoginAttemptService(new LoginThrottleProperties(5, 20, LOCKOUT),
            clock);

    @Test
    void fifthFailureForAnEmailLocksItForTheLockoutPeriod() {
        failures(EMAIL, IP, 4);
        assertThat(service.remainingLock(EMAIL, IP)).isZero();

        failures(EMAIL, IP, 1);

        assertThat(service.remainingLock(EMAIL, IP)).isEqualTo(LOCKOUT);
        assertThat(service.remainingLock(EMAIL, "198.51.100.1")).as("the e-mail lock applies from every IP")
                .isEqualTo(LOCKOUT);
        assertThat(service.remainingLock("other@stark.local", "198.51.100.1")).isZero();
        assertThat(service.tryAcquire(EMAIL, "198.51.100.1").refused()).isTrue();
    }

    @Test
    void lockEndsAfterTheLockoutAndCountingStartsAgain() {
        failures(EMAIL, IP, 5);
        clock.advance(Duration.ofMinutes(14));
        assertThat(service.remainingLock(EMAIL, IP)).isEqualTo(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));

        assertThat(service.remainingLock(EMAIL, IP)).isZero();
        failures(EMAIL, "198.51.100.1", 4);
        assertThat(service.remainingLock(EMAIL, "198.51.100.1")).as("4 failures after the lock are not enough")
                .isZero();
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        failures(EMAIL, IP, 4);
        clock.advance(LOCKOUT);

        failures(EMAIL, IP, 1);

        assertThat(service.remainingLock(EMAIL, IP)).isZero();
        failures(EMAIL, IP, 3);
        assertThat(service.remainingLock(EMAIL, IP)).isZero();
        failures(EMAIL, IP, 1);
        assertThat(service.remainingLock(EMAIL, IP)).isEqualTo(LOCKOUT);
    }

    @Test
    void refusedAttemptsAreNotCountedSoTheLockIsNotExtended() {
        failures(EMAIL, IP, 5);
        clock.advance(Duration.ofMinutes(10));

        LoginAttempt refused = service.tryAcquire(EMAIL, IP);
        service.failed(refused);
        service.succeeded(refused);
        service.release(refused);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(service.remainingLock(EMAIL, IP)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void successClearsTheEmailButOnlyGivesBackItsOwnIpCount() {
        failures(EMAIL, IP, 4);
        for (int i = 0; i < 15; i++) {
            fail("guess" + i + "@stark.local", IP);
        }

        LoginAttempt success = service.tryAcquire(EMAIL, IP);
        assertThat(success.refused()).isFalse();
        service.succeeded(success);

        failures(EMAIL, "198.51.100.1", 4);
        assertThat(service.remainingLock(EMAIL, "198.51.100.1")).as("e-mail count restarted at 0").isZero();
        assertThat(service.remainingLock("new@stark.local", IP)).as("the IP has 19 failures, below 20").isZero();
        fail("new@stark.local", IP);
        assertThat(service.remainingLock("new@stark.local", IP)).as("20th failure from the IP")
                .isEqualTo(LOCKOUT);
    }

    @Test
    void theAttemptThatReachesTheLimitMayStillSucceedAndThenLiftsTheLock() {
        failures(EMAIL, IP, 4);

        LoginAttempt fifth = service.tryAcquire(EMAIL, IP);
        assertThat(fifth.refused()).as("the 5th attempt may check its password").isFalse();
        assertThat(fifth.emailLocked()).isTrue();
        assertThat(service.tryAcquire(EMAIL, "198.51.100.1").refused())
                .as("a parallel 6th attempt is refused while the 5th is still running").isTrue();

        service.succeeded(fifth);

        assertThat(service.remainingLock(EMAIL, IP)).isZero();
    }

    @Test
    void releaseGivesBackBothReservedCounts() {
        failures(EMAIL, IP, 4);
        for (int i = 0; i < 15; i++) {
            fail("guess" + i + "@stark.local", IP);
        }

        LoginAttempt interrupted = service.tryAcquire(EMAIL, IP);
        assertThat(service.remainingLock(EMAIL, IP)).as("reserved: 5th e-mail attempt").isEqualTo(LOCKOUT);
        service.release(interrupted);

        assertThat(service.remainingLock(EMAIL, IP)).as("back to 4 e-mail / 19 IP failures").isZero();
        fail("other@stark.local", IP);
        assertThat(service.remainingLock("other@stark.local", IP)).as("20th IP failure").isEqualTo(LOCKOUT);
    }

    @Test
    void twentyFailuresFromOneIpBlockEveryEmailFromThatIp() {
        for (int i = 0; i < 20; i++) {
            fail("user" + i + "@stark.local", IP);
        }

        assertThat(service.remainingLock("never-tried@stark.local", IP)).isEqualTo(LOCKOUT);
        assertThat(service.remainingLock("never-tried@stark.local", "198.51.100.1")).isZero();
    }

    @Test
    void whenBothAreLockedTheLongerRemainingTimeIsReported() {
        for (int i = 0; i < 19; i++) {
            fail("user" + i + "@stark.local", IP);
        }
        failures(EMAIL, "198.51.100.1", 5);
        clock.advance(Duration.ofMinutes(5));

        fail("user19@stark.local", IP);

        assertThat(service.remainingLock(EMAIL, IP)).isEqualTo(LOCKOUT);
        assertThat(service.remainingLock(EMAIL, "198.51.100.1")).isEqualTo(Duration.ofMinutes(10));
        assertThat(service.tryAcquire(EMAIL, IP).retryAfter()).isEqualTo(LOCKOUT);
    }

    @Test
    void unknownKeysAreNeverLockedAndANullKeyIsNotCounted() {
        assertThat(service.remainingLock(EMAIL, IP)).isZero();
        assertThat(service.remainingLock(EMAIL, null)).isZero();

        for (int i = 0; i < 5; i++) {
            fail(EMAIL, null);
        }

        assertThat(service.remainingLock(EMAIL, null)).as("e-mail only (own password change)").isEqualTo(LOCKOUT);
        assertThat(service.remainingLock(null, IP)).isZero();
    }

    @Test
    void atMostTenThousandEmailsAreTrackedAndTheOldestIsEvicted() {
        LoginAttemptService bigIpLimit = new LoginAttemptService(
                new LoginThrottleProperties(2, Integer.MAX_VALUE, LOCKOUT), clock);
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_KEYS; i++) {
            bigIpLimit.failed(bigIpLimit.tryAcquire("user" + i + "@stark.local", IP));
            clock.advance(Duration.ofMillis(1));
        }

        bigIpLimit.failed(bigIpLimit.tryAcquire("one-more@stark.local", IP));

        bigIpLimit.failed(bigIpLimit.tryAcquire("user0@stark.local", IP));
        assertThat(bigIpLimit.remainingLock("user0@stark.local", IP))
                .as("user0 was the oldest entry and was evicted, so this is its first failure again").isZero();
        bigIpLimit.failed(bigIpLimit.tryAcquire("user2@stark.local", IP));
        assertThat(bigIpLimit.remainingLock("user2@stark.local", IP)).as("user2 was still tracked")
                .isPositive();
    }

    @Test
    void whenFullExpiredEntriesArePurgedBeforeAnyLiveOneIsEvicted() {
        LoginAttemptService bigIpLimit = new LoginAttemptService(
                new LoginThrottleProperties(2, Integer.MAX_VALUE, LOCKOUT), clock);
        bigIpLimit.failed(bigIpLimit.tryAcquire("locked@stark.local", IP));
        clock.advance(Duration.ofMinutes(1));
        bigIpLimit.failed(bigIpLimit.tryAcquire("expired@stark.local", IP));
        clock.advance(Duration.ofMinutes(4));
        for (int i = 2; i < LoginAttemptService.MAX_TRACKED_KEYS; i++) {
            bigIpLimit.failed(bigIpLimit.tryAcquire("user" + i + "@stark.local", IP));
        }
        clock.advance(Duration.ofMinutes(9));
        bigIpLimit.failed(bigIpLimit.tryAcquire("locked@stark.local", IP));
        clock.advance(Duration.ofMinutes(3));
        // Now: "locked" has the oldest window but is locked for 12 more minutes; "expired" had one failure 16 minutes
        // ago, so its window is over. Adding a key to the full map must purge "expired", not evict "locked".

        bigIpLimit.failed(bigIpLimit.tryAcquire("one-more@stark.local", IP));

        assertThat(bigIpLimit.remainingLock("locked@stark.local", IP)).isEqualTo(Duration.ofMinutes(12));
    }

    /** Review finding SEC-2 / F2(b): filling the map with sprayed e-mails must not free a locked e-mail. */
    @Test
    void aLockedEmailIsNeverEvictedEvenWhenItIsTheOldestEntry() {
        LoginAttemptService service5 = new LoginAttemptService(
                new LoginThrottleProperties(5, Integer.MAX_VALUE, LOCKOUT), clock);
        for (int i = 0; i < 5; i++) {
            service5.failed(service5.tryAcquire("admin@stark.local", IP));
        }
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_KEYS + 500; i++) {
            service5.failed(service5.tryAcquire("spray" + i + "@stark.local", IP));
        }

        assertThat(service5.remainingLock("admin@stark.local", IP)).isEqualTo(LOCKOUT.minusSeconds(1));
        assertThat(service5.tryAcquire("admin@stark.local", "198.51.100.1").refused()).isTrue();
    }

    @Test
    void anEmailCloseToTheLimitOutlivesSprayedSingleFailures() {
        LoginAttemptService service5 = new LoginAttemptService(
                new LoginThrottleProperties(5, Integer.MAX_VALUE, LOCKOUT), clock);
        for (int i = 0; i < 4; i++) {
            service5.failed(service5.tryAcquire("admin@stark.local", IP));
        }
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_KEYS + 500; i++) {
            service5.failed(service5.tryAcquire("spray" + i + "@stark.local", IP));
        }

        service5.failed(service5.tryAcquire("admin@stark.local", IP));

        assertThat(service5.remainingLock("admin@stark.local", IP))
                .as("the 4 earlier failures were kept (fewest failures are evicted first)").isEqualTo(LOCKOUT);
    }

    @Test
    void whenEveryTrackedEmailIsLockedANewEmailCountsUnderItsIpOnly() {
        LoginAttemptService lockAtOnce = new LoginAttemptService(new LoginThrottleProperties(1, 3, LOCKOUT), clock);
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_KEYS; i++) {
            lockAtOnce.failed(lockAtOnce.tryAcquire("locked" + i + "@stark.local", "10.0." + i / 250 + "." + i % 250));
        }

        LoginAttempt untracked = lockAtOnce.tryAcquire("new@stark.local", IP);

        assertThat(untracked.refused()).isFalse();
        assertThat(untracked.emailCounted()).isFalse();
        assertThat(untracked.ipCounted()).isTrue();
        assertThat(lockAtOnce.remainingLock("locked0@stark.local", null)).as("no lock was freed")
                .isEqualTo(LOCKOUT);
        lockAtOnce.failed(untracked);
        lockAtOnce.failed(lockAtOnce.tryAcquire("new2@stark.local", IP));
        lockAtOnce.failed(lockAtOnce.tryAcquire("new3@stark.local", IP));
        assertThat(lockAtOnce.remainingLock("anyone@stark.local", IP)).as("the IP limit still applies")
                .isEqualTo(LOCKOUT);
    }

    /** Review finding F1 / SEC-4: the check and the count are one step, so parallel requests cannot overshoot. */
    @Test
    void parallelAttemptsNeverGetMoreTriesThanTheLimit() throws Exception {
        failures(EMAIL, IP, 4);
        List<LoginAttempt> attempts = runInParallel(50, () -> service.tryAcquire(EMAIL, IP));

        assertThat(attempts).filteredOn(attempt -> !attempt.refused()).as("exactly one more try in the window")
                .hasSize(1);
        attempts.forEach(service::failed);
        assertThat(service.remainingLock(EMAIL, IP)).isEqualTo(LOCKOUT);
    }

    @Test
    void concurrentFailuresAreNeverLost() throws Exception {
        LoginAttemptService service50 = new LoginAttemptService(new LoginThrottleProperties(50, 1000, LOCKOUT),
                clock);
        runInParallel(49, () -> {
            service50.failed(service50.tryAcquire(EMAIL, IP));
            return null;
        });
        assertThat(service50.remainingLock(EMAIL, IP)).as("49 failures, threshold 50").isZero();

        service50.failed(service50.tryAcquire(EMAIL, IP));

        assertThat(service50.remainingLock(EMAIL, IP)).isEqualTo(LOCKOUT);
    }

    /** Review finding SEC-6: no CR/LF (forged log lines) and no endless keys in the log. */
    @Test
    void loggedKeysHaveNoControlCharactersAndAreCapped() {
        assertThat(LoginAttemptService.loggable("x@y.z\n2026-11-08T10:00:00 INFO fake\r\u0085 "))
                .isEqualTo("x@y.z?2026-11-08T10:00:00 INFO fake???");
        assertThat(LoginAttemptService.loggable("a".repeat(300)))
                .hasSize(LoginAttemptService.MAX_LOGGED_KEY_LENGTH + 3).endsWith("...");
        assertThat(LoginAttemptService.loggable(EMAIL)).isEqualTo(EMAIL);
    }

    @Test
    void startupFailsOnNonPositiveSettings() {
        assertThatIllegalStateException().isThrownBy(() -> new LoginThrottleProperties(0, 20, LOCKOUT));
        assertThatIllegalStateException().isThrownBy(() -> new LoginThrottleProperties(5, -1, LOCKOUT));
        assertThatIllegalStateException().isThrownBy(() -> new LoginThrottleProperties(5, 20, Duration.ZERO));
    }

    private void fail(String email, String ip) {
        service.failed(service.tryAcquire(email, ip));
    }

    private void failures(String email, String ip, int count) {
        for (int i = 0; i < count; i++) {
            fail(email, ip);
        }
    }

    private static <T> List<T> runInParallel(int tasks, Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
