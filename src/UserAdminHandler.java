import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class UserAdminHandler implements HttpHandler {
    private final UserStore users;
    private final SessionManager sessions;
    private final LoginRateLimiter rateLimiter;

    UserAdminHandler(UserStore users, SessionManager sessions, LoginRateLimiter rateLimiter) {
        this.users = users;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        Optional<SessionManager.Session> authenticated = HttpSupport.session(exchange, sessions);
        if (authenticated.isEmpty()) { HttpSupport.sendJson(exchange, 401, "{\"error\":\"Entre no MagaDrop.\"}"); return; }
        SessionManager.Session session = authenticated.get();
        if (session.role() != UserRole.ADMIN) {
            HttpSupport.sendJson(exchange, 403, "{\"error\":\"Apenas administradores podem gerenciar usuários.\"}"); return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> list(exchange);
            case "POST" -> change(exchange, session);
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                HttpSupport.sendJson(exchange, 405, "{\"error\":\"Método não permitido\"}");
            }
        }
    }

    private void list(HttpExchange exchange) throws IOException {
        List<UserAccount> accounts = users.list();
        StringBuilder json = new StringBuilder("{\"users\":[");
        for (int i = 0; i < accounts.size(); i++) {
            if (i > 0) json.append(',');
            UserAccount account = accounts.get(i);
            json.append("{\"username\":").append(HttpSupport.json(account.username()))
                    .append(",\"displayName\":").append(HttpSupport.json(account.displayName()))
                    .append(",\"role\":").append(HttpSupport.json(account.role().name().toLowerCase(Locale.ROOT)))
                    .append(",\"enabled\":").append(account.enabled())
                    .append(",\"activeSessions\":").append(sessions.countForUser(account.id())).append('}');
        }
        HttpSupport.sendJson(exchange, 200, json.append("]}").toString());
    }

    private void change(HttpExchange exchange, SessionManager.Session session) throws IOException {
        if (!HttpSupport.validCsrf(exchange, session)) {
            HttpSupport.sendJson(exchange, 403, "{\"error\":\"Confirmação de segurança inválida.\"}"); return;
        }
        Map<String, String> form;
        try { form = HttpSupport.readForm(exchange, 16 * 1024); }
        catch (HttpSupport.InvalidRequestException e) {
            HttpSupport.sendJson(exchange, e.status(), "{\"error\":" + HttpSupport.json(e.getMessage()) + "}"); return;
        }
        String key = remoteAddress(exchange) + ":" + session.username() + ":admin-action";
        if (!rateLimiter.isAllowed(key)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            HttpSupport.sendJson(exchange, 429, "{\"error\":\"Muitas tentativas. Aguarde um minuto.\"}"); return;
        }
        Optional<UserAccount> verified = users.authenticate(session.username(), form.getOrDefault("currentPassword", ""));
        if (verified.isEmpty() || !verified.get().id().equals(session.userId())) {
            rateLimiter.recordFailure(key);
            HttpSupport.sendJson(exchange, 403, "{\"error\":\"Senha do administrador incorreta.\"}"); return;
        }
        rateLimiter.recordSuccess(key);
        String action = form.getOrDefault("action", "");
        try {
            int status = switch (action) {
                case "create" -> create(form);
                case "reset-password" -> resetPassword(session, form);
                case "set-enabled" -> setEnabled(form);
                case "revoke-sessions" -> revokeSessions(session, form);
                default -> throw new IllegalArgumentException("Ação administrativa inválida.");
            };
            HttpSupport.sendJson(exchange, status, "{\"ok\":true}");
            MagaDrop.log("Ação de usuários '" + action + "' executada por " + session.username());
        } catch (IllegalArgumentException e) {
            HttpSupport.sendJson(exchange, 400, "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
        } catch (IOException e) {
            MagaDrop.log("Erro ao salvar alteração de usuário: " + e.getMessage());
            HttpSupport.sendJson(exchange, 500, "{\"error\":\"Não foi possível salvar a alteração.\"}");
        }
    }

    private int create(Map<String, String> form) throws IOException {
        users.createMember(form.get("username"), form.get("displayName"), form.getOrDefault("password", ""));
        return 201;
    }

    private int resetPassword(SessionManager.Session session, Map<String, String> form) throws IOException {
        UserAccount target = target(form);
        if (target.id().equals(session.userId()))
            throw new IllegalArgumentException("Altere sua própria senha em Minha conta.");
        users.changePassword(target.username(), form.getOrDefault("password", ""));
        sessions.invalidateAllForUser(target.id());
        return 200;
    }

    private int setEnabled(Map<String, String> form) throws IOException {
        UserAccount target = target(form);
        boolean enabled = switch (form.getOrDefault("enabled", "")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Estado da conta inválido.");
        };
        UserAccount updated = users.setEnabled(target.username(), enabled);
        if (!updated.enabled()) sessions.invalidateAllForUser(updated.id());
        return 200;
    }

    private int revokeSessions(SessionManager.Session session, Map<String, String> form) {
        UserAccount target = target(form);
        if (target.id().equals(session.userId()))
            throw new IllegalArgumentException("Use o botão Sair para encerrar sua sessão atual.");
        sessions.invalidateAllForUser(target.id());
        return 200;
    }

    private UserAccount target(Map<String, String> form) {
        return users.find(form.get("username")).orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
    }

    private static String remoteAddress(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }
}
