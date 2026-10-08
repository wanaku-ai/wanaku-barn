package ai.wanaku.cli.main.commands.backup;

import jakarta.ws.rs.WebApplicationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.BackupService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

@CommandLine.Command(name = "import", description = "Import the Barn data from a JSON file that an export created")
public class BackupImport extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Option(
            names = {"--input"},
            description = "The file to read",
            required = true)
    private Path input;

    @CommandLine.Option(
            names = {"--replace"},
            description = "Replace the existing data. Without this option, the import requires an empty Barn")
    private boolean replace;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        String archive = Files.readString(input);
        try {
            Map<String, Integer> imported = initAuthenticatedService(BackupService.class, host)
                    .importArchive(replace, archive)
                    .data();
            printer.printSuccessMessage("Imported %s".formatted(imported));
        } catch (WebApplicationException ex) {
            if (ex.getResponse().getStatus() == 409) {
                printer.printErrorMessage("Barn already has data. Use --replace to replace it");
                return EXIT_ERROR;
            }
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
        return EXIT_OK;
    }
}
