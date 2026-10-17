package ai.wanaku.core.services.api;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An evaluator configuration revision: its metadata and the evaluator definitions it holds.
 * <p>
 * The definitions are kept as raw maps because the router owns their schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluatorRevision {

    private EvaluatorRevisionMetadata revision;
    private List<Map<String, Object>> evaluators;

    public EvaluatorRevisionMetadata getRevision() {
        return revision;
    }

    public void setRevision(EvaluatorRevisionMetadata revision) {
        this.revision = revision;
    }

    public List<Map<String, Object>> getEvaluators() {
        return evaluators;
    }

    public void setEvaluators(List<Map<String, Object>> evaluators) {
        this.evaluators = evaluators;
    }
}
