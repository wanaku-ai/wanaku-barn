package ai.wanaku.backend.api.v1.semanticrouter.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Classification-only request API contract. */
@Schema(name = "SemanticPreviewRequest")
public class SemanticPreviewRequest {
    @Schema(description = "Example invocation message to classify without action dispatch")
    @NotBlank @Size(max = 8192) public String message;
}
