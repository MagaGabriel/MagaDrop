import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class SessionManager {
    static final String COOKIE_NAME = "MAGADROP_SESSION";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long idleTimeoutMillis;
    private final long absoluteTimeoutMillis;

    SessionManager() {
        this(Duration.ofHours(1), Duration.ofHours(12));
    }

    SessionManager(Duration idleTimeout, Duration absoluteTimeout) {
        if (idleTimeout.isNegative() || idleTimeout.isZero() || absoluteTimeout.compareTo(idleTimeout) < 0)
            throw new IllegalArgumentException("Tempos de sessão inválidos.");
        idleTimeoutMillis = idleTimeout.toMillis();
        absoluteTimeoutMillis = absoluteTimeout.toMillis();
    }

    Session create(UserAccount account) {
        cleanup();
        long now = System.currentTimeMillis();
        Session session = new Session(randomToken(), randomToken(), account.id(), account.username(), account.displayName(), account.role(), now, now);
        sessions.put(session.token, session);
        return session;
    }

    Optional<Session> find(String token) {
        if (token == null || token.length() != 43) return Optional.empty();
        Session session = sessions.get(token);
        if (session == null) return Optional.empty();
        long now = System.currentTimeMillis();
        if (now - session.lastSeenEpochMillis > idleTimeoutMillis || now - session.createdAtEpochMillis > absoluteTimeoutMillis) {
            sessions.remove(token, session);
            return Optional.empty();
        }
        session.lastSeenEpochMillis = now;
        return Optional.of(session);
    }

    void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    void invalidateAllForUser(String userId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().userId.equals(userId));
    }

    int size() {
        cleanup();
        return sessions.size();
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            return now - session.lastSeenEpochMillis > idleTimeoutMillis || now - session.createdAtEpochMillis > absoluteTimeoutMillis;
        });
    }

    private static String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static final class Session {
        private final String token;
        private final String csrfToken;
        private final String userId;
        private final String username;
        private final String displayName;
        private final UserRole role;
        private final long createdAtEpochMillis;
        private volatile long lastSeenEpochMillis;

        private Session(String token, String csrfToken, String userId, String username, String displayName, UserRole role,
                        long createdAtEpochMillis, long lastSeenEpochMillis) {
            this.token = token;
            this.csrfToken = csrfToken;
            this.userId = userId;
            this.username = username;
            this.displayName = displayName;
            this.role = role;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.lastSeenEpochMillis = lastSeenEpochMillis;
        }

        String token() { return token; }
        String csrfToken() { return csrfToken; }
        String userId() { return userId; }
        String username() { return username; }
        String displayName() { return displayName; }
        UserRole role() { return role; }
    }
}
