import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class HttpSupport {
    private HttpSupport() {}

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        secureHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        secureHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    static void secureHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    static Map<String, String> readForm(HttpExchange exchange, int maximumBytes) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded"))
            throw new InvalidRequestException("Formato da requisição inválido.", 415);
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) { body = input.readNBytes(maximumBytes + 1); }
        if (body.length > maximumBytes) throw new InvalidRequestException("Requisição muito grande.", 413);
        Map<String, String> values = new LinkedHashMap<>();
        String raw = new String(body, StandardCharsets.UTF_8);
        for (String item : raw.split("&")) {
            if (item.isEmpty()) continue;
            int equals = item.indexOf('=');
            String key = decode(equals < 0 ? item : item.substring(0, equals));
            String value = decode(equals < 0 ? "" : item.substring(equals + 1));
            values.putIfAbsent(key, value);
        }
        return values;
    }

    static Map<String, String> readQuery(HttpExchange exchange) throws InvalidRequestException {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return values;
        if (raw.length() > 8 * 1024) throw new InvalidRequestException("Endereço muito grande.", 414);
        for (String item : raw.split("&")) {
            if (item.isEmpty()) continue;
            int equals = item.indexOf('=');
            String key = decode(equals < 0 ? item : item.substring(0, equals));
            String value = decode(equals < 0 ? "" : item.substring(equals + 1));
            values.putIfAbsent(key, value);
        }
        return values;
    }

    static Optional<SessionManager.Session> session(HttpExchange exchange, SessionManager manager) {
        return manager.find(cookie(exchange, SessionManager.COOKIE_NAME));
    }

    static boolean validCsrf(HttpExchange exchange, SessionManager.Session session) {
        String received = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (received == null) return false;
        return MessageDigest.isEqual(session.csrfToken().getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    static String sessionCookie(SessionManager.Session session) {
        return SessionManager.COOKIE_NAME + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200";
    }

    static String expiredSessionCookie() {
        return SessionManager.COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    static String cookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String item : header.split(";")) {
            String trimmed = item.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && name.equals(trimmed.substring(0, equals))) return trimmed.substring(equals + 1);
        }
        return null;
    }

    static String json(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int)c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static String decode(String value) throws InvalidRequestException {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new InvalidRequestException("Formulário inválido.", 400); }
    }

    static final class InvalidRequestException extends IOException {
        private final int status;
        InvalidRequestException(String message, int status) { super(message); this.status = status; }
        int status() { return status; }
    }
}
