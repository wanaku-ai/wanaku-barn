package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.BarnApiOptions;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import ai.wanaku.core.services.api.ValidationIssue;
import ai.wanaku.core.services.api.ValidationResult;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

@CommandLine.Command(
        name = "validate",
        description = "Validate a service catalog or service template without deploying it")
public class ServiceValidate extends BaseCommand {

    @CommandLine.Option(
            names = {"--path"},
            description = "Path to the service catalog directory",
            defaultValue = ".",
            arity = "0..1")
    private String path;

    @CommandLine.Mixin
    BarnApiOptions barn;

    @CommandLine.Option(
            names = {"--template"},
            description = "Validate as a service template instead of a catalog",
            defaultValue = "false")
    private boolean template;

    ServiceCatalogService catalogService;
    ServiceTemplateService templateService;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        File baseDir = new File(path);
        if (!baseDir.isDirectory()) {
            printer.printErrorMessage(String.format("'%s' is not a directory", path));
            return EXIT_ERROR;
        }

        byte[] zipBytes;
        try {
            zipBytes = ServicePackage.createZipArchive(baseDir);
        } catch (IOException e) {
            printer.printErrorMessage(String.format("Failed to create ZIP archive: %s", e.getMessage()));
            return EXIT_ERROR;
        }

        DataStore dataStore = new DataStore();
        dataStore.setName(baseDir.getAbsoluteFile().getName() + ".service.zip");
        dataStore.setData(Base64.getEncoder().encodeToString(zipBytes));

        ValidationResult result;
        try {
            WanakuResponse<ValidationResult> response;
            if (template) {
                templateService =
                        initAuthenticatedServiceIfNeeded(templateService, ServiceTemplateService.class, barn.baseUri());
                response = templateService.validate(dataStore);
            } else {
                catalogService =
                        initAuthenticatedServiceIfNeeded(catalogService, ServiceCatalogService.class, barn.baseUri());
                response = catalogService.validate(dataStore);
            }
            result = response.data();
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }

        String kind = template ? "Service template" : "Service catalog";
        String name = result.name() != null ? result.name() : path;

        printIssues(printer, result.errors(), true);
        printIssues(printer, result.warnings(), false);

        if (!result.valid()) {
            printer.printErrorMessage(String.format(
                    "%s '%s' is invalid: %d error(s), %d warning(s)",
                    kind, name, size(result.errors()), size(result.warnings())));
            return EXIT_ERROR;
        }

        printer.printSuccessMessage(
                String.format("%s '%s' is valid (%d warning(s))", kind, name, size(result.warnings())));
        return EXIT_OK;
    }

    private static void printIssues(WanakuPrinter printer, List<ValidationIssue> issues, boolean errors) {
        if (issues == null) {
            return;
        }
        for (ValidationIssue issue : issues) {
            String message = String.format("%s: %s", issue.path(), issue.message());
            if (errors) {
                printer.printErrorMessage(message);
            } else {
                printer.printWarningMessage(message);
            }
        }
    }

    private static int size(List<ValidationIssue> issues) {
        return issues == null ? 0 : issues.size();
    }
}
