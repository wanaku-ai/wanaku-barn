package ai.wanaku.cli.main.commands.auth;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.support.AuthCredentialStore;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.cli.main.support.security.TokenRefresher;
import ai.wanaku.cli.main.support.security.TokenRefresher.RefreshResult;
import picocli.CommandLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockitoAnnotations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisabledOnOs(OS.WINDOWS)
class AuthTokenTest {

    @TempDir
    Path tempDir;

    private AuthCredentialStore credentialStore;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Path credentialsFile = tempDir.resolve("test-credentials");
        URI credentialsUri = credentialsFile.toUri();
        credentialStore = new AuthCredentialStore(credentialsUri);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"missing", "expired", "refresh-failure", "success", "refresh-success", "insecure", "masked"})
    void tokenRetrievalKeepsStdoutMachineReadable(String scenario) throws Exception {
        TokenRefresher refresher = mock(TokenRefresher.class);
        boolean expired = scenario.equals("expired") || scenario.startsWith("refresh-");
        if (!scenario.equals("missing")) {
            credentialStore.storeApiToken("test-token");
            credentialStore.storeTokenExpiry(Instant.now().getEpochSecond() + (expired ? -60 : 300));
        }
        if (scenario.startsWith("refresh-")) {
            credentialStore.storeRefreshToken("refresh-token");
            credentialStore.storeAuthServerUrl("http://localhost:8080");
            if (scenario.equals("refresh-failure")) {
                when(refresher.refresh("refresh-token", "http://localhost:8080", "admin-cli", null, null))
                        .thenThrow(new TokenRefresher.TokenRefreshException("Refresh failed"));
            } else {
                when(refresher.refresh("refresh-token", "http://localhost:8080", "admin-cli", null, null))
                        .thenReturn(new RefreshResult(
                                "test-token", "refresh-token", Instant.now().getEpochSecond() + 300));
            }
        }

        AuthToken command = new AuthToken(credentialStore, refresher);
        CommandLine commandLine = new CommandLine(command);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exitCode;
        try (PrintStream out = new PrintStream(stdout);
                PrintStream err = new PrintStream(stderr)) {
            System.setOut(out);
            System.setErr(err);
            String[] args =
                    switch (scenario) {
                        case "insecure" -> new String[] {"--get", "--unmask", "--plain", "--insecure"};
                        case "masked" -> new String[] {"--get", "--plain"};
                        default -> new String[] {"--get", "--unmask", "--plain"};
                    };
            exitCode = commandLine.execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        boolean success = scenario.equals("success")
                || scenario.equals("refresh-success")
                || scenario.equals("insecure")
                || scenario.equals("masked");
        assertEquals(success ? 0 : 1, exitCode);
        String expectedOutput = scenario.equals("masked") ? "Current API token: test***oken\r\n" : "test-token\n";
        assertEquals(success ? expectedOutput : "", stdout.toString());
        String expectedError = success ? "" : "No valid API token is available. Run 'wanaku auth login' to log in.\n";
        if (scenario.equals("refresh-failure")) {
            expectedError = "Token refresh failed: Refresh failed\n" + expectedError;
        } else if (scenario.equals("insecure")) {
            expectedError =
                    "WARNING: TLS certificate verification is disabled. This is insecure and should only be used for development.\n";
        }
        assertEquals(expectedError, stderr.toString().replace("\r\n", "\n"));
    }

    @Test
    void shouldRefreshExpiredTokenOnGet() throws Exception {
        String oldToken = "old-expired-token";
        String newToken = "new-refreshed-token";
        String refreshToken = "my-refresh-token";
        String authServerUrl = "http://localhost:8080";
        String clientId = "admin-cli";

        credentialStore.storeApiToken(oldToken);
        credentialStore.storeRefreshToken(refreshToken);
        credentialStore.storeAuthServerUrl(authServerUrl);
        credentialStore.storeClientId(clientId);
        credentialStore.storeAuthMode("token");
        // Token expired 60 seconds ago
        credentialStore.storeTokenExpiry(Instant.now().getEpochSecond() - 60);

        TokenRefresher mockRefresher = mock(TokenRefresher.class);
        long newExpiry = Instant.now().getEpochSecond() + 300;
        when(mockRefresher.refresh(refreshToken, authServerUrl, clientId, null, null))
                .thenReturn(new RefreshResult(newToken, refreshToken, newExpiry));

        AuthToken authToken = new AuthToken(credentialStore, mockRefresher);
        authToken.operation = new AuthToken.TokenOperation();
        authToken.operation.getOptions = new AuthToken.GetOptions();
        authToken.operation.getOptions.getToken = true;
        authToken.operation.getOptions.unmask = true;

        WanakuPrinter.setPlainMode(true);
        try (Terminal terminal = WanakuPrinter.terminalInstance()) {
            WanakuPrinter printer = new WanakuPrinter(null, terminal);
            authToken.doCall(terminal, printer);
        } finally {
            WanakuPrinter.setPlainMode(false);
        }

        verify(mockRefresher).refresh(refreshToken, authServerUrl, clientId, null, null);
        assertEquals(newToken, credentialStore.getApiToken());
        assertEquals(newExpiry, credentialStore.getTokenExpiry());
    }

    @Test
    void shouldNotRefreshValidTokenOnGet() throws Exception {
        String token = "valid-token";

        credentialStore.storeApiToken(token);
        credentialStore.storeAuthMode("token");
        // Token expires in 5 minutes
        credentialStore.storeTokenExpiry(Instant.now().getEpochSecond() + 300);

        TokenRefresher mockRefresher = mock(TokenRefresher.class);

        AuthToken authToken = new AuthToken(credentialStore, mockRefresher);
        authToken.operation = new AuthToken.TokenOperation();
        authToken.operation.getOptions = new AuthToken.GetOptions();
        authToken.operation.getOptions.getToken = true;
        authToken.operation.getOptions.unmask = true;

        WanakuPrinter.setPlainMode(true);
        try (Terminal terminal = WanakuPrinter.terminalInstance()) {
            WanakuPrinter printer = new WanakuPrinter(null, terminal);
            authToken.doCall(terminal, printer);
        } finally {
            WanakuPrinter.setPlainMode(false);
        }

        verify(mockRefresher, never()).refresh(any(), any(), any(), any(), any());
        assertEquals(token, credentialStore.getApiToken());
    }

    @Test
    void shouldReturnNullWhenRefreshFails() throws Exception {
        String oldToken = "old-token";
        String refreshToken = "my-refresh-token";
        String authServerUrl = "http://localhost:8080";
        String clientId = "admin-cli";

        credentialStore.storeApiToken(oldToken);
        credentialStore.storeRefreshToken(refreshToken);
        credentialStore.storeAuthServerUrl(authServerUrl);
        credentialStore.storeClientId(clientId);
        credentialStore.storeAuthMode("token");
        // Token expired
        credentialStore.storeTokenExpiry(Instant.now().getEpochSecond() - 60);

        TokenRefresher mockRefresher = mock(TokenRefresher.class);
        when(mockRefresher.refresh(refreshToken, authServerUrl, clientId, null, null))
                .thenThrow(new TokenRefresher.TokenRefreshException("Refresh failed"));

        AuthToken authToken = new AuthToken(credentialStore, mockRefresher);
        authToken.operation = new AuthToken.TokenOperation();
        authToken.operation.getOptions = new AuthToken.GetOptions();
        authToken.operation.getOptions.getToken = true;
        authToken.operation.getOptions.unmask = true;

        WanakuPrinter.setPlainMode(true);
        try (Terminal terminal = WanakuPrinter.terminalInstance()) {
            WanakuPrinter printer = new WanakuPrinter(null, terminal);
            Integer exitCode = authToken.doCall(terminal, printer);
            assertEquals(1, exitCode);
        } finally {
            WanakuPrinter.setPlainMode(false);
        }

        // The expired token is still in the credential store (not cleared), but
        // Retrieval fails without returning the expired token.
        assertEquals(oldToken, credentialStore.getApiToken());
    }

    @Test
    void shouldRefreshTokenWithRealmOnGet() throws Exception {
        String oldToken = "old-token";
        String newToken = "new-token";
        String refreshToken = "my-refresh-token";
        String authServerUrl = "http://keycloak-host";
        String clientId = "admin-cli";
        String realm = "wanaku";

        credentialStore.storeApiToken(oldToken);
        credentialStore.storeRefreshToken(refreshToken);
        credentialStore.storeAuthServerUrl(authServerUrl);
        credentialStore.storeClientId(clientId);
        credentialStore.storeRealm(realm);
        credentialStore.storeAuthMode("token");
        // Token expired
        credentialStore.storeTokenExpiry(Instant.now().getEpochSecond() - 60);

        TokenRefresher mockRefresher = mock(TokenRefresher.class);
        long newExpiry = Instant.now().getEpochSecond() + 300;
        when(mockRefresher.refresh(refreshToken, authServerUrl, clientId, null, realm))
                .thenReturn(new RefreshResult(newToken, refreshToken, newExpiry));

        AuthToken authToken = new AuthToken(credentialStore, mockRefresher);
        authToken.operation = new AuthToken.TokenOperation();
        authToken.operation.getOptions = new AuthToken.GetOptions();
        authToken.operation.getOptions.getToken = true;
        authToken.operation.getOptions.unmask = true;

        WanakuPrinter.setPlainMode(true);
        try (Terminal terminal = WanakuPrinter.terminalInstance()) {
            WanakuPrinter printer = new WanakuPrinter(null, terminal);
            authToken.doCall(terminal, printer);
        } finally {
            WanakuPrinter.setPlainMode(false);
        }

        verify(mockRefresher).refresh(refreshToken, authServerUrl, clientId, null, realm);
        assertEquals(newToken, credentialStore.getApiToken());
    }

    @Test
    void shouldNotRefreshWhenNoExpiryStored() throws Exception {
        String token = "legacy-token";

        credentialStore.storeApiToken(token);
        credentialStore.storeAuthMode("token");
        // No expiry stored (legacy token)

        TokenRefresher mockRefresher = mock(TokenRefresher.class);

        AuthToken authToken = new AuthToken(credentialStore, mockRefresher);
        authToken.operation = new AuthToken.TokenOperation();
        authToken.operation.getOptions = new AuthToken.GetOptions();
        authToken.operation.getOptions.getToken = true;
        authToken.operation.getOptions.unmask = true;

        WanakuPrinter.setPlainMode(true);
        try (Terminal terminal = WanakuPrinter.terminalInstance()) {
            WanakuPrinter printer = new WanakuPrinter(null, terminal);
            authToken.doCall(terminal, printer);
        } finally {
            WanakuPrinter.setPlainMode(false);
        }

        verify(mockRefresher, never()).refresh(any(), any(), any(), any(), any());
        assertEquals(token, credentialStore.getApiToken());
    }

    @Test
    void shouldRefreshTokenAboutToExpire() throws Exception {
        String oldToken = "old-token";
        String newToken = "new-token";
        String refreshToken = "my-refresh-token";
        String authServerUrl = "http://localhost:8080";
        String clientId = "admin-cli";

        credentialStore.storeApiToken(oldToken);
        credentialStore.storeRefreshToken(refreshToken);
        credentialStore.storeAuthServerUrl(authServerUrl);
        credentialStore.storeClientId(clientId);
        credentialStore.storeAuthMode("token");
        // Token expires in 20 seconds (within 30 second buffer)
        credentialStore.storeTokenExpiry(Instant.now().getEpochSecond() + 20);

        TokenRefresher mockRefresher = mock(TokenRefresher.class);
        long newExpiry = Instant.now().getEpochSecond() + 300;
        when(mockRefresher.refresh(refreshToken, authServerUrl, clientId, null, null))
                .thenReturn(new RefreshResult(newToken, refreshToken, newExpiry));

        AuthToken authToken = new AuthToken(credentialStore, mockRefresher);
        authToken.operation = new AuthToken.TokenOperation();
        authToken.operation.getOptions = new AuthToken.GetOptions();
        authToken.operation.getOptions.getToken = true;
        authToken.operation.getOptions.unmask = true;

        WanakuPrinter.setPlainMode(true);
        try (Terminal terminal = WanakuPrinter.terminalInstance()) {
            WanakuPrinter printer = new WanakuPrinter(null, terminal);
            authToken.doCall(terminal, printer);
        } finally {
            WanakuPrinter.setPlainMode(false);
        }

        verify(mockRefresher).refresh(refreshToken, authServerUrl, clientId, null, null);
        assertEquals(newToken, credentialStore.getApiToken());
    }
}
