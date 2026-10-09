package ai.wanaku.backend.api.v1.semanticrouter.model;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Immutable catalog publication API contract. */
@Schema(name = "SemanticPublication")
public class SemanticPublication {
    @Schema(description = "Persisted authoring definition identifier")
    public String definitionId;

    @Schema(description = "Public MCP tool name captured at publication; absent in legacy records", nullable = true)
    public String toolName;

    @Schema(
            description =
                    "Expert identity and implementation captured at publication without credentials; absent in legacy records",
            nullable = true)
    public SemanticExpert expert;

    @Schema(description = "Immutable revision derived from the generated catalog inputs")
    public String revision;

    @Schema(description = "Revision-specific existing Barn catalog name")
    public String catalogName;

    @Schema(description = "SHA-256 pin for the complete downloaded ZIP archive")
    public String sha256;

    @Schema(description = "Main Camel YAML path inside the archive")
    public String mainFile;

    @Schema(description = "Required Camel runtime version")
    public String camelVersion;

    @Schema(description = "Required fixed Camel snapshot timestamp when recorded", nullable = true)
    public String camelBuild;

    @Schema(description = "Existing Barn catalog download path returning a Base64 ZIP")
    public String downloadUrl;

    @Schema(description = "Instructions to select and start this revision in WSR")
    public List<String> deploymentInstructions;

    @Schema(description = "Publication state; does not assert runtime readiness or activation")
    public String status;
}
