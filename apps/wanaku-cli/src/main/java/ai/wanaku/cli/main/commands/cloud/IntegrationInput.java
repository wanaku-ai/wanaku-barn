package ai.wanaku.cli.main.commands.cloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Checks a Camel YAML route file before submission: the Camel Integration Capability serves routes that
 * start from {@code ai-tool:<name>} and carry one of its MCP tags as MCP tools.
 */
final class IntegrationInput {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private IntegrationInput() {}

    /**
     * @param tags the MCP tags the capability serves (comma separated)
     * @return the file content
     * @throws CloudRunSupport.CloudException with exit code 2 when the file is not usable
     */
    static String read(Path file, String tags) throws CloudRunSupport.CloudException {
        final String content = readFile(file);
        final JsonNode root;
        try {
            root = YAML.readTree(content);
        } catch (IOException e) {
            throw invalid("%s is not valid YAML: %s".formatted(file, e.getMessage()));
        }
        if (root == null || !root.isArray()) {
            throw invalid("%s is not a Camel YAML DSL file: the top level must be a list".formatted(file));
        }
        final List<String> served =
                Arrays.stream(tags.split(",")).map(String::trim).toList();
        for (JsonNode element : root) {
            final JsonNode from = element.has("route") ? element.path("route").path("from") : element.path("from");
            if (from.path("uri").asText().startsWith("ai-tool:")
                    && hasTag(from.path("parameters").path("tags"), served)) {
                return content;
            }
        }
        throw invalid("%s has no MCP tool: a route must start from ai-tool:<name> and have one of the tags %s"
                .formatted(file, served));
    }

    static String readFile(Path file) throws CloudRunSupport.CloudException {
        try {
            if (!Files.isRegularFile(file)) {
                throw invalid("%s does not exist".formatted(file));
            }
            if (Files.size(file) > CloudRunSupport.MAX_INPUT_BYTES) {
                throw invalid("%s is larger than %d KiB".formatted(file, CloudRunSupport.MAX_INPUT_BYTES / 1024));
            }
            return Files.readString(file);
        } catch (IOException e) {
            throw invalid("Cannot read %s: %s".formatted(file, e.getMessage()));
        }
    }

    private static boolean hasTag(JsonNode tags, List<String> served) {
        return Arrays.stream(tags.asText().split(",")).map(String::trim).anyMatch(served::contains);
    }

    static CloudRunSupport.CloudException invalid(String message) {
        return new CloudRunSupport.CloudException(CloudRunSupport.EXIT_INVALID_INPUT, message);
    }
}
