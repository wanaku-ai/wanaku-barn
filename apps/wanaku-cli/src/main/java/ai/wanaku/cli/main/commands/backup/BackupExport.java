package ai.wanaku.cli.main.commands.backup;

import jakarta.ws.rs.WebApplicationException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.BackupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

@CommandLine.Command(name = "export", description = "Export the Barn data to a JSON file")
public class BackupExport extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Option(
            names = {"--output"},
            description = "The file to write",
            required = true)
    private Path output;

    @CommandLine.Option(
            names = {"--no-audit"},
            description = "Do not include the audit events")
    private boolean noAudit;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        String response;
        try {
            response = initAuthenticatedService(BackupService.class, host).export(!noAudit);
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode archive = mapper.readTree(response).get("data");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(archive));
        printer.printSuccessMessage("Exported %d data store entries to %s"
                .formatted(archive.get("dataStores").size(), output));
        return EXIT_OK;
    }
}
