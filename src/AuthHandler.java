import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class AuthHandler implements HttpHandler {
    private final UserStore users;
    private final SessionManager sessions;
    private final LoginRateLimiter rateLimiter;

    AuthHandler(UserStore users, SessionManager sessions, LoginRateLimiter rateLimiter) {
        this.users = users;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> currentSession(exchange);
            case "POST" -> login(exchange);
            case "DELETE" -> logout(exchange);
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                HttpSupport.sendJson(exchange, 405, "{\"error\":\"Método não permitido\"}");
            }
        }
    }

    private void currentSession(HttpExchange exchange) throws IOException {
        Optional<SessionManager.Session> session = HttpSupport.session(exchange, sessions);
        if (session.isEmpty()) {
            HttpSupport.sendJson(exchange, 401, "{\"authenticated\":false}");
            return;
        }
        HttpSupport.sendJson(exchange, 200, sessionJson(session.get()));
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form;
        try { form = HttpSupport.readForm(exchange, 8 * 1024); }
        catch (HttpSupport.InvalidRequestException e) {
            HttpSupport.sendJson(exchange, e.status(), "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
            return;
        }
        String username = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");
        String key = remoteAddress(exchange) + ":" + safeUsername(username);
        if (!rateLimiter.isAllowed(key)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            HttpSupport.sendJson(exchange, 429, "{\"error\":\"Muitas tentativas. Aguarde um minuto.\"}");
            return;
        }
        Optional<UserAccount> account = users.authenticate(username, password);
        if (account.isEmpty()) {
            rateLimiter.recordFailure(key);
            HttpSupport.sendJson(exchange, 401, "{\"error\":\"Usuário ou senha inválidos.\"}");
            return;
        }
        rateLimiter.recordSuccess(key);
        SessionManager.Session session = sessions.create(account.get());
        exchange.getResponseHeaders().add("Set-Cookie", HttpSupport.sessionCookie(session));
        HttpSupport.sendJson(exchange, 200, sessionJson(session));
        MagaDrop.log("Login de " + account.get().username() + " em " + remoteAddress(exchange));
    }

    private void logout(HttpExchange exchange) throws IOException {
        Optional<SessionManager.Session> session = HttpSupport.session(exchange, sessions);
        if (session.isEmpty()) {
            exchange.getResponseHeaders().add("Set-Cookie", HttpSupport.expiredSessionCookie());
            HttpSupport.sendJson(exchange, 204, "");
            return;
        }
        if (!HttpSupport.validCsrf(exchange, session.get())) {
            HttpSupport.sendJson(exchange, 403, "{\"error\":\"Confirmação de segurança inválida.\"}");
            return;
        }
        sessions.invalidate(session.get().token());
        exchange.getResponseHeaders().add("Set-Cookie", HttpSupport.expiredSessionCookie());
        HttpSupport.sendJson(exchange, 204, "");
    }

    private static String sessionJson(SessionManager.Session session) {
        return "{\"authenticated\":true,\"csrfToken\":" + HttpSupport.json(session.csrfToken())
                + ",\"user\":{\"username\":" + HttpSupport.json(session.username())
                + ",\"displayName\":" + HttpSupport.json(session.displayName())
                + ",\"role\":" + HttpSupport.json(session.role().name().toLowerCase(Locale.ROOT)) + "}}";
    }

    private static String remoteAddress(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String safeUsername(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
