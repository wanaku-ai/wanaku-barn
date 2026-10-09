package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.handleNotFound;

@CommandLine.Command(name = "restore", description = "Restore a removed service catalog or template")
public class ServiceRestore extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Option(
            names = {"--name"},
            description = "Name of the removed service catalog or template",
            required = true,
            arity = "0..1")
    private String name;

    @CommandLine.Option(
            names = {"--template"},
            description = "Restore a service template instead of a catalog")
    private boolean template;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        try {
            if (template) {
                initAuthenticatedService(ServiceTemplateService.class, host).restore(name);
            } else {
                initAuthenticatedService(ServiceCatalogService.class, host).restore(name);
            }
        } catch (WebApplicationException ex) {
            return handleNotFound(ex, template ? "Removed service template" : "Removed service catalog", name, printer);
        }
        printer.printSuccessMessage("Restored '%s'".formatted(name));
        return EXIT_OK;
    }
}
