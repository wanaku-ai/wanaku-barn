package ai.wanaku.backend.api.v1.semanticrouter.model;

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Classification-only result API contract. */
@Schema(name = "SemanticPreview")
public class SemanticPreview {
    @Schema(description = "Selected fixed action label, or no_match; null on evaluation failure", nullable = true)
    public String label;

    @Schema(description = "True only for the explicit no_match label")
    public boolean noMatch;

    @Schema(description = "Evaluation wall-clock duration in milliseconds")
    public long durationMillis;

    @Schema(description = "Sanitized evaluation error; null after successful classification", nullable = true)
    public String error;

    @Schema(description = "Only available native confidence and known-label probabilities; no fabricated values")
    public Map<String, Object> diagnostics;
}
