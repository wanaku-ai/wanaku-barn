package ai.wanaku.backend.api.v1.semanticrouter;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Validates native semantic DSL with the exact WSR Camel build schema, independently of Barn dependencies. */
public final class SemanticYamlValidator {
    private static final Schema SCHEMA = load();

    private SemanticYamlValidator() {}

    private static Schema load() {
        try (InputStream input = SemanticYamlValidator.class.getResourceAsStream("/schema/semanticCamelYamlDsl.json")) {
            if (input == null) throw new IOException("Pinned WSR Camel schema is missing");
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
                    .getSchema(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load pinned WSR Camel schema", e);
        }
    }
    /** Returns native Camel schema errors for serialized YAML. */
    public static List<com.networknt.schema.Error> validate(byte[] yaml) throws IOException {
        var tree = new YAMLMapper().readTree(yaml);
        return SCHEMA.validate(new ObjectMapper().writeValueAsString(tree), InputFormat.JSON);
    }
}
