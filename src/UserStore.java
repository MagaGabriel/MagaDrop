import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

final class UserStore {
    private static final String FORMAT_VERSION = "1";
    private final Path file;
    private final String dummyPasswordHash = PasswordHasher.hash(UUID.randomUUID().toString());
    private final Map<String, UserAccount> byUsername = new LinkedHashMap<>();

    UserStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        load();
    }

    synchronized boolean isEmpty() {
        return byUsername.isEmpty();
    }

    synchronized int size() {
        return byUsername.size();
    }

    synchronized Optional<UserAccount> find(String username) {
        try { return Optional.ofNullable(byUsername.get(UserAccount.normalizeUsername(username))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    synchronized Optional<UserAccount> authenticate(String username, String password) {
        Optional<UserAccount> account = find(username);
        String protectedPassword = account.filter(UserAccount::enabled).map(UserAccount::passwordHash).orElse(dummyPasswordHash);
        boolean valid = PasswordHasher.verify(password, protectedPassword);
        return valid && account.isPresent() && account.get().enabled() ? account : Optional.empty();
    }

    synchronized List<UserAccount> list() {
        return byUsername.values().stream().sorted(Comparator.comparing(UserAccount::username)).toList();
    }

    synchronized UserAccount createInitialAdmin(String username, String displayName, String password) throws IOException {
        if (!byUsername.isEmpty()) throw new IllegalStateException("A conta inicial já foi criada.");
        return create(username, displayName, password, UserRole.ADMIN, false);
    }

    synchronized UserAccount createMember(String username, String displayName, String password) throws IOException {
        PasswordHasher.validateNewPassword(password);
        return create(username, displayName, password, UserRole.MEMBER, true);
    }

    synchronized void changePassword(String username, String newPassword) throws IOException {
        PasswordHasher.validateNewPassword(newPassword);
        UserAccount current = find(username).orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        UserAccount updated = new UserAccount(current.id(), current.username(), current.displayName(), PasswordHasher.hash(newPassword),
                current.role(), current.enabled(), current.createdAtEpochMillis());
        byUsername.put(updated.username(), updated);
        try { save(); }
        catch (IOException e) { byUsername.put(current.username(), current); throw e; }
    }

    synchronized UserAccount setEnabled(String username, boolean enabled) throws IOException {
        UserAccount current = find(username).orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        if (current.role() == UserRole.ADMIN && !enabled)
            throw new IllegalArgumentException("A conta administradora principal não pode ser desativada.");
        if (current.enabled() == enabled) return current;
        UserAccount updated = new UserAccount(current.id(), current.username(), current.displayName(), current.passwordHash(),
                current.role(), enabled, current.createdAtEpochMillis());
        byUsername.put(updated.username(), updated);
        try { save(); }
        catch (IOException e) { byUsername.put(current.username(), current); throw e; }
        return updated;
    }

    synchronized UserAccount deleteMember(String username) throws IOException {
        UserAccount current = find(username).orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        if (current.role() == UserRole.ADMIN)
            throw new IllegalArgumentException("A conta administradora principal não pode ser excluída.");
        byUsername.remove(current.username());
        try { save(); }
        catch (IOException e) { byUsername.put(current.username(), current); throw e; }
        return current;
    }

    synchronized UserAccount initialAdmin() {
        return byUsername.values().stream().filter(u -> u.role() == UserRole.ADMIN).findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhum administrador foi configurado."));
    }

    Path file() {
        return file;
    }

    private UserAccount create(String username, String displayName, String password, UserRole role, boolean enforceNewPasswordPolicy) throws IOException {
        String normalized = UserAccount.normalizeUsername(username);
        UserAccount.validateDisplayName(displayName);
        if (enforceNewPasswordPolicy) PasswordHasher.validateNewPassword(password);
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("A senha não pode estar vazia.");
        if (byUsername.containsKey(normalized)) throw new IllegalArgumentException("Esse nome de usuário já existe.");
        UserAccount account = new UserAccount(UUID.randomUUID().toString(), normalized, displayName.trim(), PasswordHasher.hash(password),
                role, true, System.currentTimeMillis());
        byUsername.put(normalized, account);
        try { save(); }
        catch (IOException e) { byUsername.remove(normalized); throw e; }
        return account;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (InputStream input = Files.newInputStream(file)) { data.load(input); }
        if (!FORMAT_VERSION.equals(data.getProperty("format"))) throw new IOException("Formato do cadastro de usuários não reconhecido.");
        int count;
        try { count = Integer.parseInt(data.getProperty("users", "0")); }
        catch (NumberFormatException e) { throw new IOException("Cadastro de usuários corrompido.", e); }
        Map<String, UserAccount> loaded = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String prefix = "user." + i + ".";
            try {
                UserAccount account = new UserAccount(
                        required(data, prefix + "id"), required(data, prefix + "username"), required(data, prefix + "displayName"),
                        required(data, prefix + "passwordHash"), UserRole.valueOf(required(data, prefix + "role")),
                        Boolean.parseBoolean(required(data, prefix + "enabled")), Long.parseLong(required(data, prefix + "createdAt")));
                if (loaded.putIfAbsent(account.username(), account) != null) throw new IOException("Há usuários duplicados no cadastro.");
            } catch (IllegalArgumentException e) {
                throw new IOException("Cadastro de usuários corrompido.", e);
            }
        }
        byUsername.putAll(loaded);
    }

    private static String required(Properties data, String key) throws IOException {
        String value = data.getProperty(key);
        if (value == null) throw new IOException("Campo ausente no cadastro de usuários: " + key);
        return value;
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties data = new Properties();
        data.setProperty("format", FORMAT_VERSION);
        List<UserAccount> accounts = new ArrayList<>(byUsername.values());
        accounts.sort(Comparator.comparing(UserAccount::username));
        data.setProperty("users", Integer.toString(accounts.size()));
        for (int i = 0; i < accounts.size(); i++) {
            UserAccount account = accounts.get(i);
            String prefix = "user." + i + ".";
            data.setProperty(prefix + "id", account.id());
            data.setProperty(prefix + "username", account.username());
            data.setProperty(prefix + "displayName", account.displayName());
            data.setProperty(prefix + "passwordHash", account.passwordHash());
            data.setProperty(prefix + "role", account.role().name());
            data.setProperty(prefix + "enabled", Boolean.toString(account.enabled()));
            data.setProperty(prefix + "createdAt", Long.toString(account.createdAtEpochMillis()));
        }
        Path temporary = Files.createTempFile(file.getParent(), ".magadrop-users-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { data.store(output, "MagaDrop local users - password hashes only"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
