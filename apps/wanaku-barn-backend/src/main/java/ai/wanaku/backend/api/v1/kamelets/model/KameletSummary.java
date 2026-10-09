package ai.wanaku.backend.api.v1.kamelets.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Metadata for one native Kamelet revision. */
@Schema(name = "KameletSummary")
public class KameletSummary {
    @Schema(description = "Native Kamelet metadata.name")
    public String name;

    @Schema(description = "Native definition title")
    public String title;

    @Schema(description = "Native definition description")
    public String description;

    @Schema(
            description = "Native Kamelet type",
            enumeration = {"source", "sink", "action"})
    public String type;

    @Schema(description = "SHA-256 of the exact UTF-8 YAML bytes")
    public String sha256;

    @Schema(description = "Whether this revision is eligible for semantic routing")
    public boolean semanticEligible;

    @Schema(description = "Reason this revision is not eligible; null for an eligible action", nullable = true)
    public String semanticEligibilityReason;

    @Schema(
            description = "Catalog source",
            enumeration = {"bundled", "configured", "uploaded"})
    public String source;

    @Schema(description = "Whether the current catalog selection can be removed")
    public boolean removable;

    @Schema(description = "Raw YAML download path pinned to this exact revision")
    public String downloadUrl;

    /** Creates an empty response model. */
    public KameletSummary() {}

    /** Copies metadata without copying definition contents.
     * @param summary source metadata */
    public KameletSummary(KameletSummary summary) {
        name = summary.name;
        title = summary.title;
        description = summary.description;
        type = summary.type;
        sha256 = summary.sha256;
        semanticEligible = summary.semanticEligible;
        semanticEligibilityReason = summary.semanticEligibilityReason;
        source = summary.source;
        removable = summary.removable;
        downloadUrl = summary.downloadUrl;
    }
}
