import com.sun.net.httpserver.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.prefs.Preferences;

public class MagaDrop {
    private static final String VERSAO = "3.0.0-preview.4";
    private static final int PORTA_PREFERIDA = 8080;
    private static final long LIMITE_UPLOAD = 2L * 1024 * 1024 * 1024;
    private static final String CHAVE_PASTA_RAIZ = "pastaRaiz";
    private static final String CHAVE_SENHA = "senhaAcesso";
    private static final String CHAVE_INICIAR_WINDOWS = "iniciarComWindows";
    private static final Preferences PREFERENCIAS = Preferences.userNodeForPackage(MagaDrop.class);
    static JTextArea logArea;
    static HttpServer server;
    static ExecutorService servidorExecutor;
    static String ip = "localhost", baseDir;
    static int porta = PORTA_PREFERIDA;
    static Path pastaRaiz, pastaUploads, pastaUsuarios, pastaWeb, pastaDados;
    static UserStore usuarios;
    static SessionManager sessoes;
    static LoginRateLimiter tentativasLogin;
    static StorageService armazenamento;
    static char[] senhaConfiguracaoInicial;
    static boolean rodando;
    static JFrame janela;
    static JLabel statusLabel, enderecoLabel, usuarioLabel, pastaLabel;
    static JButton iniciarButton, pararButton;
    static QrPanel qrPanel;

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
        pastaDados = pastaDados();
        Path padrao = pastaPadrao();
        String salva = PREFERENCIAS.get(CHAVE_PASTA_RAIZ, "");
        try { pastaRaiz = salva.isBlank() ? padrao : Paths.get(salva).toAbsolutePath().normalize(); }
        catch (InvalidPathException e) { pastaRaiz = padrao; }
        atualizarPastasDaRaiz();
        String senhaLegada = PREFERENCIAS.get(CHAVE_SENHA, "");
        try {
            usuarios = new UserStore(pastaDados.resolve("users.properties"));
            if (usuarios.isEmpty() && senhaLegada.isBlank()) {
                if (!mostrarConfiguracaoInicial()) { System.exit(0); return false; }
                senhaLegada = new String(senhaConfiguracaoInicial);
            }
            validarPastaRaiz(pastaRaiz);
            migrarUploadsLegados();
            if (usuarios.isEmpty()) {
                usuarios.createInitialAdmin("MAGA", "MAGA", senhaLegada);
                PREFERENCIAS.remove(CHAVE_SENHA);
            } else if (!senhaLegada.isBlank()) {
                PREFERENCIAS.remove(CHAVE_SENHA);
            }
            usuarios.renameInitialAdmin("MAGA", "MAGA");
            if (senhaConfiguracaoInicial != null) Arrays.fill(senhaConfiguracaoInicial, '\0');
            senhaConfiguracaoInicial = null;
            armazenamento = new StorageService(pastaUploads, pastaUsuarios);
            for (UserAccount conta : usuarios.list()) armazenamento.ensurePersonalRoot(conta);
            sessoes = new SessionManager();
            tentativasLogin = new LoginRateLimiter();
        }
        catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Não foi possível preparar as pastas:\n" + e.getMessage(),
                    "MagaDrop", JOptionPane.ERROR_MESSAGE); System.exit(1); return false;
        }
        return true;
    }

    static Path pastaDados() {
        String local = System.getenv("LOCALAPPDATA");
        return (local == null || local.isBlank()
                ? Paths.get(System.getProperty("user.home"), "MagaDrop") : Paths.get(local, "MagaDrop"))
                .toAbsolutePath().normalize();
    }

    static Path pastaPadrao() {
        Path preferida = Paths.get("D:\\backup nuvem").toAbsolutePath().normalize();
        if (Files.isDirectory(preferida)) return preferida;
        return Paths.get(System.getProperty("user.home"), "MagaDrop").toAbsolutePath().normalize();
    }

    static void atualizarPastasDaRaiz() {
        pastaUploads = pastaRaiz.resolve("Compartilhada").toAbsolutePath().normalize();
        pastaUsuarios = pastaRaiz.resolve("Usuarios").toAbsolutePath().normalize();
    }

    static boolean mostrarConfiguracaoInicial() {
        JTextField campoPasta = new JTextField("", 34);
        campoPasta.setEditable(false);
        campoPasta.setToolTipText("Escolha a pasta base onde o MagaDrop salvará todos os arquivos.");
        JPasswordField campoSenha = new JPasswordField(18), confirmarSenha = new JPasswordField(18);
        JCheckBox iniciarWindows = new JCheckBox("Iniciar o MagaDrop junto com o Windows");
        JButton escolher = new JButton("Escolher...");
        boolean[] pastaEscolhida = {false};
        escolher.addActionListener(e -> {
            if (escolherPasta(campoPasta)) pastaEscolhida[0] = true;
        });

        JPanel pastaPainel = new JPanel(new BorderLayout(6, 0));
        pastaPainel.add(campoPasta, BorderLayout.CENTER); pastaPainel.add(escolher, BorderLayout.EAST);
        JPanel painel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 10, 4);
        painel.add(new JLabel("Configure o administrador do MagaDrop"), c);
        c.gridy++; c.insets = new Insets(4, 4, 10, 4);
        painel.add(new JLabel("Escolha a pasta base. Dentro dela serão criadas as pastas Compartilhada e Usuarios."), c);
        c.gridy++; c.gridwidth = 1; c.insets = new Insets(4, 4, 4, 8); painel.add(new JLabel("Pasta principal:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; painel.add(pastaPainel, c);
        c.gridx = 0; c.gridy++; c.weightx = 0; c.fill = GridBagConstraints.NONE; painel.add(new JLabel("Senha do administrador:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; painel.add(campoSenha, c);
        c.gridx = 0; c.gridy++; c.fill = GridBagConstraints.NONE; painel.add(new JLabel("Confirme a senha:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; painel.add(confirmarSenha, c);
        c.gridx = 1; c.gridy++; painel.add(new JLabel("Use uma senha ou frase-senha de 10 a 128 caracteres"), c);
        c.gridy++; painel.add(iniciarWindows, c);

        while (true) {
            int opcao = JOptionPane.showConfirmDialog(null, painel, "Primeira configuração",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (opcao != JOptionPane.OK_OPTION) return false;
            String senha = new String(campoSenha.getPassword());
            String confirmacao = new String(confirmarSenha.getPassword());
            try {
                if (!senha.equals(confirmacao)) throw new IllegalArgumentException("As senhas não são iguais.");
                PasswordHasher.validateNewPassword(senha);
                Path pasta = validarSelecaoPastaInicial(campoPasta.getText(), pastaEscolhida[0]);
                validarPastaRaiz(pasta);
                if (iniciarWindows.isSelected()) configurarInicioWindows(true);
                pastaRaiz = pasta; atualizarPastasDaRaiz(); senhaConfiguracaoInicial = senha.toCharArray();
                salvarPreferencias();
                return true;
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Revise a configuração", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    static void migrarUploadsLegados() throws IOException {
        Files.createDirectories(pastaUploads);
        copiarArquivosLegados(Paths.get(baseDir, "uploads").toAbsolutePath().normalize());
        copiarArquivosLegados(pastaDados.resolve("uploads").toAbsolutePath().normalize());
    }

    static void copiarArquivosLegados(Path antiga) throws IOException {
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
        janela = new JFrame("MagaDrop 3 Preview"); janela.setSize(820, 620); janela.setMinimumSize(new Dimension(720, 560));
        janela.setLocationRelativeTo(null);
        janela.setDefaultCloseOperation(SystemTray.isSupported() ? JFrame.HIDE_ON_CLOSE : JFrame.EXIT_ON_CLOSE);
        janela.setLayout(new BorderLayout(12, 12));

        JPanel cabecalho = new JPanel(new BorderLayout());
        cabecalho.setBorder(BorderFactory.createEmptyBorder(16, 18, 0, 18));
        JLabel titulo = new JLabel("MagaDrop 3 Preview"); titulo.setFont(new Font("Segoe UI", Font.BOLD, 26));
        titulo.setToolTipText("Versão " + VERSAO);
        JPanel marca = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); marca.setOpaque(false);
        Path logoPath = pastaWeb.resolve("maga-logo.png");
        if (Files.isRegularFile(logoPath)) {
            ImageIcon logo = new ImageIcon(logoPath.toString());
            marca.add(new JLabel(logo)); janela.setIconImage(logo.getImage());
        }
        marca.add(titulo);
        statusLabel = new JLabel("Iniciando..."); statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        cabecalho.add(marca, BorderLayout.WEST); cabecalho.add(statusLabel, BorderLayout.EAST);
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

        janela.setVisible(true); criarTray(); atualizarInterface();
        log("MagaDrop iniciado" + (SystemTray.isSupported() ? "; fechar a janela mantém o servidor na bandeja" : ""));
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
        painel.add(Box.createVerticalStrut(22)); painel.add(rotuloSecao("Conta administradora"));
        usuarioLabel = new JLabel(); usuarioLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20)); usuarioLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        painel.add(usuarioLabel); painel.add(Box.createVerticalStrut(6));
        JLabel avisoSenha = new JLabel("A senha é protegida e nunca é exibida.");
        avisoSenha.setForeground(new Color(90, 90, 90)); avisoSenha.setAlignmentX(Component.LEFT_ALIGNMENT); painel.add(avisoSenha);
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
        JPanel painel = new JPanel(new BorderLayout(8, 6)); painel.setBorder(BorderFactory.createTitledBorder("Pasta principal"));
        pastaLabel = new JLabel(pastaRaiz.toString()); pastaLabel.setToolTipText(pastaRaiz.toString());
        JPanel acoes = new JPanel(new GridLayout(0, 3, 6, 6));
        JButton usuariosButton = new JButton("Usuários"), alterar = new JButton("Alterar pasta principal"), abrir = new JButton("Abrir compartilhada");
        JButton pessoais = new JButton("Pastas pessoais"), configuracoes = new JButton("Configurações");
        usuariosButton.addActionListener(e -> mostrarUsuarios()); alterar.addActionListener(e -> alterarPasta());
        abrir.addActionListener(e -> abrirPasta()); pessoais.addActionListener(e -> abrirPastasPessoais()); configuracoes.addActionListener(e -> mostrarConfiguracoes());
        acoes.add(usuariosButton); acoes.add(alterar); acoes.add(abrir); acoes.add(pessoais); acoes.add(configuracoes);
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
            server.createContext("/api/session", new AuthHandler(usuarios, sessoes, tentativasLogin));
            server.createContext("/api/account/password", new AccountPasswordHandler(usuarios, sessoes, tentativasLogin));
            server.createContext("/api/users", new UserAdminHandler(usuarios, sessoes, tentativasLogin));
            server.createContext("/api/files", new FileHandler(armazenamento, sessoes));
            server.createContext("/api/download", new DownloadHandler(armazenamento, sessoes));
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

    static void abrirPastasPessoais() {
        try { Desktop.getDesktop().open(pastaUsuarios.toFile()); }
        catch (Exception e) { log("Erro ao abrir pastas pessoais: " + e.getMessage()); }
    }

    static void alterarPasta() {
        JFileChooser seletor = new JFileChooser(pastaRaiz.toFile());
        seletor.setDialogTitle("Escolha a pasta principal do MagaDrop"); seletor.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        seletor.setAcceptAllFileFilterUsed(false);
        if (seletor.showOpenDialog(janela) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path nova = seletor.getSelectedFile().toPath().toAbsolutePath().normalize(); validarPastaRaiz(nova);
            pastaRaiz = nova; atualizarPastasDaRaiz(); armazenamento.setRoots(pastaUploads, pastaUsuarios);
            for (UserAccount conta : usuarios.list()) armazenamento.ensurePersonalRoot(conta);
            salvarPreferencias(); atualizarInterface();
            log("Pasta principal alterada para " + nova);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(janela, "Não foi possível usar essa pasta:\n" + e.getMessage(), "MagaDrop", JOptionPane.ERROR_MESSAGE);
        }
    }

    static boolean escolherPasta(JTextField destino) {
        String caminhoAtual = destino.getText().trim();
        JFileChooser seletor = caminhoAtual.isBlank() ? new JFileChooser(pastaRaiz.toFile()) : new JFileChooser(caminhoAtual);
        seletor.setDialogTitle("Escolha a pasta base do MagaDrop"); seletor.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        seletor.setAcceptAllFileFilterUsed(false);
        if (seletor.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return false;
        destino.setText(seletor.getSelectedFile().getAbsolutePath());
        return true;
    }

    static Path validarSelecaoPastaInicial(String caminho, boolean escolhidaExplicitamente) {
        if (!escolhidaExplicitamente || caminho == null || caminho.isBlank())
            throw new IllegalArgumentException("Escolha a pasta base usando o botão Escolher...");
        try { return Paths.get(caminho.trim()).toAbsolutePath().normalize(); }
        catch (InvalidPathException e) { throw new IllegalArgumentException("O caminho escolhido não é válido."); }
    }

    static void mostrarConfiguracoes() {
        JCheckBox iniciarWindows = new JCheckBox("Iniciar o MagaDrop junto com o Windows", PREFERENCIAS.getBoolean(CHAVE_INICIAR_WINDOWS, false));
        JButton alterarSenha = new JButton("Alterar senha do administrador"); JButton restaurarPasta = new JButton("Restaurar pasta padrão");
        alterarSenha.addActionListener(e -> alterarSenha());
        restaurarPasta.addActionListener(e -> {
            try {
                Path padrao = pastaPadrao(); validarPastaRaiz(padrao); pastaRaiz = padrao; atualizarPastasDaRaiz();
                armazenamento.setRoots(pastaUploads, pastaUsuarios);
                for (UserAccount conta : usuarios.list()) armazenamento.ensurePersonalRoot(conta);
                salvarPreferencias(); atualizarInterface();
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

    static void mostrarUsuarios() {
        JDialog dialogo = new JDialog(janela, "Usuários do MagaDrop", true);
        DefaultTableModel modelo = new DefaultTableModel(new Object[]{"Usuário", "Nome", "Perfil", "Estado", "Sessões"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable tabela = new JTable(modelo); tabela.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); tabela.setFillsViewportHeight(true);
        Runnable atualizar = () -> {
            String selecionado = tabela.getSelectedRow() >= 0 ? String.valueOf(tabela.getValueAt(tabela.getSelectedRow(), 0)) : null;
            modelo.setRowCount(0);
            for (UserAccount conta : usuarios.list()) modelo.addRow(new Object[]{conta.username(), conta.displayName(),
                    conta.role() == UserRole.ADMIN ? "Administrador" : "Membro", conta.enabled() ? "Ativa" : "Desativada",
                    sessoes.countForUser(conta.id())});
            if (selecionado != null) for (int i = 0; i < modelo.getRowCount(); i++)
                if (selecionado.equals(modelo.getValueAt(i, 0))) { tabela.setRowSelectionInterval(i, i); break; }
        };
        JButton criar = new JButton("Criar membro"), redefinir = new JButton("Redefinir senha");
        JButton alternar = new JButton("Ativar/desativar"), encerrar = new JButton("Encerrar sessões");
        JButton excluir = new JButton("Excluir membro"), fechar = new JButton("Fechar");
        excluir.setForeground(new Color(170, 30, 45));
        criar.addActionListener(e -> criarMembroDesktop(dialogo, atualizar));
        redefinir.addActionListener(e -> redefinirSenhaDesktop(dialogo, usuarioSelecionado(tabela), atualizar));
        alternar.addActionListener(e -> alternarUsuarioDesktop(dialogo, usuarioSelecionado(tabela), atualizar));
        encerrar.addActionListener(e -> encerrarSessoesDesktop(dialogo, usuarioSelecionado(tabela), atualizar));
        excluir.addActionListener(e -> excluirMembroDesktop(dialogo, usuarioSelecionado(tabela), atualizar));
        fechar.addActionListener(e -> dialogo.dispose());
        JPanel acoes = new JPanel(new GridLayout(2, 3, 6, 6));
        acoes.add(criar); acoes.add(redefinir); acoes.add(alternar); acoes.add(encerrar); acoes.add(excluir); acoes.add(fechar);
        JPanel conteudo = new JPanel(new BorderLayout(8, 8)); conteudo.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        conteudo.add(new JScrollPane(tabela), BorderLayout.CENTER); conteudo.add(acoes, BorderLayout.SOUTH);
        dialogo.setContentPane(conteudo); dialogo.setSize(760, 380); dialogo.setMinimumSize(new Dimension(680, 330));
        dialogo.setLocationRelativeTo(janela); atualizar.run(); dialogo.setVisible(true);
    }

    static UserAccount usuarioSelecionado(JTable tabela) {
        int linha = tabela.getSelectedRow();
        if (linha < 0) {
            JOptionPane.showMessageDialog(tabela, "Selecione um usuário primeiro.", "Usuários", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return usuarios.find(String.valueOf(tabela.getValueAt(linha, 0))).orElse(null);
    }

    static void criarMembroDesktop(Component parent, Runnable atualizar) {
        JTextField usuario = new JTextField(18), nome = new JTextField(18);
        JPasswordField senha = new JPasswordField(18), confirmar = new JPasswordField(18), senhaAdmin = new JPasswordField(18);
        JPanel painel = formulario(new String[]{"Nome de usuário:", "Nome de exibição:", "Senha inicial:", "Confirmar senha:", "Sua senha de administrador:"},
                new JComponent[]{usuario, nome, senha, confirmar, senhaAdmin});
        if (JOptionPane.showConfirmDialog(parent, painel, "Criar membro", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            String nova = new String(senha.getPassword());
            if (!nova.equals(new String(confirmar.getPassword()))) throw new IllegalArgumentException("As senhas não são iguais.");
            verificarAdministrador(new String(senhaAdmin.getPassword()));
            UserAccount criada = usuarios.createMember(usuario.getText(), nome.getText(), nova);
            armazenamento.ensurePersonalRoot(criada);
            atualizar.run(); log("Usuário criado no computador: " + criada.username());
        } catch (Exception e) { mostrarErroUsuario(parent, e); }
    }

    static void redefinirSenhaDesktop(Component parent, UserAccount conta, Runnable atualizar) {
        if (conta == null) return;
        if (conta.role() == UserRole.ADMIN) {
            JOptionPane.showMessageDialog(parent, "Altere a senha do administrador em Configurações.", "Usuários", JOptionPane.INFORMATION_MESSAGE); return;
        }
        JPasswordField senha = new JPasswordField(18), confirmar = new JPasswordField(18), senhaAdmin = new JPasswordField(18);
        JPanel painel = formulario(new String[]{"Nova senha para " + conta.username() + ":", "Confirmar senha:", "Sua senha de administrador:"},
                new JComponent[]{senha, confirmar, senhaAdmin});
        if (JOptionPane.showConfirmDialog(parent, painel, "Redefinir senha", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            String nova = new String(senha.getPassword());
            if (!nova.equals(new String(confirmar.getPassword()))) throw new IllegalArgumentException("As senhas não são iguais.");
            verificarAdministrador(new String(senhaAdmin.getPassword()));
            usuarios.changePassword(conta.username(), nova); sessoes.invalidateAllForUser(conta.id());
            atualizar.run(); log("Senha redefinida no computador para " + conta.username());
        } catch (Exception e) { mostrarErroUsuario(parent, e); }
    }

    static void alternarUsuarioDesktop(Component parent, UserAccount conta, Runnable atualizar) {
        if (conta == null) return;
        if (conta.role() == UserRole.ADMIN) {
            JOptionPane.showMessageDialog(parent, "A conta administradora principal não pode ser desativada.", "Usuários", JOptionPane.INFORMATION_MESSAGE); return;
        }
        String acao = conta.enabled() ? "desativar" : "ativar";
        JPasswordField senhaAdmin = new JPasswordField(18);
        JPanel painel = formulario(new String[]{"Confirme que deseja " + acao + " @" + conta.username() + ".", "Sua senha de administrador:"},
                new JComponent[]{new JLabel("As sessões serão encerradas."), senhaAdmin});
        if (JOptionPane.showConfirmDialog(parent, painel, Character.toUpperCase(acao.charAt(0)) + acao.substring(1) + " usuário",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            verificarAdministrador(new String(senhaAdmin.getPassword()));
            UserAccount alterada = usuarios.setEnabled(conta.username(), !conta.enabled());
            if (!alterada.enabled()) sessoes.invalidateAllForUser(conta.id());
            atualizar.run(); log("Usuário " + conta.username() + (alterada.enabled() ? " ativado" : " desativado"));
        } catch (Exception e) { mostrarErroUsuario(parent, e); }
    }

    static void encerrarSessoesDesktop(Component parent, UserAccount conta, Runnable atualizar) {
        if (conta == null) return;
        if (conta.role() == UserRole.ADMIN) {
            JOptionPane.showMessageDialog(parent, "Use Sair no navegador para encerrar a sessão do administrador.", "Usuários", JOptionPane.INFORMATION_MESSAGE); return;
        }
        JPasswordField senhaAdmin = new JPasswordField(18);
        JPanel painel = formulario(new String[]{"Encerrar todas as sessões de @" + conta.username() + "?", "Sua senha de administrador:"},
                new JComponent[]{new JLabel("O usuário precisará entrar novamente."), senhaAdmin});
        if (JOptionPane.showConfirmDialog(parent, painel, "Encerrar sessões", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            verificarAdministrador(new String(senhaAdmin.getPassword())); sessoes.invalidateAllForUser(conta.id());
            atualizar.run(); log("Sessões encerradas no computador para " + conta.username());
        } catch (Exception e) { mostrarErroUsuario(parent, e); }
    }

    static void excluirMembroDesktop(Component parent, UserAccount conta, Runnable atualizar) {
        if (conta == null) return;
        if (conta.role() == UserRole.ADMIN) {
            JOptionPane.showMessageDialog(parent, "A conta administradora principal não pode ser excluída.", "Usuários", JOptionPane.INFORMATION_MESSAGE); return;
        }
        JTextField confirmacao = new JTextField(18);
        JPasswordField senhaAdmin = new JPasswordField(18);
        JPanel painel = formulario(new String[]{"A conta @" + conta.username() + " será removida permanentemente.",
                        "Digite " + conta.username() + " para confirmar:", "Sua senha de administrador:"},
                new JComponent[]{new JLabel("Todas as sessões serão encerradas."), confirmacao, senhaAdmin});
        if (JOptionPane.showConfirmDialog(parent, painel, "Excluir membro", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            if (!conta.username().equals(confirmacao.getText().trim()))
                throw new IllegalArgumentException("O nome de usuário digitado não confere.");
            verificarAdministrador(new String(senhaAdmin.getPassword()));
            UserAccount excluida = usuarios.deleteMember(conta.username());
            sessoes.invalidateAllForUser(excluida.id());
            atualizar.run(); log("Usuário excluído no computador: " + excluida.username());
        } catch (Exception e) { mostrarErroUsuario(parent, e); }
    }

    static JPanel formulario(String[] labels, JComponent[] campos) {
        JPanel painel = new JPanel(new GridLayout(0, 2, 8, 8));
        for (int i = 0; i < labels.length; i++) { painel.add(new JLabel(labels[i])); painel.add(campos[i]); }
        return painel;
    }

    static void verificarAdministrador(String senha) {
        UserAccount admin = usuarios.initialAdmin();
        if (usuarios.authenticate(admin.username(), senha).isEmpty()) throw new IllegalArgumentException("Senha do administrador incorreta.");
    }

    static void mostrarErroUsuario(Component parent, Exception e) {
        JOptionPane.showMessageDialog(parent, e.getMessage(), "Não foi possível concluir", JOptionPane.WARNING_MESSAGE);
    }

    static void alterarSenha() {
        JPasswordField senha = new JPasswordField(18), confirmacao = new JPasswordField(18);
        JPanel painel = new JPanel(new GridLayout(0, 2, 8, 8));
        painel.add(new JLabel("Nova senha:")); painel.add(senha); painel.add(new JLabel("Confirmar senha:")); painel.add(confirmacao);
        while (JOptionPane.showConfirmDialog(janela, painel, "Alterar senha", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String nova = new String(senha.getPassword());
            try {
                if (!nova.equals(new String(confirmacao.getPassword()))) throw new IllegalArgumentException("As senhas não são iguais.");
                PasswordHasher.validateNewPassword(nova);
                UserAccount admin = usuarios.initialAdmin();
                usuarios.changePassword(admin.username(), nova);
                sessoes.invalidateAllForUser(admin.id());
                log("Senha do administrador alterada; sessões anteriores foram encerradas"); return;
            } catch (IllegalArgumentException | IOException e) {
                JOptionPane.showMessageDialog(janela, e.getMessage(), "Revise a senha", JOptionPane.WARNING_MESSAGE);
                senha.setText(""); confirmacao.setText("");
            }
        }
    }

    static void validarSenha(String senha) {
        PasswordHasher.validateNewPassword(senha);
    }

    static void validarPastaRaiz(Path pasta) throws IOException {
        Files.createDirectories(pasta);
        if (!Files.isDirectory(pasta)) throw new IOException("O caminho escolhido não é uma pasta.");
        Path teste = Files.createTempFile(pasta, ".magadrop-", ".tmp");
        Files.deleteIfExists(teste);
        Files.createDirectories(pasta.resolve("Compartilhada"));
        Files.createDirectories(pasta.resolve("Usuarios"));
    }

    static void salvarPreferencias() {
        PREFERENCIAS.put(CHAVE_PASTA_RAIZ, pastaRaiz.toString());
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
        enderecoLabel.setText(enderecoServidor()); pastaLabel.setText(pastaRaiz.toString()); pastaLabel.setToolTipText(pastaRaiz.toString());
        iniciarButton.setEnabled(!rodando); pararButton.setEnabled(rodando);
        if (usuarioLabel != null && usuarios != null) usuarioLabel.setText(usuarios.initialAdmin().displayName() + "  •  administrador");
        qrPanel.setConteudo(rodando ? enderecoServidor() : null);
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
            Path logoPath = pastaWeb.resolve("maga-logo.png");
            Path trayPath = Files.isRegularFile(logoPath) ? logoPath : Paths.get(baseDir, "file.ico");
            TrayIcon icon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(trayPath.toString()), "MagaDrop", menu);
            icon.setImageAutoSize(true); SystemTray.getSystemTray().add(icon);
        } catch (Exception e) { log("Não foi possível criar o ícone da bandeja"); }
    }

    static void responder(HttpExchange troca, int status, String mensagem) throws IOException {
        HttpSupport.sendText(troca, status, mensagem);
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
            HttpSupport.secureHeaders(troca); h.set("Cache-Control", "no-cache");
            h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'");
            long tamanho = Files.size(arquivo); troca.sendResponseHeaders(200, metodo.equals("HEAD") ? -1 : tamanho);
            if (metodo.equals("GET")) try (OutputStream out = troca.getResponseBody()) { Files.copy(arquivo, out); } else troca.close();
            log("Acesso de " + troca.getRemoteAddress().getAddress().getHostAddress());
        }
    }

    static class UploadHandler implements HttpHandler {
        public void handle(HttpExchange troca) throws IOException {
            if (!troca.getRequestMethod().equals("POST")) { troca.getResponseHeaders().set("Allow", "POST"); responder(troca, 405, "Método não permitido"); return; }
            Optional<SessionManager.Session> sessao = HttpSupport.session(troca, sessoes);
            if (sessao.isEmpty()) { responder(troca, 401, "Entre no MagaDrop para enviar arquivos"); return; }
            if (!HttpSupport.validCsrf(troca, sessao.get())) { responder(troca, 403, "Confirmação de segurança inválida"); return; }
            String nome = troca.getRequestHeaders().getFirst("X-Filename");
            try { StorageService.validateName(nome, true); }
            catch (IllegalArgumentException e) { responder(troca, 400, e.getMessage()); return; }
            long informado = parseLong(troca.getRequestHeaders().getFirst("Content-Length"));
            if (informado > LIMITE_UPLOAD) { responder(troca, 413, "Arquivo excede o limite de 2 GB"); return; }
            Path destino;
            StorageService.Area area;
            try {
                Map<String, String> query = HttpSupport.readQuery(troca);
                area = StorageService.Area.parse(query.getOrDefault("area", "shared"));
                destino = armazenamento.reserveUpload(sessao.get(), area, query.getOrDefault("path", ""), nome);
            } catch (NoSuchFileException e) { responder(troca, 404, "A pasta de destino não existe"); return; }
            catch (IllegalArgumentException | HttpSupport.InvalidRequestException e) { responder(troca, 400, e.getMessage()); return; }
            boolean concluido = false;
            try (InputStream in = troca.getRequestBody(); OutputStream out = Files.newOutputStream(destino)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0; int lidos;
                while ((lidos = in.read(buffer)) != -1) { total += lidos; if (total > LIMITE_UPLOAD) throw new UploadMuitoGrandeException(); out.write(buffer, 0, lidos); }
                concluido = true; log("Recebido por " + sessao.get().username() + " em " + area.apiName() + ": " + destino.getFileName() + " (" + total + " bytes)"); responder(troca, 201, destino.getFileName().toString());
            } catch (UploadMuitoGrandeException e) { responder(troca, 413, "Arquivo excede o limite de 2 GB"); }
            catch (IOException e) { log("Erro ao receber " + nome + ": " + e.getMessage()); try { responder(troca, 500, "Não foi possível salvar o arquivo"); } catch (IOException ignored) {} }
            finally { if (!concluido) Files.deleteIfExists(destino); }
        }
        private static long parseLong(String valor) { if (valor == null) return -1; try { return Long.parseLong(valor); } catch (NumberFormatException e) { return -1; } }
    }
    static class UploadMuitoGrandeException extends IOException { private static final long serialVersionUID = 1L; }
}
