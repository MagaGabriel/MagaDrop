import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

final class AccountPasswordHandler implements HttpHandler {
    private final UserStore users;
    private final SessionManager sessions;
    private final LoginRateLimiter rateLimiter;

    AccountPasswordHandler(UserStore users, SessionManager sessions, LoginRateLimiter rateLimiter) {
        this.users = users;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            HttpSupport.sendJson(exchange, 405, "{\"error\":\"Método não permitido\"}");
            return;
        }
        Optional<SessionManager.Session> authenticated = HttpSupport.session(exchange, sessions);
        if (authenticated.isEmpty()) { HttpSupport.sendJson(exchange, 401, "{\"error\":\"Entre no MagaDrop.\"}"); return; }
        SessionManager.Session session = authenticated.get();
        if (!HttpSupport.validCsrf(exchange, session)) {
            HttpSupport.sendJson(exchange, 403, "{\"error\":\"Confirmação de segurança inválida.\"}"); return;
        }
        Map<String, String> form;
        try { form = HttpSupport.readForm(exchange, 8 * 1024); }
        catch (HttpSupport.InvalidRequestException e) {
            HttpSupport.sendJson(exchange, e.status(), "{\"error\":" + HttpSupport.json(e.getMessage()) + "}"); return;
        }
        String key = remoteAddress(exchange) + ":" + session.username() + ":password-change";
        if (!rateLimiter.isAllowed(key)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            HttpSupport.sendJson(exchange, 429, "{\"error\":\"Muitas tentativas. Aguarde um minuto.\"}"); return;
        }
        Optional<UserAccount> verified = users.authenticate(session.username(), form.getOrDefault("currentPassword", ""));
        if (verified.isEmpty() || !verified.get().id().equals(session.userId())) {
            rateLimiter.recordFailure(key);
            HttpSupport.sendJson(exchange, 403, "{\"error\":\"A senha atual está incorreta.\"}"); return;
        }
        try {
            users.changePassword(session.username(), form.getOrDefault("newPassword", ""));
            rateLimiter.recordSuccess(key);
            sessions.invalidateAllForUser(session.userId());
            exchange.getResponseHeaders().add("Set-Cookie", HttpSupport.expiredSessionCookie());
            HttpSupport.sendJson(exchange, 204, "");
            MagaDrop.log("Senha alterada por " + session.username());
        } catch (IllegalArgumentException e) {
            HttpSupport.sendJson(exchange, 400, "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
        } catch (IOException e) {
            MagaDrop.log("Erro ao salvar nova senha: " + e.getMessage());
            HttpSupport.sendJson(exchange, 500, "{\"error\":\"Não foi possível salvar a nova senha.\"}");
        }
    }

    private static String remoteAddress(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }
}
