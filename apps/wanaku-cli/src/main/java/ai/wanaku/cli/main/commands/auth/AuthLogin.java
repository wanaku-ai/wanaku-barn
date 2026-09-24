package ai.wanaku.cli.main.commands.auth;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.AuthCredentialStore;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.cli.main.support.security.CustomSecurityServiceConfig;
import ai.wanaku.cli.main.support.security.ServiceAuthenticator;
import ai.wanaku.cli.main.support.security.TokenEndpoint;
import picocli.CommandLine;

@CommandLine.Command(
        name = "login",
        description = "Authenticate against Keycloak (password grant, local development only) or store an API token")
public class AuthLogin extends BaseCommand {

    static final String DEFAULT_AUTH_SERVER = "http://localhost:8543";
    static final String DEFAULT_REALM = "wanaku";
    /**
     * The Keycloak client the oauth2-proxy instances in front of Wanaku are configured with. Tokens issued
     * to this client carry the audience the proxies accept; tokens from other clients (e.g. {@code admin-cli})
     * are rejected with 401/403.
     */
    static final String DEFAULT_CLIENT_ID = "wanaku-mcp-router";

    private static final String DEFAULT_AUTH_MODE = "token";

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
    AuthMode authMode;

    static class AuthMode {
        @CommandLine.Option(
                names = {"--api-token"},
                description = "API token for authentication")
        String apiToken;

        @CommandLine.ArgGroup(exclusive = false)
        UserPassCredentials credentials;
    }

    static class UserPassCredentials {
        @CommandLine.Option(
                names = {"--username"},
                required = true,
                description = "Username for authentication")
        String username;

        @CommandLine.Option(
                names = {"--password"},
                required = true,
                description = "Password for authentication",
                interactive = true)
        String password;
    }

    @CommandLine.Option(
            names = {"--auth-server"},
            description = "Keycloak base URL, or a full OIDC issuer URL (default: " + DEFAULT_AUTH_SERVER + ")")
    private String authServerUrl;

    @CommandLine.Option(
            names = {"--realm"},
            description = "Keycloak realm (default: " + DEFAULT_REALM
                    + "). Ignored when --auth-server already points at a realm.")
    private String realm;

    @CommandLine.Option(
            names = {"--client-id"},
            description = "OAuth2 client ID. Must be a client whose tokens the oauth2-proxy in front of Wanaku accepts "
                    + "(default: " + DEFAULT_CLIENT_ID + ")",
            defaultValue = DEFAULT_CLIENT_ID)
    private String clientId;

    @CommandLine.Option(
            names = {"--client-secret"},
            description = "OAuth2 client secret, required for confidential clients such as " + DEFAULT_CLIENT_ID
                    + " (env: WANAKU_CLIENT_SECRET)",
            defaultValue = "${env:WANAKU_CLIENT_SECRET}")
    private String clientSecret;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        AuthCredentialStore credentialStore = new AuthCredentialStore();

        if (authMode.apiToken != null) {
            credentialStore.storeApiToken(authMode.apiToken);
            credentialStore.storeAuthMode(DEFAULT_AUTH_MODE);

            if (authServerUrl != null) {
                credentialStore.storeAuthServerUrl(authServerUrl);
            }

            printer.printSuccessMessage("Successfully stored authentication credentials");
            return EXIT_OK;
        }

        String serverUrl = authServerUrl != null ? authServerUrl : DEFAULT_AUTH_SERVER;
        String effectiveRealm = realm != null ? realm.strip() : DEFAULT_REALM;
        if (effectiveRealm.isEmpty() || serverUrl.contains("/realms/")) {
            effectiveRealm = null;
        }

        try {
            printer.printInfoMessage("Authenticating with username and password...");
            CustomSecurityServiceConfig config = new CustomSecurityServiceConfig();
            config.setClientId(clientId);
            config.setSecret(clientSecret);
            config.setUsername(authMode.credentials.username);
            config.setPassword(authMode.credentials.password);
            config.setTokenEndpoint(TokenEndpoint.forDiscovery(serverUrl, effectiveRealm));
            ServiceAuthenticator serviceAuthenticator = new ServiceAuthenticator(config, insecure);

            credentialStore.storeApiToken(serviceAuthenticator.currentValidAccessToken());
            credentialStore.storeRefreshToken(serviceAuthenticator.currentValidRefreshToken());
            credentialStore.storeTokenExpiry(serviceAuthenticator.getTokenExpiryEpochSeconds());
            credentialStore.storeClientId(clientId);
            credentialStore.storeClientSecret(clientSecret);
            credentialStore.storeRealm(effectiveRealm);

            credentialStore.storeAuthMode(DEFAULT_AUTH_MODE);
            credentialStore.storeAuthServerUrl(serverUrl);

            printer.printSuccessMessage("Successfully authenticated and stored credentials");
            return EXIT_OK;
        } catch (Exception e) {
            printer.printErrorMessage("Authentication failed: " + e.getMessage() + "\n\n" + loginHint());
            return EXIT_ERROR;
        }
    }

    private String loginHint() {
        return "Check that:\n"
                + "  - --auth-server points at Keycloak (default " + DEFAULT_AUTH_SERVER + "), not at Wanaku\n"
                + "  - the realm '" + (realm != null ? realm : DEFAULT_REALM)
                + "' exists and the user was created in it\n"
                + "  - the client '" + clientId + "' has 'Direct access grants' enabled\n"
                + "  - a confidential client (such as " + DEFAULT_CLIENT_ID + ") is given its secret via "
                + "--client-secret or WANAKU_CLIENT_SECRET\n"
                + "Alternatively store a token obtained elsewhere with: wanaku auth login --api-token <token>";
    }
}
