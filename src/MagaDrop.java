import com.sun.net.httpserver.*;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.prefs.Preferences;

public class MagaDrop {
    private static final int PORTA_PREFERIDA = 8080;
    private static final long LIMITE_UPLOAD = 2L * 1024 * 1024 * 1024;
    private static final String CHAVE_PASTA = "pastaUploads";
    private static final String CHAVE_SENHA = "senhaAcesso";
    private static final String CHAVE_INICIAR_WINDOWS = "iniciarComWindows";
    private static final Preferences PREFERENCIAS = Preferences.userNodeForPackage(MagaDrop.class);
    static JTextArea logArea;
    static HttpServer server;
    static ExecutorService servidorExecutor;
    static String ip = "localhost", senhaAcesso, baseDir;
    static int porta = PORTA_PREFERIDA;
    static Path pastaUploads, pastaWeb;
    static boolean rodando;
    static JFrame janela;
    static JLabel statusLabel, enderecoLabel, senhaLabel, pastaLabel;
    static JButton iniciarButton, pararButton, mostrarSenhaButton;
    static QrPanel qrPanel;
    static boolean senhaVisivel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            detectarBaseDir();
            if (!prepararConfiguracao()) return;
            descobrirEndereco();
            criarInterface();
            iniciarServidor();
        });
    }

    static void detectarBaseDir() {
        try {
            File origem = new File(MagaDrop.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File pasta = origem.isDirectory() ? origem : origem.getParentFile();
            if (pasta.getName().equalsIgnoreCase("src")) pasta = pasta.getParentFile();
            baseDir = pasta.getAbsolutePath();
        } catch (Exception e) { baseDir = System.getProperty("user.dir"); }
    }

    static boolean prepararConfiguracao() {
        pastaWeb = Paths.get(baseDir, "web").toAbsolutePath().normalize();
        Path padrao = pastaPadrao();
        String salva = PREFERENCIAS.get(CHAVE_PASTA, "");
        try { pastaUploads = salva.isBlank() ? padrao : Paths.get(salva).toAbsolutePath().normalize(); }
        catch (InvalidPathException e) { pastaUploads = padrao; }
        senhaAcesso = PREFERENCIAS.get(CHAVE_SENHA, "");
        if (senhaAcesso.isBlank() && !mostrarConfiguracaoInicial()) {
            System.exit(0);
            return false;
        }
        try { validarPastaDestino(pastaUploads); migrarUploadsLegados(); }
        catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Não foi possível preparar as pastas:\n" + e.getMessage(),
                    "MagaDrop", JOptionPane.ERROR_MESSAGE); System.exit(1); return false;
        }
        return true;
    }

    static Path pastaPadrao() {
        String local = System.getenv("LOCALAPPDATA");
        Path dados = local == null || local.isBlank()
                ? Paths.get(System.getProperty("user.home"), "MagaDrop") : Paths.get(local, "MagaDrop");
        return dados.resolve("uploads").toAbsolutePath().normalize();
    }

    static boolean mostrarConfiguracaoInicial() {
        JTextField campoPasta = new JTextField(pastaUploads.toString(), 34);
        JPasswordField campoSenha = new JPasswordField(18), confirmarSenha = new JPasswordField(18);
        JCheckBox iniciarWindows = new JCheckBox("Iniciar o MagaDrop junto com o Windows");
        JButton escolher = new JButton("Escolher...");
        escolher.addActionListener(e -> escolherPasta(campoPasta));

        JPanel pastaPainel = new JPanel(new BorderLayout(6, 0));
        pastaPainel.add(campoPasta, BorderLayout.CENTER); pastaPainel.add(escolher, BorderLayout.EAST);
        JPanel painel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 10, 4);
        painel.add(new JLabel("Configure o MagaDrop para começar"), c);
        c.gridy++; c.gridwidth = 1; c.insets = new Insets(4, 4, 4, 8); painel.add(new JLabel("Salvar arquivos em:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; painel.add(pastaPainel, c);
        c.gridx = 0; c.gridy++; c.weightx = 0; c.fill = GridBagConstraints.NONE; painel.add(new JLabel("Crie uma senha:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; painel.add(campoSenha, c);
        c.gridx = 0; c.gridy++; c.fill = GridBagConstraints.NONE; painel.add(new JLabel("Confirme a senha:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; painel.add(confirmarSenha, c);
        c.gridx = 1; c.gridy++; painel.add(new JLabel("4 a 32 caracteres: letras, números, @ # . _ ou -"), c);
        c.gridy++; painel.add(iniciarWindows, c);

        while (true) {
            int opcao = JOptionPane.showConfirmDialog(null, painel, "Primeira configuração",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (opcao != JOptionPane.OK_OPTION) return false;
            String senha = new String(campoSenha.getPassword());
            String confirmacao = new String(confirmarSenha.getPassword());
            try {
                if (!senha.equals(confirmacao)) throw new IllegalArgumentException("As senhas não são iguais.");
                validarSenha(senha);
                Path pasta = Paths.get(campoPasta.getText().trim()).toAbsolutePath().normalize();
                validarPastaDestino(pasta);
                if (iniciarWindows.isSelected()) configurarInicioWindows(true);
                pastaUploads = pasta; senhaAcesso = senha;
                salvarPreferencias();
                return true;
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Revise a configuração", JOptionPane.WARNING_MESSAGE);
            }
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
        janela = new JFrame("MagaDrop"); janela.setSize(820, 620); janela.setMinimumSize(new Dimension(720, 560));
        janela.setLocationRelativeTo(null); janela.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        janela.setLayout(new BorderLayout(12, 12));

        JPanel cabecalho = new JPanel(new BorderLayout());
        cabecalho.setBorder(BorderFactory.createEmptyBorder(16, 18, 0, 18));
        JLabel titulo = new JLabel("MagaDrop"); titulo.setFont(new Font("Segoe UI", Font.BOLD, 26));
        statusLabel = new JLabel("Iniciando..."); statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        cabecalho.add(titulo, BorderLayout.WEST); cabecalho.add(statusLabel, BorderLayout.EAST);
        janela.add(cabecalho, BorderLayout.NORTH);

        JPanel conteudo = new JPanel(new GridLayout(1, 2, 14, 0));
        conteudo.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        conteudo.add(criarPainelConexao()); conteudo.add(criarPainelQr());
        janela.add(conteudo, BorderLayout.CENTER);

        JPanel rodape = new JPanel(new BorderLayout(8, 8));
        rodape.setBorder(BorderFactory.createEmptyBorder(0, 18, 16, 18));
        rodape.add(criarPainelPasta(), BorderLayout.NORTH);
        logArea = new JTextArea(4, 20); logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane detalhes = new JScrollPane(logArea); detalhes.setBorder(BorderFactory.createTitledBorder("Atividade"));
        rodape.add(detalhes, BorderLayout.CENTER);
        janela.add(rodape, BorderLayout.SOUTH);

        janela.setVisible(true); criarTray(); atualizarInterface(); log("MagaDrop iniciado");
    }

    static JPanel criarPainelConexao() {
        JPanel painel = new JPanel(); painel.setLayout(new BoxLayout(painel, BoxLayout.Y_AXIS));
        painel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Conexão"), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel instrucao = new JLabel("No celular, abra o endereço ou leia o QR code."); instrucao.setAlignmentX(Component.LEFT_ALIGNMENT);
        painel.add(instrucao); painel.add(Box.createVerticalStrut(18));
        painel.add(rotuloSecao("Endereço"));
        enderecoLabel = new JLabel("Preparando..."); enderecoLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15)); enderecoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        painel.add(enderecoLabel); painel.add(Box.createVerticalStrut(6));
        JButton copiarEndereco = new JButton("Copiar endereço"); copiarEndereco.setAlignmentX(Component.LEFT_ALIGNMENT);
        copiarEndereco.addActionListener(e -> copiarTexto(enderecoServidor(), "Endereço copiado")); painel.add(copiarEndereco);
        painel.add(Box.createVerticalStrut(22)); painel.add(rotuloSecao("Senha de acesso"));
        senhaLabel = new JLabel(); senhaLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22)); senhaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        painel.add(senhaLabel); painel.add(Box.createVerticalStrut(6));
        JPanel senhaAcoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); senhaAcoes.setAlignmentX(Component.LEFT_ALIGNMENT);
        mostrarSenhaButton = new JButton("Mostrar"); JButton copiarSenha = new JButton("Copiar senha");
        mostrarSenhaButton.addActionListener(e -> { senhaVisivel = !senhaVisivel; atualizarSenhaExibida(); });
        copiarSenha.addActionListener(e -> copiarTexto(senhaAcesso, "Senha copiada"));
        senhaAcoes.add(mostrarSenhaButton); senhaAcoes.add(Box.createHorizontalStrut(6)); senhaAcoes.add(copiarSenha);
        senhaAcoes.setMaximumSize(new Dimension(Integer.MAX_VALUE, senhaAcoes.getPreferredSize().height)); painel.add(senhaAcoes);
        painel.add(Box.createVerticalGlue());
        JPanel controles = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); controles.setAlignmentX(Component.LEFT_ALIGNMENT);
        iniciarButton = new JButton("Iniciar"); pararButton = new JButton("Parar"); JButton navegador = new JButton("Abrir no navegador");
        iniciarButton.addActionListener(e -> iniciarServidor()); pararButton.addActionListener(e -> pararServidor()); navegador.addActionListener(e -> abrirNavegador());
        controles.add(iniciarButton); controles.add(pararButton); controles.add(navegador);
        controles.setMaximumSize(new Dimension(Integer.MAX_VALUE, controles.getPreferredSize().height)); painel.add(controles);
        return painel;
    }

    static JPanel criarPainelQr() {
        JPanel painel = new JPanel(new BorderLayout()); painel.setBorder(BorderFactory.createTitledBorder("QR code"));
        qrPanel = new QrPanel(); painel.add(qrPanel, BorderLayout.CENTER);
        JLabel dica = new JLabel("Celular e computador devem estar na mesma rede.", SwingConstants.CENTER);
        dica.setBorder(BorderFactory.createEmptyBorder(0, 6, 10, 6)); painel.add(dica, BorderLayout.SOUTH);
        return painel;
    }

    static JPanel criarPainelPasta() {
        JPanel painel = new JPanel(new BorderLayout(8, 6)); painel.setBorder(BorderFactory.createTitledBorder("Pasta de destino"));
        pastaLabel = new JLabel(pastaUploads.toString()); pastaLabel.setToolTipText(pastaUploads.toString());
        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton alterar = new JButton("Alterar pasta"), abrir = new JButton("Abrir pasta"), configuracoes = new JButton("Configurações");
        alterar.addActionListener(e -> alterarPasta()); abrir.addActionListener(e -> abrirPasta()); configuracoes.addActionListener(e -> mostrarConfiguracoes());
        acoes.add(alterar); acoes.add(abrir); acoes.add(configuracoes);
        painel.add(pastaLabel, BorderLayout.CENTER); painel.add(acoes, BorderLayout.EAST); return painel;
    }

    static JLabel rotuloSecao(String texto) {
        JLabel label = new JLabel(texto); label.setFont(new Font("Segoe UI", Font.PLAIN, 12)); label.setForeground(new Color(90, 90, 90));
        label.setAlignmentX(Component.LEFT_ALIGNMENT); return label;
    }

    static synchronized void iniciarServidor() {
        if (rodando) { log("Servidor já está rodando"); return; }
        try {
            descobrirEndereco(); server = criarServidorEmPorta(PORTA_PREFERIDA); porta = server.getAddress().getPort();
            server.createContext("/upload", new UploadHandler()); server.createContext("/", new PaginaHandler());
            int threads = Math.max(4, Math.min(12, Runtime.getRuntime().availableProcessors() * 2));
            servidorExecutor = Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "magadrop-http"); t.setDaemon(true); return t; });
            server.setExecutor(servidorExecutor);
            server.start(); rodando = true;
            if (porta != PORTA_PREFERIDA) log("A porta 8080 estava ocupada; usando a porta " + porta);
            log("Servidor iniciado em " + enderecoServidor());
        } catch (IOException e) {
            server = null; rodando = false;
            if (servidorExecutor != null) servidorExecutor.shutdownNow(); servidorExecutor = null;
            log("Erro ao iniciar servidor: " + e.getMessage());
        }
        atualizarInterface();
    }

    static HttpServer criarServidorEmPorta(int preferida) throws IOException {
        try { return HttpServer.create(new InetSocketAddress(preferida), 0); }
        catch (BindException ocupada) { return HttpServer.create(new InetSocketAddress(0), 0); }
    }

    static synchronized void pararServidor() {
        if (server == null || !rodando) { log("Servidor já está parado"); return; }
        server.stop(0); server = null; rodando = false;
        if (servidorExecutor != null) servidorExecutor.shutdownNow(); servidorExecutor = null;
        log("Servidor parado"); atualizarInterface();
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

    static void alterarPasta() {
        JFileChooser seletor = new JFileChooser(pastaUploads.toFile());
        seletor.setDialogTitle("Escolha onde salvar os arquivos"); seletor.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        seletor.setAcceptAllFileFilterUsed(false);
        if (seletor.showOpenDialog(janela) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path nova = seletor.getSelectedFile().toPath().toAbsolutePath().normalize(); validarPastaDestino(nova);
            pastaUploads = nova; PREFERENCIAS.put(CHAVE_PASTA, nova.toString()); atualizarInterface();
            log("Pasta de destino alterada para " + nova);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(janela, "Não foi possível usar essa pasta:\n" + e.getMessage(), "MagaDrop", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void escolherPasta(JTextField destino) {
        JFileChooser seletor = new JFileChooser(destino.getText());
        seletor.setDialogTitle("Escolha onde salvar os arquivos"); seletor.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        seletor.setAcceptAllFileFilterUsed(false);
        if (seletor.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) destino.setText(seletor.getSelectedFile().getAbsolutePath());
    }

    static void mostrarConfiguracoes() {
        JCheckBox iniciarWindows = new JCheckBox("Iniciar o MagaDrop junto com o Windows", PREFERENCIAS.getBoolean(CHAVE_INICIAR_WINDOWS, false));
        JButton alterarSenha = new JButton("Alterar senha de acesso"); JButton restaurarPasta = new JButton("Restaurar pasta padrão");
        alterarSenha.addActionListener(e -> alterarSenha());
        restaurarPasta.addActionListener(e -> {
            try {
                Path padrao = pastaPadrao(); validarPastaDestino(padrao); pastaUploads = padrao;
                PREFERENCIAS.put(CHAVE_PASTA, padrao.toString()); atualizarInterface();
                JOptionPane.showMessageDialog(janela, "A pasta padrão foi restaurada.", "MagaDrop", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) { JOptionPane.showMessageDialog(janela, ex.getMessage(), "MagaDrop", JOptionPane.ERROR_MESSAGE); }
        });
        JPanel painel = new JPanel(); painel.setLayout(new BoxLayout(painel, BoxLayout.Y_AXIS));
        iniciarWindows.setAlignmentX(Component.LEFT_ALIGNMENT); alterarSenha.setAlignmentX(Component.LEFT_ALIGNMENT); restaurarPasta.setAlignmentX(Component.LEFT_ALIGNMENT);
        painel.add(iniciarWindows); painel.add(Box.createVerticalStrut(12)); painel.add(alterarSenha); painel.add(Box.createVerticalStrut(8)); painel.add(restaurarPasta);
        int opcao = JOptionPane.showConfirmDialog(janela, painel, "Configurações", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opcao != JOptionPane.OK_OPTION) return;
        boolean anterior = PREFERENCIAS.getBoolean(CHAVE_INICIAR_WINDOWS, false);
        if (anterior != iniciarWindows.isSelected()) {
            try { configurarInicioWindows(iniciarWindows.isSelected()); }
            catch (Exception e) {
                JOptionPane.showMessageDialog(janela, "Não foi possível alterar a inicialização do Windows:\n" + e.getMessage(), "MagaDrop", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    static void alterarSenha() {
        JPasswordField senha = new JPasswordField(18), confirmacao = new JPasswordField(18);
        JPanel painel = new JPanel(new GridLayout(0, 2, 8, 8));
        painel.add(new JLabel("Nova senha:")); painel.add(senha); painel.add(new JLabel("Confirmar senha:")); painel.add(confirmacao);
        while (JOptionPane.showConfirmDialog(janela, painel, "Alterar senha", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String nova = new String(senha.getPassword());
            try {
                if (!nova.equals(new String(confirmacao.getPassword()))) throw new IllegalArgumentException("As senhas não são iguais.");
                validarSenha(nova); senhaAcesso = nova; PREFERENCIAS.put(CHAVE_SENHA, nova); senhaVisivel = false; atualizarInterface();
                log("Senha de acesso alterada"); return;
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(janela, e.getMessage(), "Revise a senha", JOptionPane.WARNING_MESSAGE);
                senha.setText(""); confirmacao.setText("");
            }
        }
    }

    static void validarSenha(String senha) {
        if (senha == null || !senha.matches("[A-Za-z0-9@#._-]{4,32}"))
            throw new IllegalArgumentException("Use de 4 a 32 caracteres: letras, números, @ # . _ ou -.");
    }

    static void validarPastaDestino(Path pasta) throws IOException {
        Files.createDirectories(pasta);
        if (!Files.isDirectory(pasta)) throw new IOException("O caminho escolhido não é uma pasta.");
        Path teste = Files.createTempFile(pasta, ".magadrop-", ".tmp");
        Files.deleteIfExists(teste);
    }

    static void salvarPreferencias() {
        PREFERENCIAS.put(CHAVE_PASTA, pastaUploads.toString()); PREFERENCIAS.put(CHAVE_SENHA, senhaAcesso);
    }

    static void configurarInicioWindows(boolean ativar) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            throw new IOException("Esta opção está disponível apenas no Windows.");
        String chave = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        List<String> comando = new ArrayList<>(); comando.add("reg.exe");
        if (ativar) {
            Path executavel = Paths.get(baseDir, "MagaDrop.exe").toAbsolutePath().normalize();
            if (!Files.isRegularFile(executavel)) throw new IOException("Executável não encontrado em " + executavel);
            comando.addAll(List.of("add", chave, "/v", "MagaDrop", "/t", "REG_SZ", "/d", "\"" + executavel + "\"", "/f"));
        } else comando.addAll(List.of("delete", chave, "/v", "MagaDrop", "/f"));
        Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String saida;
        try (InputStream in = processo.getInputStream()) { saida = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim(); }
        int codigo = processo.waitFor();
        if (codigo != 0 && !(codigo == 1 && !ativar)) throw new IOException(saida.isBlank() ? "O Windows recusou a alteração." : saida);
        PREFERENCIAS.putBoolean(CHAVE_INICIAR_WINDOWS, ativar);
    }

    static void copiarTexto(String texto, String mensagem) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(texto), null);
        log(mensagem);
    }

    static void atualizarInterface() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(MagaDrop::atualizarInterface); return; }
        if (statusLabel == null) return;
        statusLabel.setText(rodando ? "● Pronto para receber" : "● Servidor parado");
        statusLabel.setForeground(rodando ? new Color(22, 130, 72) : new Color(170, 55, 55));
        enderecoLabel.setText(enderecoServidor()); pastaLabel.setText(pastaUploads.toString()); pastaLabel.setToolTipText(pastaUploads.toString());
        iniciarButton.setEnabled(!rodando); pararButton.setEnabled(rodando); atualizarSenhaExibida();
        qrPanel.setConteudo(rodando ? enderecoServidor() : null);
    }

    static void atualizarSenhaExibida() {
        if (senhaLabel == null) return;
        senhaLabel.setText(senhaVisivel ? senhaAcesso : "•".repeat(Math.min(senhaAcesso.length(), 16)));
        mostrarSenhaButton.setText(senhaVisivel ? "Ocultar" : "Mostrar");
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
    static String enderecoServidor() { return "http://" + ip + ":" + porta; }
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

    static class QrPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private boolean[][] matriz;
        private String mensagem = "Iniciando servidor...";

        QrPanel() { setPreferredSize(new Dimension(280, 280)); setBackground(Color.WHITE); }

        void setConteudo(String texto) {
            if (texto == null) { matriz = null; mensagem = "Inicie o servidor para exibir o QR code"; }
            else {
                try { matriz = QrCode.encodeText(texto); mensagem = ""; }
                catch (IllegalArgumentException e) { matriz = null; mensagem = "QR code indisponível"; }
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (matriz == null) {
                g.setColor(new Color(100, 100, 100)); FontMetrics fm = g.getFontMetrics();
                g.drawString(mensagem, Math.max(8, (getWidth() - fm.stringWidth(mensagem)) / 2), getHeight() / 2); return;
            }
            int quiet = 4, total = matriz.length + quiet * 2;
            int escala = Math.max(1, Math.min(getWidth(), getHeight()) / total);
            int qrTamanho = total * escala, inicioX = (getWidth() - qrTamanho) / 2, inicioY = (getHeight() - qrTamanho) / 2;
            g.setColor(Color.WHITE); g.fillRect(inicioX, inicioY, qrTamanho, qrTamanho); g.setColor(Color.BLACK);
            for (int y = 0; y < matriz.length; y++) for (int x = 0; x < matriz.length; x++)
                if (matriz[y][x]) g.fillRect(inicioX + (x + quiet) * escala, inicioY + (y + quiet) * escala, escala, escala);
        }
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
            String recebida = troca.getRequestHeaders().getFirst("X-Access-Code");
            if (recebida == null || !MessageDigest.isEqual(senhaAcesso.getBytes(StandardCharsets.UTF_8), recebida.getBytes(StandardCharsets.UTF_8))) {
                responder(troca, 401, "Senha de acesso inválida"); return;
            }
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
            Path raiz = pastaUploads;
            String base = nome, ext = ""; int ponto = nome.lastIndexOf('.');
            if (ponto > 0) { base = nome.substring(0, ponto); ext = nome.substring(ponto); }
            for (int i = 0; i < 10_000; i++) {
                String candidato = i == 0 ? nome : base + " (" + i + ")" + ext; Path destino = raiz.resolve(candidato).normalize();
                if (!destino.startsWith(raiz)) throw new IOException("Destino inválido");
                try { return Files.createFile(destino); } catch (FileAlreadyExistsException ignored) {}
            }
            throw new IOException("Muitos arquivos com o mesmo nome");
        }
    }
    static class UploadMuitoGrandeException extends IOException { private static final long serialVersionUID = 1L; }
}
