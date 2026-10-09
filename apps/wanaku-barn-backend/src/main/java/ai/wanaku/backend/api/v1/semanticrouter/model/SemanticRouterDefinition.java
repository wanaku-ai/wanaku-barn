package ai.wanaku.backend.api.v1.semanticrouter.model;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Editable semantic router draft API contract. */
@Schema(name = "SemanticRouterDefinition")
public class SemanticRouterDefinition {
    @Schema(description = "Persisted draft identifier, assigned by Barn")
    public String id;

    @Schema(
            description =
                    "Router name; start with an ASCII letter, then use ASCII letters, digits, hyphens, or underscores; no spaces; at most 120 characters; empty for an incomplete draft")
    @Pattern(
            regexp = "^([A-Za-z][A-Za-z0-9_-]*)?$",
            message = "Start with a letter and use only letters, digits, hyphens, or underscores; no spaces")
    @Size(max = 120) public String name;

    @Schema(description = "Business purpose and public MCP tool description")
    public String description;

    @Schema(
            description =
                    "Public MCP tool name; start with an ASCII letter, then use ASCII letters, digits, hyphens, or underscores; no spaces; at most 64 characters; empty for an incomplete draft")
    @Pattern(
            regexp = "^([A-Za-z][A-Za-z0-9_-]*)?$",
            message = "Start with a letter and use only letters, digits, hyphens, or underscores; no spaces")
    @Size(max = 64) public String toolName;

    @Schema(description = "Supported action input and output profile")
    public String profile;

    @Schema(description = "Configured expert catalog identifier")
    public String expertId;

    @Schema(description = "Invocation field supplied to native semantic evaluation")
    public String semanticInput;

    @Schema(description = "Plain text instructions for native choice evaluation")
    public String instructions;

    @Schema(description = "Criterion for the reserved no_match label")
    public String noMatchCriteria;

    @Schema(description = "Fixed eligible actions with deployment configuration and selection criteria")
    public List<SemanticActionSelection> actions;

    @Schema(description = "Saved classification examples and expected labels")
    public List<SemanticExample> examples;
}
