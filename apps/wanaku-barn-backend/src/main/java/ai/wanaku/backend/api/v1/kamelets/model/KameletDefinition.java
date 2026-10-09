package ai.wanaku.backend.api.v1.kamelets.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Metadata and exact native YAML for one Kamelet revision. */
@Schema(name = "KameletDefinition")
public class KameletDefinition extends KameletSummary {
    @Schema(description = "Original single-document UTF-8 Kamelet YAML, without normalization")
    public String yaml;
}
