package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;

import java.util.List;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.CatalogVersion;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.handleNotFound;

@CommandLine.Command(name = "list", description = "List the versions of a service catalog or template")
public class ServiceVersionsList extends BaseCommand {

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
            names = {"--template"},
            description = "List the versions of a service template instead of a catalog")
    private boolean template;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        try {
            WanakuResponse<List<CatalogVersion>> response = template
                    ? initAuthenticatedService(ServiceTemplateService.class, host)
                            .versions(name)
                    : initAuthenticatedService(ServiceCatalogService.class, host)
                            .versions(name);
            printer.printTable(response.data(), "version", "status", "createdAt", "origin", "restoredFrom", "checksum");
        } catch (WebApplicationException ex) {
            return handleNotFound(ex, template ? "Service template" : "Service catalog", name, printer);
        }
        return EXIT_OK;
    }
}
