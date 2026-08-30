import com.sun.net.httpserver.*;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;

public class MagaDrop {
    private static final int PORTA = 8080;
    private static final long LIMITE_UPLOAD = 2L * 1024 * 1024 * 1024;
    static JTextArea logArea;
    static HttpServer server;
    static String ip = "localhost", codigoAcesso, baseDir;
    static Path pastaUploads, pastaWeb;
    static boolean rodando;
    static JFrame janela;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            detectarBaseDir(); criarPastas(); descobrirEndereco();
            codigoAcesso = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            mostrarSplash();
        });
    }

    static void mostrarSplash() {
        Splash splash = new Splash(); splash.setVisible(true);
        new Thread(() -> {
            try { Thread.sleep(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            SwingUtilities.invokeLater(() -> { splash.dispose(); criarInterface(); iniciarServidor(); });
        }, "magadrop-splash").start();
    }

    static void detectarBaseDir() {
        try {
            File origem = new File(MagaDrop.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File pasta = origem.isDirectory() ? origem : origem.getParentFile();
            if (pasta.getName().equalsIgnoreCase("src")) pasta = pasta.getParentFile();
            baseDir = pasta.getAbsolutePath();
        } catch (Exception e) { baseDir = System.getProperty("user.dir"); }
    }

    static void criarPastas() {
        pastaWeb = Paths.get(baseDir, "web").toAbsolutePath().normalize();
        String local = System.getenv("LOCALAPPDATA");
        Path dados = local == null || local.isBlank()
                ? Paths.get(System.getProperty("user.home"), "MagaDrop") : Paths.get(local, "MagaDrop");
        pastaUploads = dados.resolve("uploads").toAbsolutePath().normalize();
        try { Files.createDirectories(pastaWeb); Files.createDirectories(pastaUploads); migrarUploadsLegados(); }
        catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Não foi possível preparar as pastas:\n" + e.getMessage(),
                    "MagaDrop", JOptionPane.ERROR_MESSAGE); System.exit(1);
        }
    }

    static void migrarUploadsLegados() throws IOException {
        Path antiga = Paths.get(baseDir, "uploads").toAbsolutePath().normalize();
        if (antiga.equals(pastaUploads) || !Files.isDirectory(antiga)) return;
        try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(antiga)) {
            for (Path origem : arquivos) {
                if (!Files.isRegularFile(origem)) continue;
                Path destino = pastaUploads.resolve(origem.getFileName().toString()).normalize();
                if (destino.startsWith(pastaUploads) && !Files.exists(destino)) Files.copy(origem, destino);
            }
        }
    }

    static void criarInterface() {
        janela = new JFrame("MagaDrop"); janela.setSize(680, 440); janela.setLocationRelativeTo(null);
        janela.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); janela.setLayout(new BorderLayout(8, 8));
        JPanel info = new JPanel(new GridLayout(0, 1)); info.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        info.add(new JLabel("Endereço: " + enderecoServidor()));
        info.add(new JLabel("Código de acesso: " + codigoAcesso));
        info.add(new JLabel("Arquivos recebidos: " + pastaUploads)); janela.add(info, BorderLayout.NORTH);
        logArea = new JTextArea(); logArea.setEditable(false); logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        janela.add(new JScrollPane(logArea), BorderLayout.CENTER);
        JPanel painel = new JPanel();
        JButton iniciar = new JButton("Iniciar"), parar = new JButton("Parar");
        JButton abrir = new JButton("Abrir no navegador"), pasta = new JButton("Abrir pasta");
        painel.add(iniciar); painel.add(parar); painel.add(abrir); painel.add(pasta); janela.add(painel, BorderLayout.SOUTH);
        iniciar.addActionListener(e -> iniciarServidor()); parar.addActionListener(e -> pararServidor());
        abrir.addActionListener(e -> abrirNavegador()); pasta.addActionListener(e -> abrirPasta());
        janela.setVisible(true); criarTray(); log("MagaDrop iniciado");
    }

    static synchronized void iniciarServidor() {
        if (rodando) { log("Servidor já está rodando"); return; }
        try {
            descobrirEndereco(); server = HttpServer.create(new InetSocketAddress(PORTA), 0);
            server.createContext("/upload", new UploadHandler()); server.createContext("/", new PaginaHandler());
            server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "magadrop-http"); t.setDaemon(true); return t; }));
            server.start(); rodando = true;
            log("Servidor iniciado em " + enderecoServidor()); log("Código de acesso: " + codigoAcesso);
        } catch (IOException e) { server = null; rodando = false; log("Erro ao iniciar servidor: " + e.getMessage()); }
    }

    static synchronized void pararServidor() {
        if (server == null || !rodando) { log("Servidor já está parado"); return; }
        server.stop(0); server = null; rodando = false; log("Servidor parado");
    }

    static void abrirNavegador() {
        if (!rodando) { log("Inicie o servidor antes de abrir o navegador"); return; }
        try { Desktop.getDesktop().browse(new URI(enderecoServidor())); }
        catch (Exception e) { log("Erro ao abrir navegador: " + e.getMessage()); }
    }

    static void abrirPasta() {
        try { Desktop.getDesktop().open(pastaUploads.toFile()); }
        catch (Exception e) { log("Erro ao abrir pasta: " + e.getMessage()); }
    }

    static void descobrirEndereco() { try { ip = descobrirIP(); } catch (SocketException e) { ip = "localhost"; } }
    static String descobrirIP() throws SocketException {
        List<String> candidatos = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            String nome = (iface.getName() + " " + iface.getDisplayName()).toLowerCase(Locale.ROOT);
            if (!iface.isUp() || iface.isLoopback() || iface.isVirtual() || nome.contains("vpn") || nome.contains("virtual")) continue;
            Enumeration<InetAddress> enderecos = iface.getInetAddresses();
            while (enderecos.hasMoreElements()) {
                InetAddress endereco = enderecos.nextElement();
                if (endereco instanceof Inet4Address && endereco.isSiteLocalAddress()) candidatos.add(endereco.getHostAddress());
            }
        }
        return candidatos.isEmpty() ? "localhost" : candidatos.get(0);
    }
    static String enderecoServidor() { return "http://" + ip + ":" + PORTA; }
    static void log(String msg) { SwingUtilities.invokeLater(() -> { if (logArea != null) { logArea.append(msg + System.lineSeparator()); logArea.setCaretPosition(logArea.getDocument().getLength()); } }); }

    static void criarTray() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu(); MenuItem abrir = new MenuItem("Abrir"), sair = new MenuItem("Sair");
            abrir.addActionListener(e -> janela.setVisible(true)); sair.addActionListener(e -> { pararServidor(); System.exit(0); });
            menu.add(abrir); menu.add(sair);
            TrayIcon icon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(Paths.get(baseDir, "file.ico").toString()), "MagaDrop", menu);
            icon.setImageAutoSize(true); SystemTray.getSystemTray().add(icon);
        } catch (Exception e) { log("Não foi possível criar o ícone da bandeja"); }
    }

    static void responder(HttpExchange troca, int status, String mensagem) throws IOException {
        byte[] bytes = mensagem.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        troca.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        troca.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = troca.getResponseBody()) { out.write(bytes); }
    }

    static class PaginaHandler implements HttpHandler {
        private static final Map<String, String> TIPOS = Map.of("html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
                "js", "text/javascript; charset=utf-8", "png", "image/png", "ico", "image/x-icon", "svg", "image/svg+xml");
        public void handle(HttpExchange troca) throws IOException {
            String metodo = troca.getRequestMethod();
            if (!metodo.equals("GET") && !metodo.equals("HEAD")) { troca.getResponseHeaders().set("Allow", "GET, HEAD"); responder(troca, 405, "Método não permitido"); return; }
            String pedido = URLDecoder.decode(troca.getRequestURI().getPath(), StandardCharsets.UTF_8);
            if (pedido.equals("/")) pedido = "/index.html";
            Path arquivo = pastaWeb.resolve(pedido.substring(1)).normalize();
            if (!arquivo.startsWith(pastaWeb) || !Files.isRegularFile(arquivo)) { responder(troca, 404, "Arquivo não encontrado"); return; }
            String nome = arquivo.getFileName().toString(); int ponto = nome.lastIndexOf('.');
            String ext = ponto >= 0 ? nome.substring(ponto + 1).toLowerCase(Locale.ROOT) : "";
            Headers h = troca.getResponseHeaders(); h.set("Content-Type", TIPOS.getOrDefault(ext, "application/octet-stream"));
            h.set("X-Content-Type-Options", "nosniff"); h.set("Cache-Control", "no-cache");
            h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'");
            long tamanho = Files.size(arquivo); troca.sendResponseHeaders(200, metodo.equals("HEAD") ? -1 : tamanho);
            if (metodo.equals("GET")) try (OutputStream out = troca.getResponseBody()) { Files.copy(arquivo, out); } else troca.close();
            log("Acesso de " + troca.getRemoteAddress().getAddress().getHostAddress());
        }
    }

    static class UploadHandler implements HttpHandler {
        public void handle(HttpExchange troca) throws IOException {
            if (!troca.getRequestMethod().equals("POST")) { troca.getResponseHeaders().set("Allow", "POST"); responder(troca, 405, "Método não permitido"); return; }
            if (!codigoAcesso.equals(troca.getRequestHeaders().getFirst("X-Access-Code"))) { responder(troca, 401, "Código de acesso inválido"); return; }
            String nome = troca.getRequestHeaders().getFirst("X-Filename");
            if (nome == null || nome.isBlank() || nome.length() > 255 || nome.contains("/") || nome.contains("\\") || nome.equals(".") || nome.equals("..") || nome.chars().anyMatch(c -> c < 32)) {
                responder(troca, 400, "Nome de arquivo inválido"); return;
            }
            long informado = parseLong(troca.getRequestHeaders().getFirst("Content-Length"));
            if (informado > LIMITE_UPLOAD) { responder(troca, 413, "Arquivo excede o limite de 2 GB"); return; }
            Path destino = reservarDestino(nome); boolean concluido = false;
            try (InputStream in = troca.getRequestBody(); OutputStream out = Files.newOutputStream(destino)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0; int lidos;
                while ((lidos = in.read(buffer)) != -1) { total += lidos; if (total > LIMITE_UPLOAD) throw new UploadMuitoGrandeException(); out.write(buffer, 0, lidos); }
                concluido = true; log("Recebido: " + destino.getFileName() + " (" + total + " bytes)"); responder(troca, 201, destino.getFileName().toString());
            } catch (UploadMuitoGrandeException e) { responder(troca, 413, "Arquivo excede o limite de 2 GB"); }
            catch (IOException e) { log("Erro ao receber " + nome + ": " + e.getMessage()); try { responder(troca, 500, "Não foi possível salvar o arquivo"); } catch (IOException ignored) {} }
            finally { if (!concluido) Files.deleteIfExists(destino); }
        }
        private static long parseLong(String valor) { if (valor == null) return -1; try { return Long.parseLong(valor); } catch (NumberFormatException e) { return -1; } }
        private static Path reservarDestino(String nome) throws IOException {
            String base = nome, ext = ""; int ponto = nome.lastIndexOf('.');
            if (ponto > 0) { base = nome.substring(0, ponto); ext = nome.substring(ponto); }
            for (int i = 0; i < 10_000; i++) {
                String candidato = i == 0 ? nome : base + " (" + i + ")" + ext; Path destino = pastaUploads.resolve(candidato).normalize();
                if (!destino.startsWith(pastaUploads)) throw new IOException("Destino inválido");
                try { return Files.createFile(destino); } catch (FileAlreadyExistsException ignored) {}
            }
            throw new IOException("Muitos arquivos com o mesmo nome");
        }
    }
    static class UploadMuitoGrandeException extends IOException {}
}
