package ai.wanaku.backend.api.v1.kamelets.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Upload request for a native Kamelet definition. */
@Schema(name = "KameletUpload")
public class KameletUpload {
    @Schema(description = "Single native Kamelet YAML document, at most 1 MiB in UTF-8 bytes")
    @NotBlank @Size(max = 1048576) public String yaml;
}
