package ai.wanaku.cli.main.commands.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Collects a semantic route and the files its {@code semantic-router.properties} manifest references,
 * with the same layout rules as the Wanaku Semantic Router local-files mode: the manifest is next to the
 * route, and its references are relative to the directory that {@code main} is relative to.
 */
final class SemanticInput {
    static final String MANIFEST = "semantic-router.properties";
    private static final List<String> REQUIRED =
            List.of("main", "dependencies", "configuration", "kamelets", "tool.name", "expert.bean");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The main route reference, relative to the source root. */
    final String main;

    /** The tool name from the manifest. */
    final String toolName;

    /** Relative path to file content. */
    final Map<String, String> files;

    private SemanticInput(String main, String toolName, Map<String, String> files) {
        this.main = main;
        this.toolName = toolName;
        this.files = files;
    }

    static SemanticInput read(Path route) throws CloudRunSupport.CloudException {
        final Path file = route.toAbsolutePath().normalize();
        IntegrationInput.readFile(file);
        final Path manifestPath = file.resolveSibling(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) {
            throw IntegrationInput.invalid("%s needs %s in the same directory".formatted(route, MANIFEST));
        }
        final Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(manifestPath)) {
            manifest.load(in);
        } catch (IOException e) {
            throw IntegrationInput.invalid("Cannot read %s: %s".formatted(manifestPath, e.getMessage()));
        }
        for (String key : REQUIRED) {
            if (manifest.getProperty(key, "").isBlank()) {
                throw IntegrationInput.invalid("%s has no %s".formatted(manifestPath, key));
            }
        }

        final String main = manifest.getProperty("main").trim();
        Path root = file;
        for (int i = 0; i < Path.of(main).getNameCount(); i++) {
            root = root.getParent();
        }
        if (root == null || !root.resolve(main).normalize().equals(file)) {
            throw IntegrationInput.invalid("main=%s in %s does not name %s".formatted(main, manifestPath, route));
        }

        final Map<String, String> files = new TreeMap<>();
        add(root, file, files);
        add(root, manifestPath, files);
        add(root, contained(root, manifest.getProperty("dependencies").trim()), files);
        add(root, contained(root, manifest.getProperty("configuration").trim()), files);
        for (String reference : manifest.getProperty("kamelets").split(",")) {
            final Path kamelets = contained(root, reference.trim());
            if (Files.isDirectory(kamelets)) {
                try (var paths = Files.walk(kamelets)) {
                    for (Path entry : paths.filter(Files::isRegularFile).toList()) {
                        add(root, entry, files);
                    }
                } catch (IOException e) {
                    throw IntegrationInput.invalid("Cannot read %s: %s".formatted(kamelets, e.getMessage()));
                }
            } else {
                add(root, kamelets, files);
            }
        }

        final long size = files.values().stream().mapToLong(String::length).sum();
        if (size > CloudRunSupport.MAX_INPUT_BYTES) {
            throw IntegrationInput.invalid("The semantic route files are larger than %d KiB"
                    .formatted(CloudRunSupport.MAX_INPUT_BYTES / 1024));
        }
        try {
            final JsonNode routes = YAML.readTree(files.get(main));
            if (routes == null || !routes.isArray()) {
                throw IntegrationInput.invalid(
                        "%s is not a Camel YAML DSL file: the top level must be a list".formatted(route));
            }
        } catch (IOException e) {
            throw IntegrationInput.invalid("%s is not valid YAML: %s".formatted(route, e.getMessage()));
        }
        return new SemanticInput(main, manifest.getProperty("tool.name").trim(), files);
    }

    /** ConfigMap keys cannot hold '/', so each relative path maps to a key with "--" separators. */
    static String key(String relativePath) {
        return relativePath.replace("/", "--");
    }

    private static Path contained(Path root, String reference) throws CloudRunSupport.CloudException {
        final Path resolved = root.resolve(reference).normalize();
        if (!resolved.startsWith(root)) {
            throw IntegrationInput.invalid("%s points outside %s".formatted(reference, root));
        }
        if (!Files.exists(resolved)) {
            throw IntegrationInput.invalid("%s does not exist".formatted(resolved));
        }
        return resolved;
    }

    private static void add(Path root, Path file, Map<String, String> files) throws CloudRunSupport.CloudException {
        final String relative = root.relativize(file).toString().replace('\\', '/');
        if (!key(relative).matches("[-._a-zA-Z0-9]+")) {
            throw IntegrationInput.invalid(
                    "%s has characters that a ConfigMap key cannot hold: use letters, digits, '.', '_' and '-'"
                            .formatted(relative));
        }
        files.put(relative, IntegrationInput.readFile(file));
    }
}
