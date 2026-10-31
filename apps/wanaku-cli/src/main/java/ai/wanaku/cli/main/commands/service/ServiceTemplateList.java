package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.ServiceTemplateSummary;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.BarnApiOptions;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceTemplateService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

@CommandLine.Command(name = "list", description = "List all service templates")
public class ServiceTemplateList extends BaseCommand {

    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_DESCRIPTION = "description";

    @CommandLine.Mixin
    BarnApiOptions barn;

    @CommandLine.Option(
            names = {"--search"},
            description = "Optional search term to filter templates",
            arity = "0..1")
    private String search;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        ServiceTemplateService service = initAuthenticatedService(ServiceTemplateService.class, barn.baseUri());

        try {
            WanakuResponse<List<ServiceTemplateSummary>> response = service.list(search);
            List<ServiceTemplateSummary> templates = response.data();

            if (templates == null || templates.isEmpty()) {
                printer.printInfoMessage("No service templates found");
                return EXIT_OK;
            }

            printer.printInfoMessage("Available service templates:");
            printer.printTable(templates, COLUMN_NAME, COLUMN_DESCRIPTION);
        } catch (WebApplicationException ex) {
            Response response = ex.getResponse();
            commonResponseErrorHandler(response);
            return EXIT_ERROR;
        }

        return EXIT_OK;
    }
}
