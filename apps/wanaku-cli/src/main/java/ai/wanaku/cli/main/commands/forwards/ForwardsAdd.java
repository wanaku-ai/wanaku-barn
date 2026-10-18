package ai.wanaku.cli.main.commands.forwards;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.ForwardReference;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.NamespaceOptions;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ForwardsService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

@Command(name = "add", description = "Add forward targets")
public class ForwardsAdd extends BaseCommand {

    @Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @Option(
            names = {"--name"},
            description = "The name of the service to forward",
            required = true,
            arity = "0..1")
    protected String name;

    @Option(
            names = {"--service"},
            description = "The service to forward",
            required = true,
            arity = "0..1")
    protected String service;

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    NamespaceOptions namespaceOptions;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        ForwardsService forwardsService = initAuthenticatedService(ForwardsService.class, host);

        ForwardReference reference = new ForwardReference();
        reference.setName(name);
        reference.setAddress(service);
        reference.setNamespace(namespaceOptions.getNamespaceValue());

        try {
            WanakuResponse<Map<String, Object>> response = forwardsService.addForward(reference);
            if (!printSummary(printer, name, response != null ? response.data() : null)) {
                return EXIT_ERROR;
            }
        } catch (WebApplicationException ex) {
            Response response = ex.getResponse();
            commonResponseErrorHandler(response);
            return EXIT_ERROR;
        }
        return EXIT_OK;
    }

    /**
     * Prints the outcome of the forward registration. The forward is stored even when the
     * discovery fails, so an unavailable forward is reported as a warning with its status message.
     *
     * @return false if the forward was stored as unavailable
     */
    static boolean printSummary(WanakuPrinter printer, String name, Map<String, Object> result) {
        if (result == null) {
            printer.printSuccessMessage(String.format("Successfully added forward '%s'", name));
            return true;
        }

        if (result.get("forward") instanceof Map<?, ?> forward && Boolean.FALSE.equals(forward.get("available"))) {
            Object statusMessage = forward.get("statusMessage");
            printer.printWarningMessage(String.format(
                    "Forward '%s' was added, but it is unavailable%s",
                    name, statusMessage != null ? ": " + statusMessage : ""));
            return false;
        }

        printer.printSuccessMessage(String.format(
                "Successfully added forward '%s': discovered %s tools, %s resources and %s prompts",
                name,
                count(result, "tools_discovered"),
                count(result, "resources_discovered"),
                count(result, "prompts_discovered")));
        return true;
    }

    private static Object count(Map<String, Object> result, String key) {
        Object value = result.get(key);
        return value != null ? value : 0;
    }
}
