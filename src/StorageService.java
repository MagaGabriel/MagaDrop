import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class StorageService {
    private static final String TRASH_DIRECTORY = ".magadrop-trash";
    private volatile Path sharedRoot;
    private final Path personalBase;

    enum Area {
        PERSONAL("personal"), SHARED("shared");
        private final String apiName;
        Area(String apiName) { this.apiName = apiName; }
        String apiName() { return apiName; }
        static Area parse(String value) {
            for (Area area : values()) if (area.apiName.equalsIgnoreCase(value == null ? "" : value)) return area;
            throw new IllegalArgumentException("Área de armazenamento inválida.");
        }
    }

    record Entry(String name, boolean directory, long size, long modifiedAtEpochMillis) {}

    StorageService(Path sharedRoot, Path personalBase) throws IOException {
        this.personalBase = personalBase.toAbsolutePath().normalize();
        setSharedRoot(sharedRoot);
        Files.createDirectories(this.personalBase);
    }

    synchronized void setSharedRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized))
            throw new IOException("A pasta compartilhada não é válida.");
        sharedRoot = normalized;
    }

    Path sharedRoot() { return sharedRoot; }

    List<Entry> list(SessionManager.Session session, Area area, String rawPath) throws IOException {
        Path directory = resolve(session, area, rawPath, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("O caminho não é uma pasta.");
        Path root = root(session, area);
        List<Entry> entries = new ArrayList<>();
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (child.getFileName().toString().equalsIgnoreCase(TRASH_DIRECTORY) || isLinkOrReparsePoint(child)) continue;
                try {
                    ensureInside(root, child);
                    BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() && !attributes.isRegularFile()) continue;
                    entries.add(new Entry(child.getFileName().toString(), attributes.isDirectory(),
                            attributes.isDirectory() ? 0 : attributes.size(), attributes.lastModifiedTime().toMillis()));
                } catch (IOException ignored) {}
            }
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    Path createFolder(SessionManager.Session session, Area area, String rawParent, String name) throws IOException {
        validateName(name, false);
        Path parent = resolve(session, area, rawParent, true);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("A pasta de destino não existe.");
        Path target = parent.resolve(name).normalize();
        ensureInside(parentRoot(session, area), target);
        return Files.createDirectory(target);
    }

    Path reserveUpload(SessionManager.Session session, Area area, String rawDirectory, String filename) throws IOException {
        validateName(filename, true);
        Path directory = resolve(session, area, rawDirectory, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("A pasta de destino não existe.");
        String base = filename, extension = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) { base = filename.substring(0, dot); extension = filename.substring(dot); }
        for (int i = 0; i < 10_000; i++) {
            String candidateName = i == 0 ? filename : base + " (" + i + ")" + extension;
            Path candidate = directory.resolve(candidateName).normalize();
            ensureInside(parentRoot(session, area), candidate);
            try { return Files.createFile(candidate); }
            catch (FileAlreadyExistsException ignored) {}
        }
        throw new IOException("Muitos arquivos com o mesmo nome.");
    }

    Path fileForDownload(SessionManager.Session session, Area area, String rawPath) throws IOException {
        Path file = resolve(session, area, rawPath, true);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("O item não é um arquivo.");
        return file;
    }

    Path moveToTrash(SessionManager.Session session, Area area, String rawPath) throws IOException {
        String normalized = normalizeRelativePath(rawPath);
        if (normalized.isEmpty()) throw new IllegalArgumentException("A pasta principal não pode ser excluída.");
        Path root = root(session, area);
        Path source = resolve(session, area, normalized, true);
        Path trash = root.resolve(TRASH_DIRECTORY).resolve(session.userId()).normalize();
        ensureInside(root, trash); Files.createDirectories(trash);
        String prefix = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String originalName = source.getFileName().toString();
        int available = Math.max(1, 240 - prefix.length());
        String destinationName = prefix + originalName.substring(0, Math.min(originalName.length(), available));
        Path destination = trash.resolve(destinationName);
        try { return Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { return Files.move(source, destination); }
    }

    String normalizedPath(String rawPath) { return normalizeRelativePath(rawPath); }

    private Path resolve(SessionManager.Session session, Area area, String rawPath, boolean mustExist) throws IOException {
        Path root = root(session, area);
        Path candidate = root.resolve(normalizeRelativePath(rawPath)).normalize();
        ensureInside(root, candidate);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (mustExist) throw new NoSuchFileException(candidate.toString());
            return candidate;
        }
        ensureNoLinks(root, candidate);
        return candidate;
    }

    private Path root(SessionManager.Session session, Area area) throws IOException {
        Path root = area == Area.SHARED ? sharedRoot : personalBase.resolve(personalDirectoryName(session));
        Files.createDirectories(root);
        return root.toAbsolutePath().normalize();
    }

    private Path parentRoot(SessionManager.Session session, Area area) throws IOException {
        return root(session, area);
    }

    private static String personalDirectoryName(SessionManager.Session session) {
        String id = session.userId().replaceAll("[^a-zA-Z0-9]", "");
        return session.username() + "-" + id.substring(0, Math.min(8, id.length()));
    }

    private static String normalizeRelativePath(String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.isEmpty()) return "";
        if (value.contains("\\") || value.startsWith("/") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Caminho inválido.");
        try {
            Path normalized = Paths.get(value).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")) throw new IllegalArgumentException("Caminho inválido.");
            for (Path part : normalized) {
                String item = part.toString();
                if (item.equals("..") || item.equalsIgnoreCase(TRASH_DIRECTORY))
                    throw new IllegalArgumentException("Caminho inválido.");
            }
            return normalized.toString().replace('\\', '/');
        } catch (InvalidPathException e) { throw new IllegalArgumentException("Caminho inválido."); }
    }

    static void validateName(String name, boolean file) {
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.equalsIgnoreCase(TRASH_DIRECTORY) || name.endsWith(".") || name.endsWith(" ")
                || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0))
            throw new IllegalArgumentException(file ? "Nome de arquivo inválido." : "Nome de pasta inválido.");
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String upper = stem.toUpperCase(Locale.ROOT);
        if (upper.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
            throw new IllegalArgumentException(file ? "Nome de arquivo reservado pelo Windows." : "Nome de pasta reservado pelo Windows.");
    }

    private static void ensureInside(Path root, Path candidate) {
        if (!candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("O caminho solicitado não é permitido.");
    }

    private static void ensureNoLinks(Path root, Path candidate) throws IOException {
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && isLinkOrReparsePoint(current))
                throw new IllegalArgumentException("Links e atalhos de pasta não são permitidos.");
        }
    }

    private static boolean isLinkOrReparsePoint(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        try {
            Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            return attributes instanceof Integer value && (value & 0x400) != 0;
        } catch (UnsupportedOperationException e) { return false; }
    }
}
