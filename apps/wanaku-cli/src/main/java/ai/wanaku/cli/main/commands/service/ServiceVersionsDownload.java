package ai.wanaku.cli.main.commands.service;

import jakarta.ws.rs.WebApplicationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.handleNotFound;

@CommandLine.Command(name = "download", description = "Download the package of one version as a ZIP file")
public class ServiceVersionsDownload extends BaseCommand {

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
            description = "The version to download",
            required = true)
    private long version;

    @CommandLine.Option(
            names = {"--output"},
            description = "The file to write (default: <name>-v<version>.service.zip)",
            arity = "0..1")
    private Path output;

    @CommandLine.Option(
            names = {"--template"},
            description = "Download a service template version instead of a catalog version")
    private boolean template;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        DataStore content;
        try {
            content = template
                    ? initAuthenticatedService(ServiceTemplateService.class, host)
                            .downloadVersion(name, version)
                            .data()
                    : initAuthenticatedService(ServiceCatalogService.class, host)
                            .downloadVersion(name, version)
                            .data();
        } catch (WebApplicationException ex) {
            return handleNotFound(ex, "Version " + version + " of", name, printer);
        }

        Path target = output != null ? output : Path.of("%s-v%d.service.zip".formatted(name, version));
        Files.write(target, Base64.getDecoder().decode(content.getData()));
        printer.printSuccessMessage("Wrote version %d of '%s' to %s".formatted(version, name, target));
        return EXIT_OK;
    }
}
