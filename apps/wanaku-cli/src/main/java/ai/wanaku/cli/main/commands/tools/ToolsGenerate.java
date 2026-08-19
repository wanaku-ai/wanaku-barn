package ai.wanaku.cli.main.commands.tools;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jline.terminal.Terminal;
import io.swagger.v3.oas.models.OpenAPI;
import ai.wanaku.capabilities.sdk.api.types.ToolReference;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.converter.URLConverter;
import ai.wanaku.cli.main.support.ToolsGenerateHelper;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ToolsGenerateHelper.determineBaseUrl;
import static ai.wanaku.cli.main.support.ToolsGenerateHelper.generateToolReferences;
import static ai.wanaku.cli.main.support.ToolsGenerateHelper.loadAndResolveOpenAPI;

@CommandLine.Command(name = "generate", description = "generate tools from an OpenApi specification")
public class ToolsGenerate extends BaseCommand {

    private static final Logger LOG = Logger.getLogger(ToolsGenerate.class);

    @CommandLine.Option(
            names = {"--server-url", "-u"},
            description = "Override the OpenAPI server base url",
            arity = "0..1")
    protected String serverUrl;

    @CommandLine.Option(
            names = {"--output-file", "-o"},
            description =
                    "path of the file where the generated tools will be written. If not set, the generated tools will be written to the STDOUT",
            arity = "0..1")
    private String outputFile;

    @CommandLine.Option(
            names = {"--server-index", "-i"},
            description = "index of the server in the server object list in the OpenApi Spec. 0 is the first one.",
            arity = "0..1")
    private Integer serverIndex;

    @CommandLine.Option(
            names = {"--server-variable", "-v"},
            description =
                    "key-value pair for server object variable, can be specified multiple times. Ex  '-v environment=prod -v region=us-east'",
            arity = "0..*")
    private Map<String, String> serverVariables = new HashMap<>();

    @CommandLine.Option(
            names = {"--label", "-l"},
            description =
                    "key-value pair for labels to be added to all generated tools, can be specified multiple times. Ex  '-l environment=prod -l team=backend'",
            arity = "0..*")
    private Map<String, String> labels = new HashMap<>();

    @CommandLine.Parameters(
            description = "location to the OpenAPI spec definition, can be a local path or an URL",
            arity = "1..1",
            converter = URLConverter.class)
    private URL specLocation;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        try {
            OpenAPI openAPI = loadAndResolveOpenAPI(specLocation.toString());

            if (openAPI == null
                    || openAPI.getPaths() == null
                    || openAPI.getPaths().isEmpty()) {
                LOG.warn("No paths found in the OpenAPI specification");
                return EXIT_ERROR;
            }
            String baseUrl = determineBaseUrl(openAPI, serverUrl, serverIndex, serverVariables);
            List<ToolReference> toolReferences = generateToolReferences(openAPI, baseUrl, labels);
            ToolsGenerateHelper.writeOutput(toolReferences, outputFile);
        } catch (Exception e) {
            System.err.println("Error processing OpenAPI specification: " + e.getMessage());
            return EXIT_ERROR;
        }
        return EXIT_OK;
    }
}
