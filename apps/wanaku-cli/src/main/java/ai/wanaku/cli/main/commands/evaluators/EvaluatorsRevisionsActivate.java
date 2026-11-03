package ai.wanaku.cli.main.commands.evaluators;

import jakarta.ws.rs.WebApplicationException;

import java.util.Map;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevision;
import ai.wanaku.core.services.api.EvaluatorsService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

/**
 * Activates the configuration of a prior revision (rollback). The router records it as a new revision.
 */
@CommandLine.Command(
        name = "activate",
        description = "Activate the configuration of a prior evaluator revision (rollback)")
public class EvaluatorsRevisionsActivate extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Parameters(description = "The ID of the revision to activate", arity = "1")
    long id;

    @CommandLine.Option(
            names = {"--expected-revision"},
            description = "Reject the activation if the active revision is not this one (optimistic concurrency)")
    Long expectedRevision;

    EvaluatorsService evaluatorsService;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        evaluatorsService = initAuthenticatedServiceIfNeeded(evaluatorsService, EvaluatorsService.class, host);

        Map<String, Long> request = expectedRevision == null ? Map.of() : Map.of("expected_revision", expectedRevision);

        try {
            WanakuResponse<EvaluatorRevision> response = evaluatorsService.activateRevision(id, request);
            EvaluatorRevision revision = response.data();
            printer.printSuccessMessage(String.format(
                    "Activated the configuration of revision %d as revision %d",
                    id, revision.getRevision().getId()));
            EvaluatorRevisionPrinter.print(printer, revision);
            return EXIT_OK;
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
    }
}
