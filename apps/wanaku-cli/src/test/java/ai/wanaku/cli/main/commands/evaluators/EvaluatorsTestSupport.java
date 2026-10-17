package ai.wanaku.cli.main.commands.evaluators;

import java.util.List;
import java.util.Map;
import ai.wanaku.core.services.api.EvaluatorRevision;
import ai.wanaku.core.services.api.EvaluatorRevisionMetadata;

final class EvaluatorsTestSupport {

    private EvaluatorsTestSupport() {}

    static EvaluatorRevision revision(long id) {
        EvaluatorRevisionMetadata metadata = new EvaluatorRevisionMetadata();
        metadata.setId(id);
        metadata.setStatus("active");

        EvaluatorRevision revision = new EvaluatorRevision();
        revision.setRevision(metadata);
        revision.setEvaluators(List.of(Map.of(
                "name", "safety-gate",
                "trigger", Map.of("method", "tools/call", "namespace", "production"),
                "engine", Map.of("type", "passthrough"),
                "processor", Map.of("path", "/wasm/safety-gate.wasm"))));
        return revision;
    }
}
