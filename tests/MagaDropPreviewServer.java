import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Servidor descartável para conferir manualmente a interface em desenvolvimento. */
public class MagaDropPreviewServer {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("magadrop-preview-");
        MagaDrop.pastaWeb = Paths.get("web").toAbsolutePath().normalize();
        MagaDrop.pastaUploads = temporary.resolve("uploads");
        Files.createDirectories(MagaDrop.pastaUploads);
        MagaDrop.usuarios = new UserStore(temporary.resolve("data/users.properties"));
        MagaDrop.usuarios.createInitialAdmin("admin", "Administrador", "Teste seguro 123!");
        MagaDrop.sessoes = new SessionManager();
        MagaDrop.tentativasLogin = new LoginRateLimiter();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/session", new AuthHandler(MagaDrop.usuarios, MagaDrop.sessoes, MagaDrop.tentativasLogin));
        server.createContext("/api/account/password", new AccountPasswordHandler(MagaDrop.usuarios, MagaDrop.sessoes, MagaDrop.tentativasLogin));
        server.createContext("/api/users", new UserAdminHandler(MagaDrop.usuarios, MagaDrop.sessoes, MagaDrop.tentativasLogin));
        server.createContext("/upload", new MagaDrop.UploadHandler());
        server.createContext("/", new MagaDrop.PaginaHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try (var items = Files.walk(temporary)) {
                items.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }));
        server.start();
        String networkAddress;
        try { networkAddress = "http://" + MagaDrop.descobrirIP() + ":" + server.getAddress().getPort(); }
        catch (Exception e) { networkAddress = "indisponível"; }
        System.out.println("PREVIEW_URL_LOCAL=http://127.0.0.1:" + server.getAddress().getPort());
        System.out.println("PREVIEW_URL_NETWORK=" + networkAddress);
        System.out.println("PREVIEW_LOGIN=admin / Teste seguro 123!");
        new CountDownLatch(1).await();
    }
}
