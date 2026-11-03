package ai.wanaku.core.services.api;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for replacing the router's evaluator configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluatorsUpdateRequest {

    private List<Map<String, Object>> evaluators;

    @JsonProperty("expected_revision")
    private Long expectedRevision;

    public EvaluatorsUpdateRequest() {}

    public EvaluatorsUpdateRequest(List<Map<String, Object>> evaluators, Long expectedRevision) {
        this.evaluators = evaluators;
        this.expectedRevision = expectedRevision;
    }

    public List<Map<String, Object>> getEvaluators() {
        return evaluators;
    }

    public void setEvaluators(List<Map<String, Object>> evaluators) {
        this.evaluators = evaluators;
    }

    /** When set, the router rejects the update with 409 Conflict if the active revision differs. */
    public Long getExpectedRevision() {
        return expectedRevision;
    }

    public void setExpectedRevision(Long expectedRevision) {
        this.expectedRevision = expectedRevision;
    }
}
