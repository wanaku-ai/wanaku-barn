package ai.wanaku.cli.main.commands.evaluators;

import jakarta.ws.rs.WebApplicationException;

import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevision;
import ai.wanaku.core.services.api.EvaluatorsService;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

/**
 * Shows one evaluator configuration revision.
 */
@CommandLine.Command(name = "get", description = "Show an evaluator configuration revision")
public class EvaluatorsRevisionsGet extends BaseCommand {

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Parameters(description = "The revision ID", arity = "1")
    long id;

    EvaluatorsService evaluatorsService;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        evaluatorsService = initAuthenticatedServiceIfNeeded(evaluatorsService, EvaluatorsService.class, host);

        try {
            WanakuResponse<EvaluatorRevision> response = evaluatorsService.getRevision(id);
            EvaluatorRevisionPrinter.print(printer, response.data());
            return EXIT_OK;
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
    }
}
