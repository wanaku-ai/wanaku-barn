package ai.wanaku.backend.api.v1.semanticrouter.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Configured native Camel expert identifier API contract. */
@Schema(name = "SemanticExpert")
public class SemanticExpert {
    @Schema(description = "Configured expert catalog identifier")
    public String id;

    @Schema(description = "Configured expert display name")
    public String name;

    @Schema(description = "Named Camel expert instance configured externally by the deployment")
    public String bean;

    @Schema(description = "Maven group:artifact:version implementation dependency")
    public String dependency;

    @Schema(description = "Whether this contract exposes confidence controls")
    public boolean supportsConfidence;
}
