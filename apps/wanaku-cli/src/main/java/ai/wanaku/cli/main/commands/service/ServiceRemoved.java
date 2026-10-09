package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;

import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

@CommandLine.Command(name = "removed", description = "List removed service catalogs or templates that can be restored")
public class ServiceRemoved extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Option(
            names = {"--template"},
            description = "List removed service templates instead of catalogs")
    private boolean template;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        try {
            List<Map<String, Object>> removed = template
                    ? initAuthenticatedService(ServiceTemplateService.class, host)
                            .removed()
                            .data()
                    : initAuthenticatedService(ServiceCatalogService.class, host)
                            .removed()
                            .data();
            if (removed == null || removed.isEmpty()) {
                printer.printInfoMessage(template ? "No removed service templates" : "No removed service catalogs");
                return EXIT_OK;
            }
            printer.printTable(removed, "name", "removedAt", "version");
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
        return EXIT_OK;
    }
}
