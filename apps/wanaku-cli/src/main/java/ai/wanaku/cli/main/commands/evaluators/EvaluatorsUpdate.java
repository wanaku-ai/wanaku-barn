package ai.wanaku.cli.main.commands.evaluators;

import jakarta.ws.rs.WebApplicationException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevision;
import ai.wanaku.core.services.api.EvaluatorsService;
import ai.wanaku.core.services.api.EvaluatorsUpdateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine;

import static ai.wanaku.cli.main.support.ResponseHelper.commonResponseErrorHandler;

/**
 * Replaces the router's active evaluator configuration with the evaluators read from a file.
 * <p>
 * The file is YAML or JSON. It holds either a list of evaluator definitions or an object with
 * an {@code evaluators} list, such as a {@code wanaku.yaml} file. Other top-level keys are ignored.
 * </p>
 */
@CommandLine.Command(name = "update", description = "Replace the active evaluator configuration from a file")
public class EvaluatorsUpdate extends BaseCommand {

    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    // SnakeYAML rejects tab indentation, so JSON files are read with a plain JSON mapper
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @CommandLine.Option(
            names = {"--host"},
            description = "The API host",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    protected String host;

    @CommandLine.Parameters(
            description =
                    "YAML or JSON file with the evaluator definitions (a list, or an object with an 'evaluators' key)",
            arity = "1")
    Path file;

    @CommandLine.Option(
            names = {"--expected-revision"},
            description = "Reject the update if the active revision is not this one (optimistic concurrency)")
    Long expectedRevision;

    EvaluatorsService evaluatorsService;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        List<Map<String, Object>> evaluators;
        try {
            evaluators = readEvaluators(file);
        } catch (IOException | IllegalArgumentException e) {
            printer.printErrorMessage(String.format("Failed to read evaluators from '%s': %s", file, e.getMessage()));
            return EXIT_ERROR;
        }

        evaluatorsService = initAuthenticatedServiceIfNeeded(evaluatorsService, EvaluatorsService.class, host);

        try {
            WanakuResponse<EvaluatorRevision> response =
                    evaluatorsService.update(new EvaluatorsUpdateRequest(evaluators, expectedRevision));
            EvaluatorRevision revision = response.data();
            printer.printSuccessMessage(String.format(
                    "Evaluator configuration updated: revision %d is active",
                    revision.getRevision().getId()));
            EvaluatorRevisionPrinter.print(printer, revision);
            return EXIT_OK;
        } catch (WebApplicationException ex) {
            commonResponseErrorHandler(ex.getResponse());
            return EXIT_ERROR;
        }
    }

    static List<Map<String, Object>> readEvaluators(Path file) throws IOException {
        ObjectMapper mapper =
                file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json") ? JSON_MAPPER : YAML_MAPPER;
        JsonNode root = mapper.readTree(file.toFile());
        JsonNode evaluators = root != null && root.isObject() ? root.get("evaluators") : root;
        if (evaluators == null || !evaluators.isArray()) {
            throw new IllegalArgumentException("expected a list of evaluators or an object with an 'evaluators' list");
        }
        return mapper.convertValue(evaluators, new TypeReference<>() {});
    }
}
