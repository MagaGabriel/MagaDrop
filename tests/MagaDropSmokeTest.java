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
        MagaDrop.usuarios.createInitialAdmin("admin", "Administrador", PASSWORD);
        MagaDrop.sessoes = new SessionManager();
        MagaDrop.tentativasLogin = new LoginRateLimiter();
    }

    private static void testPasswordProtection(Path temporary) throws Exception {
        String persisted = Files.readString(temporary.resolve("data/users.properties"));
        check(!persisted.contains(PASSWORD), "senha não armazenada em texto legível");
        check(persisted.contains("pbkdf2-sha256"), "hash PBKDF2 persistido");
        check(MagaDrop.usuarios.authenticate("admin", PASSWORD).isPresent(), "senha correta autenticada");
        check(MagaDrop.usuarios.authenticate("admin", "senha errada").isEmpty(), "senha incorreta rejeitada");
        UserStore migrated = new UserStore(temporary.resolve("legacy/users.properties"));
        migrated.createInitialAdmin("admin", "Administrador", "1234");
        check(migrated.authenticate("admin", "1234").isPresent(), "senha curta legada continua válida após migração");
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

            HttpResponse<String> invalidLogin = login(client, base, "admin", "senha errada");
            check(invalidLogin.statusCode() == 401, "login inválido rejeitado");
            HttpResponse<String> validLogin = login(client, base, "admin", PASSWORD);
            check(validLogin.statusCode() == 200, "login válido aceito");
            String cookie = validLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String csrf = jsonField(validLogin.body(), "csrfToken");
            check(cookie.startsWith(SessionManager.COOKIE_NAME + "="), "cookie de sessão emitido");
            check(validLogin.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"), "cookie HttpOnly");
            check(validLogin.headers().firstValue("Set-Cookie").orElseThrow().contains("SameSite=Strict"), "cookie SameSite estrito");

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

    private static HttpResponse<String> login(HttpClient client, URI base, String username, String password) throws Exception {
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/session"))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> upload(HttpClient client, URI base, String name, String cookie, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("/upload"))
                .header("X-Filename", name).POST(HttpRequest.BodyPublishers.ofString("conteúdo", StandardCharsets.UTF_8));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
