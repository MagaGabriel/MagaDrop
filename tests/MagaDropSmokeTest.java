import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MagaDropSmokeTest {
    private static final String PASSWORD = "Frase senha familiar 123!";

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("magadrop-smoke-");
        try {
            configure(temporary);
            testPasswordProtection(temporary);
            testUsers();
            testSessions();
            testRateLimit();
            testQrCode();
            testAutomaticPort();
            testHttpFlow(temporary);
            System.out.println("Todos os testes do MagaDrop passaram.");
        } finally {
            try (var items = Files.walk(temporary)) {
                items.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }

    private static void configure(Path temporary) throws Exception {
        MagaDrop.pastaWeb = Paths.get("web").toAbsolutePath().normalize();
        MagaDrop.pastaUploads = temporary.resolve("uploads");
        Files.createDirectories(MagaDrop.pastaUploads);
        MagaDrop.usuarios = new UserStore(temporary.resolve("data/users.properties"));
        MagaDrop.usuarios.createInitialAdmin("MAGA", "MAGA", PASSWORD);
        MagaDrop.armazenamento = new StorageService(MagaDrop.pastaUploads, temporary.resolve("data/Pessoal"));
        MagaDrop.sessoes = new SessionManager();
        MagaDrop.tentativasLogin = new LoginRateLimiter();
    }

    private static void testPasswordProtection(Path temporary) throws Exception {
        String persisted = Files.readString(temporary.resolve("data/users.properties"));
        check(!persisted.contains(PASSWORD), "senha não armazenada em texto legível");
        check(persisted.contains("pbkdf2-sha256"), "hash PBKDF2 persistido");
        check(MagaDrop.usuarios.authenticate("MAGA", PASSWORD).isPresent(), "senha correta autenticada");
        check(MagaDrop.usuarios.authenticate("MAGA", "senha errada").isEmpty(), "senha incorreta rejeitada");
        UserStore migrated = new UserStore(temporary.resolve("legacy/users.properties"));
        migrated.createInitialAdmin("admin", "Administrador", "1234");
        UserAccount renamed = migrated.renameInitialAdmin("MAGA", "MAGA");
        check(renamed.username().equals("maga") && renamed.displayName().equals("MAGA"), "administrador legado renomeado");
        check(migrated.authenticate("MAGA", "1234").isPresent(), "senha curta legada continua válida após migração");
        check(!Files.readString(temporary.resolve("legacy/users.properties")).contains("1234"), "senha legada também vira hash");
    }

    private static void testUsers() throws Exception {
        UserAccount member = MagaDrop.usuarios.createMember("esposa", "Esposa", "Outra frase senha 456!");
        check(member.role() == UserRole.MEMBER, "perfil de membro");
        check(MagaDrop.usuarios.size() == 2, "dois usuários cadastrados");
        check(MagaDrop.usuarios.authenticate("ESPOSA", "Outra frase senha 456!").isPresent(), "usuário normalizado");
        boolean duplicateRejected = false;
        try { MagaDrop.usuarios.createMember("esposa", "Duplicada", "Terceira frase senha 789!"); }
        catch (IllegalArgumentException e) { duplicateRejected = true; }
        check(duplicateRejected, "usuário duplicado rejeitado");
        boolean weakPasswordRejected = false;
        try { MagaDrop.usuarios.createMember("membro", "Membro", "1234"); }
        catch (IllegalArgumentException e) { weakPasswordRejected = true; }
        check(weakPasswordRejected, "senha fraca de novo membro rejeitada");
        boolean adminDisableRejected = false;
        try { MagaDrop.usuarios.setEnabled("MAGA", false); }
        catch (IllegalArgumentException e) { adminDisableRejected = true; }
        check(adminDisableRejected, "administrador principal não pode ser desativado");
        boolean adminDeleteRejected = false;
        try { MagaDrop.usuarios.deleteMember("MAGA"); }
        catch (IllegalArgumentException e) { adminDeleteRejected = true; }
        check(adminDeleteRejected && MagaDrop.usuarios.find("MAGA").isPresent(), "administrador principal não pode ser excluído");
    }

    private static void testSessions() {
        UserAccount admin = MagaDrop.usuarios.initialAdmin();
        SessionManager.Session first = MagaDrop.sessoes.create(admin);
        SessionManager.Session second = MagaDrop.sessoes.create(admin);
        check(first.token().length() >= 43 && first.csrfToken().length() >= 43, "tokens de 256 bits");
        check(!first.token().equals(second.token()), "tokens de sessão únicos");
        check(MagaDrop.sessoes.find(first.token()).isPresent(), "sessão válida encontrada");
        MagaDrop.sessoes.invalidateAllForUser(admin.id());
        check(MagaDrop.sessoes.find(first.token()).isEmpty(), "sessões do usuário revogadas");
    }

    private static void testRateLimit() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(1));
        check(limiter.isAllowed("client"), "primeira tentativa permitida");
        limiter.recordFailure("client");
        check(limiter.isAllowed("client"), "tentativa antes do limite permitida");
        limiter.recordFailure("client");
        check(!limiter.isAllowed("client"), "tentativas excessivas bloqueadas");
    }

    private static void testHttpFlow(Path temporary) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/session", new AuthHandler(MagaDrop.usuarios, MagaDrop.sessoes, MagaDrop.tentativasLogin));
        server.createContext("/api/account/password", new AccountPasswordHandler(MagaDrop.usuarios, MagaDrop.sessoes, MagaDrop.tentativasLogin));
        server.createContext("/api/users", new UserAdminHandler(MagaDrop.usuarios, MagaDrop.sessoes, MagaDrop.tentativasLogin));
        server.createContext("/api/files", new FileHandler(MagaDrop.armazenamento, MagaDrop.sessoes));
        server.createContext("/api/download", new DownloadHandler(MagaDrop.armazenamento, MagaDrop.sessoes));
        server.createContext("/upload", new MagaDrop.UploadHandler());
        server.createContext("/", new MagaDrop.PaginaHandler());
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable); thread.setDaemon(true); return thread;
        }));
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            check(client.send(HttpRequest.newBuilder(base.resolve("/")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200,
                    "GET da página");
            HttpResponse<String> anonymousSession = client.send(HttpRequest.newBuilder(base.resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(anonymousSession.statusCode() == 401, "sessão anônima rejeitada");
            check(upload(client, base, "teste.txt", null, null).statusCode() == 401, "upload sem sessão rejeitado");

            HttpResponse<String> invalidLogin = login(client, base, "MAGA", "senha errada");
            check(invalidLogin.statusCode() == 401, "login inválido rejeitado");
            HttpResponse<String> validLogin = login(client, base, "MAGA", PASSWORD);
            check(validLogin.statusCode() == 200, "login válido aceito");
            String cookie = validLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String csrf = jsonField(validLogin.body(), "csrfToken");
            check(cookie.startsWith(SessionManager.COOKIE_NAME + "="), "cookie de sessão emitido");
            check(validLogin.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"), "cookie HttpOnly");
            check(validLogin.headers().firstValue("Set-Cookie").orElseThrow().contains("SameSite=Strict"), "cookie SameSite estrito");

            testFileManagement(client, base, cookie, csrf, temporary);
            testUserAdministration(client, base, cookie, csrf);

            check(upload(client, base, "teste.txt", cookie, null).statusCode() == 403, "upload sem CSRF rejeitado");
            check(upload(client, base, "teste.txt", cookie, csrf).statusCode() == 201, "upload autenticado aceito");
            check(upload(client, base, "teste.txt", cookie, csrf).statusCode() == 201, "nome duplicado preservado");
            check(Files.readString(temporary.resolve("uploads/teste.txt")).equals("conteúdo"), "conteúdo salvo");
            check(Files.exists(temporary.resolve("uploads/teste (1).txt")), "arquivo duplicado criado");
            check(upload(client, base, "../fora.txt", cookie, csrf).statusCode() == 400, "travessia de diretório rejeitada");

            HttpRequest logout = HttpRequest.newBuilder(base.resolve("/api/session")).header("Cookie", cookie).header("X-CSRF-Token", csrf).DELETE().build();
            check(client.send(logout, HttpResponse.BodyHandlers.ofString()).statusCode() == 204, "logout aceito");
            check(upload(client, base, "depois.txt", cookie, csrf).statusCode() == 401, "sessão revogada não envia arquivo");
        } finally {
            server.stop(0);
        }
    }

    private static void testUserAdministration(HttpClient client, URI base, String adminCookie, String adminCsrf) throws Exception {
        HttpRequest adminList = HttpRequest.newBuilder(base.resolve("/api/users")).header("Cookie", adminCookie).GET().build();
        HttpResponse<String> usersResponse = client.send(adminList, HttpResponse.BodyHandlers.ofString());
        check(usersResponse.statusCode() == 200 && usersResponse.body().contains("\"username\":\"esposa\""), "administrador lista usuários");
        check(!usersResponse.body().contains("passwordHash") && !usersResponse.body().contains(PASSWORD), "API não expõe hashes ou senhas");

        LoginSession member = loginSession(client, base, "esposa", "Outra frase senha 456!");
        HttpRequest memberList = HttpRequest.newBuilder(base.resolve("/api/users")).header("Cookie", member.cookie()).GET().build();
        check(client.send(memberList, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "membro não acessa administração");

        Map<String, String> create = action("create", "filho");
        create.put("displayName", "Filho"); create.put("password", "Senha inicial do filho 789!");
        check(postForm(client, base.resolve("/api/users"), adminCookie, "csrf-inválido", create).statusCode() == 403,
                "ação administrativa sem CSRF rejeitada");
        check(adminAction(client, base, adminCookie, adminCsrf, create).statusCode() == 201, "administrador cria membro");
        check(MagaDrop.usuarios.authenticate("filho", "Senha inicial do filho 789!").isPresent(), "novo membro pode autenticar");

        Map<String, String> reset = action("reset-password", "esposa"); reset.put("password", "Senha redefinida 987!");
        check(adminAction(client, base, adminCookie, adminCsrf, reset).statusCode() == 200, "administrador redefine senha");
        check(upload(client, base, "revogada.txt", member.cookie(), member.csrf()).statusCode() == 401, "redefinição encerra sessão do membro");
        member = loginSession(client, base, "esposa", "Senha redefinida 987!");

        Map<String, String> disable = action("set-enabled", "esposa"); disable.put("enabled", "false");
        check(adminAction(client, base, adminCookie, adminCsrf, disable).statusCode() == 200, "administrador desativa membro");
        check(upload(client, base, "desativada.txt", member.cookie(), member.csrf()).statusCode() == 401, "desativação encerra sessão");
        check(login(client, base, "esposa", "Senha redefinida 987!").statusCode() == 401, "conta desativada não entra");

        Map<String, String> enable = action("set-enabled", "esposa"); enable.put("enabled", "true");
        check(adminAction(client, base, adminCookie, adminCsrf, enable).statusCode() == 200, "administrador reativa membro");
        member = loginSession(client, base, "esposa", "Senha redefinida 987!");

        Map<String, String> revoke = action("revoke-sessions", "esposa");
        check(adminAction(client, base, adminCookie, adminCsrf, revoke).statusCode() == 200, "administrador encerra sessões");
        check(upload(client, base, "sessao-encerrada.txt", member.cookie(), member.csrf()).statusCode() == 401, "sessão encerrada deixa de funcionar");
        member = loginSession(client, base, "esposa", "Senha redefinida 987!");

        Map<String, String> ownPassword = new LinkedHashMap<>();
        ownPassword.put("currentPassword", "Senha redefinida 987!"); ownPassword.put("newPassword", "Senha escolhida pela esposa 654!");
        HttpResponse<String> changed = postForm(client, base.resolve("/api/account/password"), member.cookie(), member.csrf(), ownPassword);
        check(changed.statusCode() == 204, "membro altera a própria senha");
        check(upload(client, base, "senha-alterada.txt", member.cookie(), member.csrf()).statusCode() == 401, "troca da própria senha encerra sessões");
        check(login(client, base, "esposa", "Senha escolhida pela esposa 654!").statusCode() == 200, "nova senha do membro funciona");

        LoginSession child = loginSession(client, base, "filho", "Senha inicial do filho 789!");
        Map<String, String> wrongDelete = action("delete", "filho"); wrongDelete.put("confirmation", "outro");
        check(adminAction(client, base, adminCookie, adminCsrf, wrongDelete).statusCode() == 400, "exclusão exige confirmação exata");
        check(MagaDrop.usuarios.find("filho").isPresent(), "confirmação incorreta preserva membro");

        Map<String, String> delete = action("delete", "filho"); delete.put("confirmation", "filho");
        check(adminAction(client, base, adminCookie, adminCsrf, delete).statusCode() == 200, "administrador exclui membro");
        check(MagaDrop.usuarios.find("filho").isEmpty(), "membro excluído do cadastro");
        check(upload(client, base, "excluido.txt", child.cookie(), child.csrf()).statusCode() == 401, "exclusão encerra sessões do membro");
        check(login(client, base, "filho", "Senha inicial do filho 789!").statusCode() == 401, "membro excluído não entra");

        Map<String, String> deleteAdmin = action("delete", "MAGA"); deleteAdmin.put("confirmation", "maga");
        check(adminAction(client, base, adminCookie, adminCsrf, deleteAdmin).statusCode() == 400, "API não exclui administrador principal");
        check(MagaDrop.usuarios.find("MAGA").isPresent(), "administrador permanece cadastrado");
    }

    private static void testFileManagement(HttpClient client, URI base, String adminCookie, String adminCsrf, Path temporary) throws Exception {
        HttpRequest anonymous = HttpRequest.newBuilder(filesUri(base, "personal", "")).GET().build();
        check(client.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode() == 401, "arquivos exigem autenticação");

        LoginSession member = loginSession(client, base, "esposa", "Outra frase senha 456!");
        Map<String, String> personalFolder = fileAction("create-folder", "personal", "");
        personalFolder.put("name", "Fotos");
        check(postForm(client, base.resolve("/api/files"), member.cookie(), member.csrf(), personalFolder).statusCode() == 201,
                "membro cria pasta pessoal");
        check(upload(client, base, "foto.txt", member.cookie(), member.csrf(), "personal", "Fotos").statusCode() == 201,
                "membro envia arquivo para pasta pessoal escolhida");
        String memberPersonal = getFiles(client, base, member.cookie(), "personal", "Fotos").body();
        check(memberPersonal.contains("\"name\":\"foto.txt\""), "membro lista o próprio arquivo");
        check(!getFiles(client, base, adminCookie, "personal", "").body().contains("Fotos"), "pastas pessoais são isoladas por conta");

        HttpResponse<String> personalDownload = download(client, base, member.cookie(), "personal", "Fotos/foto.txt");
        check(personalDownload.statusCode() == 200 && personalDownload.body().equals("conteúdo"), "membro baixa arquivo pessoal");
        check(personalDownload.headers().firstValue("Content-Disposition").orElse("").contains("attachment"), "download força anexo");
        check(download(client, base, adminCookie, "personal", "Fotos/foto.txt").statusCode() == 404,
                "administrador não atravessa para pasta pessoal de membro");

        Map<String, String> sharedFolder = fileAction("create-folder", "shared", ""); sharedFolder.put("name", "Familia");
        check(postForm(client, base.resolve("/api/files"), adminCookie, adminCsrf, sharedFolder).statusCode() == 201,
                "administrador cria pasta compartilhada");
        check(upload(client, base, "documento.txt", member.cookie(), member.csrf(), "shared", "Familia").statusCode() == 201,
                "membro envia para pasta compartilhada");
        check(getFiles(client, base, adminCookie, "shared", "Familia").body().contains("documento.txt"),
                "administrador vê arquivo compartilhado");
        check(download(client, base, member.cookie(), "shared", "Familia/documento.txt").statusCode() == 200,
                "membro baixa arquivo compartilhado");

        check(getFiles(client, base, member.cookie(), "personal", "../").statusCode() == 400, "travessia na listagem é rejeitada");
        Map<String, String> invalidFolder = fileAction("create-folder", "personal", ""); invalidFolder.put("name", "..");
        check(postForm(client, base.resolve("/api/files"), member.cookie(), member.csrf(), invalidFolder).statusCode() == 400,
                "nome de pasta perigoso é rejeitado");

        Map<String, String> deleteFile = fileAction("delete", "personal", "Fotos/foto.txt");
        check(postForm(client, base.resolve("/api/files"), member.cookie(), member.csrf(), deleteFile).statusCode() == 200,
                "arquivo pessoal vai para lixeira");
        check(download(client, base, member.cookie(), "personal", "Fotos/foto.txt").statusCode() == 404,
                "arquivo excluído deixa de aparecer");
        Map<String, String> deletePersonalFolder = fileAction("delete", "personal", "Fotos");
        check(postForm(client, base.resolve("/api/files"), member.cookie(), member.csrf(), deletePersonalFolder).statusCode() == 200,
                "pasta pessoal vai para lixeira");

        Map<String, String> deleteSharedFolder = fileAction("delete", "shared", "Familia");
        check(postForm(client, base.resolve("/api/files"), member.cookie(), member.csrf(), deleteSharedFolder).statusCode() == 200,
                "pasta compartilhada não vazia vai para lixeira");
        check(!Files.exists(temporary.resolve("uploads/Familia")), "item compartilhado removido da visualização");
        check(Files.isDirectory(temporary.resolve("uploads/.magadrop-trash")), "lixeira compartilhada criada");
        try (var paths = Files.walk(temporary.resolve("data/Pessoal"))) {
            check(paths.anyMatch(path -> path.getFileName().toString().equals(".magadrop-trash")), "lixeira pessoal criada");
        }
    }

    private static Map<String, String> fileAction(String action, String area, String path) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("action", action); values.put("area", area); values.put("path", path); return values;
    }

    private static Map<String, String> action(String action, String username) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("action", action); values.put("username", username); values.put("currentPassword", PASSWORD);
        return values;
    }

    private static HttpResponse<String> adminAction(HttpClient client, URI base, String cookie, String csrf, Map<String, String> values) throws Exception {
        return postForm(client, base.resolve("/api/users"), cookie, csrf, values);
    }

    private static HttpResponse<String> postForm(HttpClient client, URI uri, String cookie, String csrf, Map<String, String> values) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).header("Cookie", cookie).header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static LoginSession loginSession(HttpClient client, URI base, String username, String password) throws Exception {
        HttpResponse<String> response = login(client, base, username, password);
        check(response.statusCode() == 200, "login de " + username);
        return new LoginSession(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0], jsonField(response.body(), "csrfToken"));
    }

    private record LoginSession(String cookie, String csrf) {}

    private static HttpResponse<String> login(HttpClient client, URI base, String username, String password) throws Exception {
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/session"))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> upload(HttpClient client, URI base, String name, String cookie, String csrf) throws Exception {
        return upload(client, base, name, cookie, csrf, null, null);
    }

    private static HttpResponse<String> upload(HttpClient client, URI base, String name, String cookie, String csrf,
                                               String area, String path) throws Exception {
        URI target = base.resolve("/upload");
        if (area != null) target = URI.create(base + "/upload?area=" + encode(area) + "&path=" + encode(path));
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .header("X-Filename", name).POST(HttpRequest.BodyPublishers.ofString("conteúdo", StandardCharsets.UTF_8));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getFiles(HttpClient client, URI base, String cookie, String area, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(filesUri(base, area, path)).header("Cookie", cookie).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI filesUri(URI base, String area, String path) {
        return URI.create(base + "/api/files?area=" + encode(area) + "&path=" + encode(path));
    }

    private static HttpResponse<String> download(HttpClient client, URI base, String cookie, String area, String path) throws Exception {
        URI uri = URI.create(base + "/api/download?area=" + encode(area) + "&path=" + encode(path));
        HttpRequest request = HttpRequest.newBuilder(uri).header("Cookie", cookie).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String jsonField(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\":\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new AssertionError("Campo JSON ausente: " + field);
        return matcher.group(1);
    }

    private static void testPasswordPolicy() {
        MagaDrop.validarSenha("Uma frase senha segura");
        boolean rejected = false;
        try { MagaDrop.validarSenha("123456789"); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "senha curta rejeitada");
    }

    private static void testQrCode() {
        boolean[][] qr = QrCode.encodeText("http://192.168.0.10:8080");
        check(qr.length >= 21 && qr.length == qr[0].length, "matriz QR");
        testPasswordPolicy();
    }

    private static void testAutomaticPort() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            HttpServer alternative = MagaDrop.criarServidorEmPorta(occupied.getLocalPort());
            check(alternative.getAddress().getPort() != occupied.getLocalPort(), "porta alternativa");
            alternative.start(); alternative.stop(0);
        }
    }

    private static void check(boolean condition, String testCase) {
        if (!condition) throw new AssertionError("Falhou: " + testCase);
    }
}
