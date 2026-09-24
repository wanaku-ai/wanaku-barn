package ai.wanaku.cli.main.commands.auth;

import java.nio.file.Path;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

import static ai.wanaku.cli.main.commands.BaseCommand.EXIT_ERROR;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthLoginTest {

    private static final String WANAKU_HOME = "wanaku.home";

    @TempDir
    Path tempDir;

    /**
     * Parses without {@code --password}: that option is interactive and would block reading stdin.
     */
    private static AuthLogin parse(String... args) {
        AuthLogin login = new AuthLogin();
        new CommandLine(login).parseArgs(args);
        return login;
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = AuthLogin.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = AuthLogin.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void defaultsTargetTheOauth2ProxyClient() throws Exception {
        AuthLogin login = parse("--api-token", "abc");

        assertEquals("wanaku-mcp-router", field(login, "clientId"));
        assertNull(field(login, "authServerUrl"), "auth server default is applied at call time");
        assertNull(field(login, "realm"), "realm default is applied at call time");
        assertEquals("http://localhost:8543", AuthLogin.DEFAULT_AUTH_SERVER);
        assertEquals("wanaku", AuthLogin.DEFAULT_REALM);
    }

    @Test
    void clientSecretIsBlankWhenNeitherOptionNorEnvIsGiven() throws Exception {
        AuthLogin login = parse("--api-token", "abc");

        Object secret = field(login, "clientSecret");
        assertTrue(secret == null || secret.toString().isBlank(), "unexpected secret: " + secret);
    }

    @Test
    void clientSecretOptionIsParsed() throws Exception {
        AuthLogin login = parse("--api-token", "abc", "--client-secret", "s3cr3t");

        assertEquals("s3cr3t", field(login, "clientSecret"));
    }

    @Test
    void failedLoginExplainsTheOauth2ProxyRequirements() throws Exception {
        String previousHome = System.getProperty(WANAKU_HOME);
        System.setProperty(WANAKU_HOME, tempDir.toString());
        try {
            assertFailedLoginHint();
        } finally {
            if (previousHome == null) {
                System.clearProperty(WANAKU_HOME);
            } else {
                System.setProperty(WANAKU_HOME, previousHome);
            }
        }
    }

    private void assertFailedLoginHint() throws Exception {
        AuthLogin login = new AuthLogin();
        login.authMode = new AuthLogin.AuthMode();
        login.authMode.credentials = new AuthLogin.UserPassCredentials();
        login.authMode.credentials.username = "alice";
        login.authMode.credentials.password = "pw";
        setField(login, "clientId", AuthLogin.DEFAULT_CLIENT_ID);
        // Nothing listens on this port, so discovery fails fast without touching a real Keycloak.
        setField(login, "authServerUrl", "http://127.0.0.1:1");

        WanakuPrinter printer = mock(WanakuPrinter.class);
        int result = login.doCall(mock(Terminal.class), printer);

        assertEquals(EXIT_ERROR, result);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(printer).printErrorMessage(message.capture());
        assertTrue(message.getValue().startsWith("Authentication failed:"), message.getValue());
        assertTrue(message.getValue().contains("Direct access grants"), message.getValue());
        assertTrue(message.getValue().contains("--client-secret"), message.getValue());
    }
}
