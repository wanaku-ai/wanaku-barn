package ai.wanaku.cli.main.commands.evaluators;

import jakarta.ws.rs.WebApplicationException;

import java.util.List;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevisionMetadata;
import ai.wanaku.core.services.api.EvaluatorsService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

/**
 * Lists the evaluator configuration revisions, newest first.
 */
@CommandLine.Command(name = "list", description = "List evaluator configuration revisions (newest first)")
public class EvaluatorsRevisionsList extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    EvaluatorsService evaluatorsService;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        evaluatorsService = initAuthenticatedServiceIfNeeded(evaluatorsService, EvaluatorsService.class, host);

        try {
            WanakuResponse<List<EvaluatorRevisionMetadata>> response = evaluatorsService.listRevisions();
            List<EvaluatorRevisionMetadata> revisions = response.data();

            if (revisions == null || revisions.isEmpty()) {
                printer.printInfoMessage("No evaluator revisions found.");
                return EXIT_OK;
            }

            printer.printTable(revisions, EvaluatorRevisionPrinter.METADATA_COLUMNS);
            return EXIT_OK;
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
    }
}
