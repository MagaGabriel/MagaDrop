import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class LoginRateLimiter {
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maximumFailures;
    private final long blockMillis;

    LoginRateLimiter() {
        this(5, Duration.ofMinutes(1));
    }

    LoginRateLimiter(int maximumFailures, Duration blockDuration) {
        if (maximumFailures < 1 || blockDuration.isNegative() || blockDuration.isZero())
            throw new IllegalArgumentException("Limites de login inválidos.");
        this.maximumFailures = maximumFailures;
        this.blockMillis = blockDuration.toMillis();
    }

    boolean isAllowed(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) return true;
        long now = System.currentTimeMillis();
        if (attempt.blockedUntilEpochMillis > now) return false;
        if (attempt.blockedUntilEpochMillis != 0) attempts.remove(key, attempt);
        return true;
    }

    void recordFailure(String key) {
        long now = System.currentTimeMillis();
        attempts.compute(key, (ignored, current) -> {
            Attempt next = current == null || current.blockedUntilEpochMillis != 0 ? new Attempt() : current;
            next.failures++;
            if (next.failures >= maximumFailures) next.blockedUntilEpochMillis = now + blockMillis;
            return next;
        });
    }

    void recordSuccess(String key) {
        attempts.remove(key);
    }

    private static final class Attempt {
        private int failures;
        private long blockedUntilEpochMillis;
    }
}
