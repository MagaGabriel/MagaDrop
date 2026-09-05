import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

final class DownloadHandler implements HttpHandler {
    private final StorageService storage;
    private final SessionManager sessions;

    DownloadHandler(StorageService storage, SessionManager sessions) {
        this.storage = storage; this.sessions = sessions;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            HttpSupport.sendJson(exchange, 405, "{\"error\":\"Método não permitido\"}"); return;
        }
        Optional<SessionManager.Session> authenticated = HttpSupport.session(exchange, sessions);
        if (authenticated.isEmpty()) { HttpSupport.sendJson(exchange, 401, "{\"error\":\"Entre no MagaDrop.\"}"); return; }
        try {
            Map<String, String> query = HttpSupport.readQuery(exchange);
            StorageService.Area area = StorageService.Area.parse(query.getOrDefault("area", "personal"));
            Path file = storage.fileForDownload(authenticated.get(), area, query.getOrDefault("path", ""));
            String name = file.getFileName().toString();
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            String fallback = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            Headers headers = exchange.getResponseHeaders(); HttpSupport.secureHeaders(exchange);
            headers.set("Content-Type", "application/octet-stream");
            headers.set("Content-Disposition", "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded);
            headers.set("Cache-Control", "no-store");
            long length = java.nio.file.Files.size(file);
            exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : length);
            if (method.equals("GET")) try (OutputStream output = exchange.getResponseBody()) {
                java.nio.file.Files.copy(file, output);
            } else exchange.close();
            MagaDrop.log("Download por " + authenticated.get().username() + ": " + name);
        } catch (HttpSupport.InvalidRequestException e) {
            HttpSupport.sendJson(exchange, e.status(), "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
        } catch (NoSuchFileException e) {
            HttpSupport.sendJson(exchange, 404, "{\"error\":\"Arquivo não encontrado.\"}");
        } catch (IllegalArgumentException e) {
            HttpSupport.sendJson(exchange, 400, "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
        } catch (IOException e) {
            MagaDrop.log("Erro ao baixar arquivo: " + e.getMessage());
            HttpSupport.sendJson(exchange, 500, "{\"error\":\"Não foi possível ler o arquivo.\"}");
        }
    }
}
