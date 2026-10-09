package ai.wanaku.backend.api.v1.semanticrouter.model;

import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Eligible native Kamelet routing destination API contract. */
@Schema(name = "SemanticAction")
public class SemanticAction {
    @Schema(description = "Native metadata.name used as the Kamelet identifier")
    public String id;

    @Schema(description = "SHA-256 pin for the exact selected native Kamelet bytes; omitted on legacy selections")
    public String sha256;

    @Schema(description = "Whether this action revision is the current catalog selection")
    public boolean current;

    @Schema(
            description = "Native Kamelet type; sinks return a fixed acknowledgement after successful delivery",
            enumeration = {"action", "sink"})
    public String type;

    @Schema(description = "Native Kamelet title")
    public String name;

    @Schema(description = "Native Kamelet purpose")
    public String description;

    @Schema(description = "Suggested native routing criterion")
    public String criteria;

    @Schema(description = "Compatible invocation and result profile")
    public String profile;

    @Schema(description = "Native Kamelet input body schema")
    public Map<String, Object> inputSchema;

    @Schema(description = "Native Kamelet output body schema")
    public Map<String, Object> outputSchema;

    @Schema(description = "Native deployment configuration JSON Schema; secret fields accept external references only")
    public Map<String, Object> configurationSchema;

    @Schema(description = "Native Kamelet implementation dependencies")
    public List<String> dependencies;
}
