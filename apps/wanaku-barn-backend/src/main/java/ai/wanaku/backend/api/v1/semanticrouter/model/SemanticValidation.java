package ai.wanaku.backend.api.v1.semanticrouter.model;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Structural validation result API contract. */
@Schema(name = "SemanticValidation")
public class SemanticValidation {
    @Schema(description = "True when all structural checks pass without inference")
    public boolean valid;

    @Schema(description = "Structural field errors; empty for a valid definition")
    public List<SemanticFieldError> errors;
}
