package ai.wanaku.backend.api.v1.semanticrouter.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Published deployment selection resolved from an exact saved router name. */
@Schema(name = "SemanticResolvedPublication")
public class SemanticResolvedPublication {
    @Schema(description = "Exact current saved router name used for lookup")
    public String name;

    @Schema(description = "Public MCP tool name in the selected immutable archive")
    public String toolName;

    @Schema(description = "Revision-specific Barn catalog index name")
    public String catalogName;

    @Schema(description = "Selected service identifier in the catalog index")
    public String service;

    @Schema(description = "Immutable published revision")
    public String revision;

    @Schema(description = "SHA-256 pin for the complete downloaded ZIP")
    public String sha256;

    @Schema(description = "Main Camel YAML path inside the archive")
    public String mainFile;

    @Schema(description = "Required Camel runtime version")
    public String camelVersion;

    @Schema(description = "Required fixed Camel snapshot timestamp when recorded", nullable = true)
    public String camelBuild;

    @Schema(description = "Existing Barn catalog download path returning a Base64 ZIP")
    public String downloadUrl;

    @Schema(
            description =
                    "Published expert snapshot; absent when a legacy archive cannot prove one unique curated expert",
            nullable = true)
    public SemanticExpert expert;
}
