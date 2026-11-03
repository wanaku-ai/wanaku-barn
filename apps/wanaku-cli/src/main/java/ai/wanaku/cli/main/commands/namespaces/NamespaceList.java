package ai.wanaku.cli.main.commands.namespaces;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jline.terminal.Terminal;
import io.quarkus.runtime.annotations.RegisterForReflection;
import ai.wanaku.capabilities.sdk.api.types.Namespace;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.NamespacesService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

@CommandLine.Command(name = "list", description = "List namespaces")
public class NamespaceList extends BaseCommand {
    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Option(
            names = {"--mcp-url"},
            description = "The base URL of the Wanaku Server MCP listener, used to build the namespace addresses",
            defaultValue = "http://localhost:8081",
            arity = "0..1")
    protected String mcpUrl;

    @CommandLine.Option(
            names = {"-e", "--label-expression"},
            description = {
                """
Filter namespaces by label expression. Supports logical operators for complex queries.
For detailed information see the label expression manual page:
`wanaku man label-expression`
Note: If omitted, all namespaces are listed. Label matching is case-sensitive.
"""
            })
    String labelExpression;

    NamespacesService namespacesService;

    @RegisterForReflection
    public static class NamespaceRow {
        private final String name;
        private final String address;
        private final Map<String, String> labels;

        public NamespaceRow(String name, String address, Map<String, String> labels) {
            this.name = name;
            this.address = address;
            this.labels = labels;
        }

        public String getName() {
            return name;
        }

        public String getAddress() {
            return address;
        }

        public Map<String, String> getLabels() {
            return labels;
        }
    }

    private NamespaceRow toRow(Namespace n) {
        String baseUrl = mcpUrl.endsWith("/") ? mcpUrl.substring(0, mcpUrl.length() - 1) : mcpUrl;
        String address = String.format("%s/%s/mcp", baseUrl, n.getName());
        return new NamespaceRow(n.getName(), address, n.getLabels());
    }

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        namespacesService = initAuthenticatedServiceIfNeeded(namespacesService, NamespacesService.class, host);

        try {
            WanakuResponse<List<Namespace>> response = namespacesService.list(labelExpression);
            List<NamespaceRow> list = response.data().stream().map(this::toRow).collect(Collectors.toList());

            printer.printTable(list, "name", "address", "labels");
        } catch (WebApplicationException ex) {
            Response response = ex.getResponse();
            commonResponseErrorHandler(response);
            return EXIT_ERROR;
        }
        return EXIT_OK;
    }
}
