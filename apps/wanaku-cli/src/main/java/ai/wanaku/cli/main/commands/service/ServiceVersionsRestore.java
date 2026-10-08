package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;

import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.handleNotFound;

@CommandLine.Command(
        name = "restore",
        description = "Restore an earlier version. The restore creates a new version with the earlier content")
public class ServiceVersionsRestore extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Option(
            names = {"--name"},
            description = "Name of the service catalog or template",
            required = true,
            arity = "0..1")
    private String name;

    @CommandLine.Option(
            names = {"--version"},
            description = "The version to restore",
            required = true)
    private long version;

    @CommandLine.Option(
            names = {"--expected-version"},
            description = "Fail if the active version is not this version")
    private Long expectedVersion;

    @CommandLine.Option(
            names = {"--template"},
            description = "Restore a service template version instead of a catalog version")
    private boolean template;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        DataStore restored;
        try {
            restored = template
                    ? initAuthenticatedService(ServiceTemplateService.class, host)
                            .activateVersion(name, version, expectedVersion)
                            .data()
                    : initAuthenticatedService(ServiceCatalogService.class, host)
                            .activateVersion(name, version, expectedVersion)
                            .data();
        } catch (WebApplicationException ex) {
            return handleNotFound(ex, "Version " + version + " of", name, printer);
        }
        printer.printSuccessMessage("Restored version %d of '%s' as version %s"
                .formatted(version, name, restored.getLabels().get("wanaku.version")));
        return EXIT_OK;
    }
}
