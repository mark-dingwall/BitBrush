package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.exception.RecoveryCapacityException;
import au.com.dingwall.mark.bitbrush.exception.RecoveryThrottledException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Bounded, process-local rolling windows; each map is coordinated independently. */
@Service
public class RecoveryAttemptService {

    public record AccountAttemptReservation(String username, long sequence) {}

    private record AccountAttempt(long sequence, Instant timestamp) {}

    private final PinProperties properties;
    private final Clock clock;
    private final Object accountLock = new Object();
    private final Object ipLock = new Object();
    // Immutable lists provide deque semantics (oldest first, newest last). Never mutate published values.
    private final Map<String, List<AccountAttempt>> accounts = new HashMap<>();
    private final Map<InetAddress, List<Instant>> ips = new HashMap<>();
    private long nextSequence;

    public RecoveryAttemptService(PinProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void recordIpAttempt(InetAddress ip) {
        synchronized (ipLock) {
            Instant now = clock.instant();
            List<Instant> attempts = liveEntries(ips, ip, now, Function.identity());
            checkLimit(attempts, properties.ipLimit(), now, Function.identity());
            if (attempts.isEmpty()) {
                checkCapacity(ips, properties.ipCapacity(), now, Function.identity());
            }
            ips.put(ip, append(attempts, now));
        }
    }

    public AccountAttemptReservation recordAccountAttempt(String username) {
        synchronized (accountLock) {
            Instant now = clock.instant();
            List<AccountAttempt> attempts = liveEntries(accounts, username, now, AccountAttempt::timestamp);
            checkLimit(attempts, properties.accountLimit(), now, AccountAttempt::timestamp);
            if (attempts.isEmpty()) {
                checkCapacity(accounts, properties.accountCapacity(), now, AccountAttempt::timestamp);
            }
            long sequence = ++nextSequence;
            accounts.put(username, append(attempts, new AccountAttempt(sequence, now)));
            return new AccountAttemptReservation(username, sequence);
        }
    }

    public void cancelAccountAttempt(AccountAttemptReservation reservation) {
        synchronized (accountLock) {
            Instant now = clock.instant();
            List<AccountAttempt> attempts = liveEntries(accounts, reservation.username(), now, AccountAttempt::timestamp);
            List<AccountAttempt> retained = attempts.stream()
                .filter(attempt -> attempt.sequence() != reservation.sequence())
                .toList();
            if (retained.isEmpty()) {
                accounts.remove(reservation.username());
            } else {
                accounts.put(reservation.username(), retained);
            }
        }
    }

    public void clearAccount(String username) {
        synchronized (accountLock) {
            Instant now = clock.instant();
            removeExpired(accounts, now, AccountAttempt::timestamp);
            accounts.remove(username);
        }
    }

    @Scheduled(fixedDelayString = "${pin.recovery-window}")
    void cleanupExpired() {
        synchronized (accountLock) {
            Instant now = clock.instant();
            removeExpired(accounts, now, AccountAttempt::timestamp);
        }
        synchronized (ipLock) {
            Instant now = clock.instant();
            removeExpired(ips, now, Function.identity());
        }
    }

    // All helpers run under the owning map's lock and use its operation's single clock sample.
    private <K, E> List<E> liveEntries(Map<K, List<E>> windows, K key, Instant now, Function<E, Instant> timestamp) {
        List<E> retained = prune(windows.getOrDefault(key, List.of()), now, timestamp);
        if (retained.isEmpty()) {
            windows.remove(key);
        } else {
            windows.put(key, retained);
        }
        return retained;
    }

    private <E> List<E> prune(List<E> attempts, Instant now, Function<E, Instant> timestamp) {
        Instant cutoff = now.minus(properties.recoveryWindow());
        return attempts.stream().filter(attempt -> timestamp.apply(attempt).isAfter(cutoff)).toList();
    }

    private <E> void checkLimit(List<E> attempts, int limit, Instant now, Function<E, Instant> timestamp) {
        if (attempts.size() >= limit) {
            throw new RecoveryThrottledException(retryAfter(timestamp.apply(attempts.getFirst()), now));
        }
    }

    private <K, E> void checkCapacity(Map<K, List<E>> windows, int capacity, Instant now, Function<E, Instant> timestamp) {
        if (windows.size() < capacity) {
            return;
        }
        removeExpired(windows, now, timestamp);
        if (windows.size() >= capacity) {
            // A slot is reusable only when every attempt for that key has expired.
            Instant earliestFinalAttempt = windows.values().stream()
                .map(attempts -> timestamp.apply(attempts.getLast()))
                .min(Instant::compareTo)
                .orElseThrow();
            throw new RecoveryCapacityException(retryAfter(earliestFinalAttempt, now));
        }
    }

    private <K, E> void removeExpired(Map<K, List<E>> windows, Instant now, Function<E, Instant> timestamp) {
        var iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            List<E> retained = prune(entry.getValue(), now, timestamp);
            if (retained.isEmpty()) {
                iterator.remove();
            } else {
                entry.setValue(retained);
            }
        }
    }

    private long retryAfter(Instant timestamp, Instant now) {
        Duration remaining = Duration.between(now, timestamp.plus(properties.recoveryWindow()));
        return remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
    }

    private static <E> List<E> append(List<E> attempts, E attempt) {
        List<E> next = new ArrayList<>(attempts);
        next.add(attempt);
        return List.copyOf(next);
    }
}
