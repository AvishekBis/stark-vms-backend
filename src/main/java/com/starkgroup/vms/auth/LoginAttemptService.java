package com.starkgroup.vms.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory login throttling (ADR 0022, NFR-SEC-01): counts failed logins per normalized e-mail and per client
 * IP. {@code maxFailuresPerEmail} failures for one e-mail within the window lock that e-mail, and
 * {@code maxFailuresPerIp} failures from one IP block that IP, each for {@code lockout}. The window has the same
 * length as the lock and starts with the first counted failure of a key.
 * <p>
 * A plain class without servlet types: the caller passes the client IP as a String. All time comes from the
 * injected {@link Clock}. The state is per backend instance and is lost on restart (Render free runs one
 * instance; a shared store would be the follow-up for more).
 * <p>
 * <b>Reserve, then check the password.</b> {@link #tryAcquire} checks the locks and counts the attempt in one
 * atomic step, <i>before</i> the caller checks the password; the caller then settles it with {@link #failed}
 * (the count stays), {@link #succeeded} (the e-mail is cleared, the IP count is given back) or {@link #release}
 * (both given back, the password was never compared). So requests running at the same time can never test more
 * than {@code maxFailuresPerEmail} passwords for one e-mail in a window (review finding F1 / SEC-4).
 * <p>
 * Memory bound: at most {@value #MAX_TRACKED_KEYS} keys per map. Expired entries are removed when they are read;
 * when a map is full, expired entries are purged and then the unlocked entry with the fewest failures (oldest
 * first among equals) is evicted. A <b>locked entry is never evicted</b>: when every entry is locked, the new key
 * is not tracked in that map (a new e-mail then counts under its IP only). All changes run under one monitor, so
 * the bound always holds and no update is lost; {@link #remainingLock} reads lock-free.
 */
@Service
public class LoginAttemptService {

    static final int MAX_TRACKED_KEYS = 10_000;
    /** Longest key written to the log; longer (attacker-supplied) e-mails are cut. */
    static final int MAX_LOGGED_KEY_LENGTH = 100;
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final Map<String, Attempts> failuresByEmail = new ConcurrentHashMap<>();
    private final Map<String, Attempts> failuresByIp = new ConcurrentHashMap<>();
    private final Object monitor = new Object();
    private final LoginThrottleProperties properties;
    private final Clock clock;

    public LoginAttemptService(LoginThrottleProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Atomically: refuses while the e-mail is locked or the IP blocked (nothing is counted then), otherwise
     * counts this attempt as a failure for the e-mail and the IP. The attempt that reaches a threshold is still
     * allowed to check its password; every later one is refused until the lock ends.
     *
     * @param normalizedEmail the e-mail key, or {@code null} to count under the IP only
     * @param clientIp the IP key, or {@code null} to count under the e-mail only
     * @return a refusal ({@link LoginAttempt#refused()}) or a counted attempt to settle
     */
    public LoginAttempt tryAcquire(String normalizedEmail, String clientIp) {
        synchronized (monitor) {
            Instant now = clock.instant();
            Duration emailLock = remainingLock(failuresByEmail, normalizedEmail, now);
            Duration ipLock = remainingLock(failuresByIp, clientIp, now);
            Duration wait = emailLock.compareTo(ipLock) >= 0 ? emailLock : ipLock;
            if (!wait.isZero()) {
                return LoginAttempt.refused(wait);
            }
            Counted email = count(failuresByEmail, normalizedEmail, properties.maxFailuresPerEmail(), now);
            Counted ip = count(failuresByIp, clientIp, properties.maxFailuresPerIp(), now);
            return new LoginAttempt(Duration.ZERO, normalizedEmail, email.counted(), email.locked(), clientIp,
                    ip.counted(), ip.locked());
        }
    }

    /** The password was wrong (or the account unknown / disabled): the reserved count stays. */
    public void failed(LoginAttempt attempt) {
        if (attempt.emailLocked()) {
            logLock("E-mail", attempt.email());
        }
        if (attempt.ipLocked()) {
            logLock("IP", attempt.ip());
        }
    }

    /**
     * The password was right: the e-mail's failures are cleared and the IP's reserved count is given back. The
     * IP's earlier failures stay, so a valid account cannot be used to reset the per-IP limit between guesses.
     */
    public void succeeded(LoginAttempt attempt) {
        if (attempt.refused()) {
            return;
        }
        synchronized (monitor) {
            if (attempt.email() != null) {
                failuresByEmail.remove(attempt.email());
            }
            if (attempt.ipCounted()) {
                giveBack(failuresByIp, attempt.ip(), properties.maxFailuresPerIp());
            }
        }
    }

    /** The password was never compared (e.g. the database failed): both reserved counts are given back. */
    public void release(LoginAttempt attempt) {
        if (attempt.refused()) {
            return;
        }
        synchronized (monitor) {
            if (attempt.emailCounted()) {
                giveBack(failuresByEmail, attempt.email(), properties.maxFailuresPerEmail());
            }
            if (attempt.ipCounted()) {
                giveBack(failuresByIp, attempt.ip(), properties.maxFailuresPerIp());
            }
        }
    }

    /**
     * Read-only view (tests, diagnostics).
     *
     * @return the remaining lock time of the e-mail or of the IP (the longer one), or {@link Duration#ZERO} when
     * neither is locked
     */
    public Duration remainingLock(String normalizedEmail, String clientIp) {
        Instant now = clock.instant();
        Duration emailLock = remainingLock(failuresByEmail, normalizedEmail, now);
        Duration ipLock = remainingLock(failuresByIp, clientIp, now);
        return emailLock.compareTo(ipLock) >= 0 ? emailLock : ipLock;
    }

    /**
     * Control characters (CR, LF, ...) are replaced and the length is capped, so an attacker-chosen e-mail cannot
     * forge log lines (CWE-117, review finding SEC-6).
     */
    static String loggable(String key) {
        String safe = key.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", "?");
        return safe.length() <= MAX_LOGGED_KEY_LENGTH ? safe : safe.substring(0, MAX_LOGGED_KEY_LENGTH) + "...";
    }

    private void logLock(String kind, String key) {
        log.warn("{} {} locked for {} after too many failed logins", kind, loggable(key), properties.lockout());
    }

    private Duration remainingLock(Map<String, Attempts> failures, String key, Instant now) {
        if (key == null) {
            return Duration.ZERO;
        }
        Attempts attempts = failures.get(key);
        if (attempts == null) {
            return Duration.ZERO;
        }
        if (attempts.isExpired(now, properties.lockout())) {
            failures.remove(key, attempts);
            return Duration.ZERO;
        }
        return attempts.isLocked(now) ? Duration.between(now, attempts.lockedUntil()) : Duration.ZERO;
    }

    /** Runs with the monitor held; the key is known not to be locked. */
    private Counted count(Map<String, Attempts> failures, String key, int threshold, Instant now) {
        if (key == null) {
            return Counted.NOT_COUNTED;
        }
        if (!failures.containsKey(key) && failures.size() >= MAX_TRACKED_KEYS && !makeRoom(failures, now)) {
            return Counted.NOT_COUNTED;
        }
        Attempts after = failures.compute(key, (ignored, current) -> next(current, threshold, now));
        return new Counted(true, after.isLocked(now));
    }

    private Attempts next(Attempts current, int threshold, Instant now) {
        Duration lockout = properties.lockout();
        if (current == null || current.isExpired(now, lockout)) {
            return Attempts.first(now, threshold, lockout);
        }
        return current.plusOne(now, threshold, lockout);
    }

    /** Runs with the monitor held: takes one reserved failure back; an entry left without failures is removed. */
    private void giveBack(Map<String, Attempts> failures, String key, int threshold) {
        failures.computeIfPresent(key, (ignored, current) -> current.minusOne(threshold));
    }

    /**
     * Runs with the monitor held: purges expired entries, then evicts unlocked entries (fewest failures first,
     * then the oldest window) while the map is full. Never evicts a locked entry.
     *
     * @return {@code true} when there is room for one more key
     */
    private boolean makeRoom(Map<String, Attempts> failures, Instant now) {
        failures.values().removeIf(attempts -> attempts.isExpired(now, properties.lockout()));
        while (failures.size() >= MAX_TRACKED_KEYS) {
            Optional<Map.Entry<String, Attempts>> victim = failures.entrySet().stream()
                    .filter(entry -> !entry.getValue().isLocked(now))
                    .min(Comparator.comparingInt((Map.Entry<String, Attempts> entry) -> entry.getValue().failures())
                            .thenComparing(entry -> entry.getValue().windowStart()));
            if (victim.isEmpty()) {
                return false;
            }
            failures.remove(victim.get().getKey(), victim.get().getValue());
        }
        return true;
    }

    /**
     * One reserved login attempt, or a refusal.
     *
     * @param retryAfter remaining lock when refused, {@link Duration#ZERO} when the attempt was counted
     * @param emailCounted the attempt was counted for {@code email} (not when the key is null or untracked)
     * @param emailLocked this attempt reached the e-mail threshold
     */
    public record LoginAttempt(Duration retryAfter, String email, boolean emailCounted, boolean emailLocked,
                               String ip, boolean ipCounted, boolean ipLocked) {

        static LoginAttempt refused(Duration retryAfter) {
            return new LoginAttempt(retryAfter, null, false, false, null, false, false);
        }

        public boolean refused() {
            return !retryAfter.isZero();
        }
    }

    private record Counted(boolean counted, boolean locked) {
        static final Counted NOT_COUNTED = new Counted(false, false);
    }

    /**
     * Immutable state of one key, replaced atomically on every change.
     *
     * @param lockedUntil end of the lock, or {@code null} while the threshold has not been reached
     */
    record Attempts(int failures, Instant windowStart, Instant lockedUntil) {

        static Attempts first(Instant now, int threshold, Duration lockout) {
            return new Attempts(1, now, threshold <= 1 ? now.plus(lockout) : null);
        }

        Attempts plusOne(Instant now, int threshold, Duration lockout) {
            int failuresNow = failures + 1;
            return new Attempts(failuresNow, windowStart, failuresNow >= threshold ? now.plus(lockout) : null);
        }

        /** @return the state with one failure less, {@code null} (remove) when none is left */
        Attempts minusOne(int threshold) {
            int failuresNow = failures - 1;
            if (failuresNow <= 0) {
                return null;
            }
            return new Attempts(failuresNow, windowStart, failuresNow >= threshold ? lockedUntil : null);
        }

        boolean isLocked(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        /** The lock has ended, or the counting window has passed without reaching the threshold. */
        boolean isExpired(Instant now, Duration window) {
            if (lockedUntil != null) {
                return !now.isBefore(lockedUntil);
            }
            return !now.isBefore(windowStart.plus(window));
        }
    }
}
