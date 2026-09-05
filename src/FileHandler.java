import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FileHandler implements HttpHandler {
    private final StorageService storage;
    private final SessionManager sessions;

    FileHandler(StorageService storage, SessionManager sessions) {
        this.storage = storage; this.sessions = sessions;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        Optional<SessionManager.Session> authenticated = HttpSupport.session(exchange, sessions);
        if (authenticated.isEmpty()) { HttpSupport.sendJson(exchange, 401, "{\"error\":\"Entre no MagaDrop.\"}"); return; }
        try {
            switch (exchange.getRequestMethod()) {
                case "GET" -> list(exchange, authenticated.get());
                case "POST" -> change(exchange, authenticated.get());
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    HttpSupport.sendJson(exchange, 405, "{\"error\":\"Método não permitido\"}");
                }
            }
        } catch (HttpSupport.InvalidRequestException e) {
            HttpSupport.sendJson(exchange, e.status(), "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
        } catch (NoSuchFileException e) {
            HttpSupport.sendJson(exchange, 404, "{\"error\":\"Arquivo ou pasta não encontrado.\"}");
        } catch (FileAlreadyExistsException e) {
            HttpSupport.sendJson(exchange, 409, "{\"error\":\"Já existe um item com esse nome.\"}");
        } catch (IllegalArgumentException e) {
            HttpSupport.sendJson(exchange, 400, "{\"error\":" + HttpSupport.json(e.getMessage()) + "}");
        } catch (IOException e) {
            MagaDrop.log("Erro ao acessar arquivos: " + e.getMessage());
            HttpSupport.sendJson(exchange, 500, "{\"error\":\"Não foi possível concluir a operação no disco.\"}");
        } catch (RuntimeException e) {
            MagaDrop.log("Erro interno ao acessar arquivos: " + e.getMessage());
            HttpSupport.sendJson(exchange, 500, "{\"error\":\"Não foi possível concluir a operação.\"}");
        }
    }

    private void list(HttpExchange exchange, SessionManager.Session session) throws IOException {
        Map<String, String> query = HttpSupport.readQuery(exchange);
        StorageService.Area area = StorageService.Area.parse(query.getOrDefault("area", "personal"));
        String path = storage.normalizedPath(query.getOrDefault("path", ""));
        List<StorageService.Entry> entries = storage.list(session, area, path);
        StringBuilder json = new StringBuilder("{\"area\":").append(HttpSupport.json(area.apiName()))
                .append(",\"path\":").append(HttpSupport.json(path)).append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) json.append(',');
            StorageService.Entry entry = entries.get(i);
            json.append("{\"name\":").append(HttpSupport.json(entry.name()))
                    .append(",\"directory\":").append(entry.directory())
                    .append(",\"size\":").append(entry.size())
                    .append(",\"modifiedAt\":").append(entry.modifiedAtEpochMillis()).append('}');
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
        StorageService.Area area = StorageService.Area.parse(form.getOrDefault("area", "personal"));
        String action = form.getOrDefault("action", "");
        switch (action) {
            case "create-folder" -> {
                storage.createFolder(session, area, form.getOrDefault("path", ""), form.get("name"));
                MagaDrop.log("Pasta criada por " + session.username() + " em " + area.apiName());
                HttpSupport.sendJson(exchange, 201, "{\"ok\":true}");
            }
            case "delete" -> {
                storage.moveToTrash(session, area, form.getOrDefault("path", ""));
                MagaDrop.log("Item enviado à lixeira por " + session.username() + " em " + area.apiName());
                HttpSupport.sendJson(exchange, 200, "{\"ok\":true}");
            }
            default -> throw new IllegalArgumentException("Ação de arquivo inválida.");
        }
    }
}
