package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigDefaultTest {
    @Test
    void packagedConfigEnforcesAuthenticationByDefault() throws IOException {
        try (var stream = AuthConfigDefaultTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "packaged config.yml");
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(McRemote.DEFAULT_AUTH_ENFORCEMENT);
            assertEquals("true", authEnforcementValue(config));
        }
    }

    private static String authEnforcementValue(String config) {
        boolean inAuth = false;
        for (String line : config.lines().toList()) {
            if (line.equals("auth:")) {
                inAuth = true;
                continue;
            }
            if (inAuth && !line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            if (inAuth && line.stripLeading().startsWith("enforcement:")) {
                return line.stripLeading()
                        .substring("enforcement:".length())
                        .split("#", 2)[0]
                        .trim();
            }
        }
        throw new AssertionError("auth.enforcement is missing from config.yml");
    }
}
