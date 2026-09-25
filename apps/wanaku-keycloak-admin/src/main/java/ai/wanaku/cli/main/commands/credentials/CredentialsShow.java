package ai.wanaku.cli.main.commands.credentials;

import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import io.quarkus.runtime.annotations.RegisterForReflection;
import ai.wanaku.cli.main.commands.admin.BaseAdminCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.cli.main.support.keycloak.KeycloakAdminClient;
import picocli.CommandLine;

@CommandLine.Command(
        name = "show",
        description =
                "Show current secret for a service client; use --plain with --show-secret to capture only the secret")
public class CredentialsShow extends BaseAdminCommand {

    @RegisterForReflection
    public record ClientDetail(String clientId, String description, boolean enabled) {}

    @CommandLine.Option(
            names = {"--client-id"},
            description = "Client ID of the service client",
            required = true)
    private String clientId;

    @CommandLine.Option(
            names = {"--show-secret"},
            description = "Print client secret to stdout (use with caution; may leak into logs or shell history)")
    private boolean showSecret;

    public CredentialsShow() {
        super();
    }

    public CredentialsShow(KeycloakAdminClient adminClient) {
        super(adminClient);
    }

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        boolean secretOutput = plain && showSecret;
        try {
            KeycloakAdminClient client = createAdminClient();
            List<Map<String, Object>> clients = client.listClients(realm);

            Map<String, Object> matched = clients.stream()
                    .filter(c -> clientId.equals(stringVal(c.get("clientId"))))
                    .findFirst()
                    .orElse(null);

            if (matched == null) {
                String message = "Client '" + clientId + "' not found";
                if (secretOutput) {
                    System.err.println(message);
                } else {
                    printer.printErrorMessage(message);
                }
                return EXIT_ERROR;
            }

            if (secretOutput) {
                String secret = client.getClientSecret(realm, clientId);
                if (secret == null || secret.isBlank()) {
                    System.err.println("No secret found for client '" + clientId + "'");
                    return EXIT_ERROR;
                }
                System.out.println(secret);
                return EXIT_OK;
            }

            ClientDetail detail = new ClientDetail(
                    stringVal(matched.get("clientId")),
                    stringVal(matched.get("description")),
                    boolVal(matched.get("enabled")));
            printer.printTable(List.of(detail), "clientId", "description", "enabled");

            if (showSecret) {
                String secret = client.getClientSecret(realm, clientId);
                if (secret != null) {
                    printer.printInfoMessage("Client Secret: " + secret);
                } else {
                    printer.printWarningMessage("No secret found for client '" + clientId + "'");
                }
            } else {
                printer.printWarningMessage(
                        "Use --show-secret to display the client secret (use with caution; may leak into logs or shell history)");
            }
            return EXIT_OK;
        } catch (KeycloakAdminClient.KeycloakAdminException e) {
            if (secretOutput) {
                System.err.println(e.getMessage());
            } else {
                printer.printErrorMessage(e.getMessage());
            }
            return EXIT_ERROR;
        }
    }

    private static String stringVal(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static boolean boolVal(Object obj) {
        return obj instanceof Boolean b && b;
    }
}
