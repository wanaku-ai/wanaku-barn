package ai.wanaku.cli.main.commands.evaluators;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevision;

/**
 * Prints an evaluator revision: its metadata followed by a summary of its evaluators.
 */
final class EvaluatorRevisionPrinter {

    static final String[] METADATA_COLUMNS = {"id", "status", "created_at", "activated_at", "origin", "checksum"};

    private EvaluatorRevisionPrinter() {}

    static void print(WanakuPrinter printer, EvaluatorRevision revision) {
        printer.printAsMap(revision.getRevision(), METADATA_COLUMNS);

        List<Map<String, Object>> evaluators = revision.getEvaluators();
        if (evaluators == null || evaluators.isEmpty()) {
            printer.printInfoMessage("No evaluators configured.");
            return;
        }

        List<Map<String, Object>> rows =
                evaluators.stream().map(EvaluatorRevisionPrinter::summarize).toList();
        printer.printTable(rows, "name", "method", "namespace", "engine");
    }

    static Map<String, Object> summarize(Map<String, Object> evaluator) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", evaluator.get("name"));
        row.put("method", nested(evaluator, "trigger", "method"));
        row.put("namespace", nested(evaluator, "trigger", "namespace"));
        row.put("engine", nested(evaluator, "engine", "type"));
        return row;
    }

    private static Object nested(Map<String, Object> evaluator, String parent, String key) {
        if (evaluator.get(parent) instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }
}
