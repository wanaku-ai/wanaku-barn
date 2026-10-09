package ai.wanaku.backend.api.v1.semanticrouter.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Structural validation diagnostic API contract. */
@Schema(name = "SemanticFieldError")
public class SemanticFieldError {
    @Schema(description = "Definition field or configuration property with a validation error")
    public String field;

    @Schema(description = "Actionable structural validation error")
    public String message;
}
