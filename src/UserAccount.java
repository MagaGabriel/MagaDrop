import java.text.Normalizer;
import java.util.Locale;

enum UserRole {
    ADMIN,
    MEMBER
}

record UserAccount(
        String id,
        String username,
        String displayName,
        String passwordHash,
        UserRole role,
        boolean enabled,
        long createdAtEpochMillis) {

    UserAccount {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Identificador de usuário ausente.");
        username = normalizeUsername(username);
        validateDisplayName(displayName);
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("Senha protegida ausente.");
        if (role == null) throw new IllegalArgumentException("Perfil de usuário ausente.");
    }

    static String normalizeUsername(String value) {
        if (value == null) throw new IllegalArgumentException("Informe o nome de usuário.");
        String normalized = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,31}"))
            throw new IllegalArgumentException("Use de 3 a 32 caracteres no usuário: letras sem acento, números, ponto, _ ou -.");
        return normalized;
    }

    static void validateDisplayName(String value) {
        if (value == null || value.isBlank() || value.length() > 80 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Informe um nome de exibição com até 80 caracteres.");
    }
}
