import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

// file parses authentication file

public final class AuthConfigParser {
    
    public static final class AuthConfig {
        public final String authType;
        public final String authName;
        public final String username;
        public final String password;

        public AuthConfig(String authType, String authName, String username, String password) {
            this.authType = authType;
            this.authName = authName;
            this.username = username;
            this.password = password;
        }
    }

    public static AuthConfig parse(Path filePath) throws IOException {
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

        String authType = null;
        String authName = null;
        String userBase64 = null;
        String passwordBase64 = null;

        for (String rawline: lines) {
            if (rawline == null) {
                continue;
            }
            String line = rawline.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+", 2);
            String key = parts[0].toLowerCase();

            String value = (parts.length > 1) ? parts[1].trim() : "";

            // only really applies to the authname one
            value = stripQuotes(value);

            switch (key) {
                case "authtype" -> authType = value;
                case "authname" -> authName = value;
                case "user" -> userBase64 = value;
                case "password" -> passwordBase64 = value;
                default -> {
                    // ignore other keys i don't know about
                }
            }
        }

        // basic checks
        if ((authType == null) || authType.isBlank()) {
            throw new IllegalArgumentException("Missing AuthType in .htaccess file");
        }
        if (!(authType.equalsIgnoreCase("Basic"))) {
            throw new IllegalArgumentException("Unsupported AuthType, only handling Basic right now");
        }

        if (authName == null) authName = "";
        if (userBase64 == null) throw new IllegalArgumentException("Missing User in .htaccess file");
        if (passwordBase64 == null) throw new IllegalArgumentException("Missing Password in .htaccess file");

        String username = base64DecodeToUtf8(userBase64);
        String password = base64DecodeToUtf8(passwordBase64);

        return new AuthConfig(authType, authName, username, password);
    }

    private static String stripQuotes(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static String base64DecodeToUtf8(String base64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64.trim());
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 value: " + base64, e);
        }
    }
}

