package ai.wanaku.backend.api.v1.semanticrouter.model;

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Fixed action selection and deployment configuration API contract. */
@Schema(name = "SemanticActionSelection")
public class SemanticActionSelection {
    @Schema(description = "Eligible native Kamelet identifier")
    public String actionId;

    @Schema(description = "SHA-256 pin for the exact selected native Kamelet bytes; omitted on legacy selections")
    public String sha256;

    @Schema(description = "Fixed unique semantic label; no_match is reserved")
    public String label;

    @Schema(description = "Plain text criterion for selecting this action")
    public String criteria;

    @Schema(description = "Deployment parameter values; credentials use env:VARIABLE_NAME references")
    public Map<String, String> configuration;
}
