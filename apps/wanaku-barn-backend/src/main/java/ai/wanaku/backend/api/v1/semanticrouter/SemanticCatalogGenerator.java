package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticActionSelection;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.core.services.api.ServiceCatalogIndex;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/** Serializes native Camel YAML and existing Barn catalog fields reproducibly. */
@ApplicationScoped
public class SemanticCatalogGenerator {
    public static final String CAMEL_VERSION = "4.23.0-SNAPSHOT";
    public static final String CAMEL_BUILD = "20261006.103638";
    public static final String MAIN = "service/router.camel.yaml";

    @Inject
    SemanticActionCatalog catalog;

    /** Generates a byte-identical ZIP for identical definitions and revisions. */
    public byte[] generate(SemanticRouterDefinition definition, String revision, String catalogName) {
        try {
            Map<String, byte[]> entries = new TreeMap<>();
            List<String> kamelets = new ArrayList<>();
            TreeSet<String> dependencies = new TreeSet<>(
                    List.of("camel:core", "camel:direct", "camel:kamelet", "camel:semantic", "camel:ai-tool"));
            dependencies.add("mvn:" + catalog.expert(definition.expertId).dependency);
            Map<String, String> configuration = new TreeMap<>();
            for (SemanticActionSelection selection : definition.actions) {
                String path = "service/kamelets/" + selection.actionId + ".kamelet.yaml";
                byte[] resource = catalog.resource(selection);
                byte[] previous = entries.putIfAbsent(path, resource);
                if (previous != null && !java.util.Arrays.equals(previous, resource))
                    throw new IllegalArgumentException("Conflicting action resource " + path);
                if (!kamelets.contains(path)) kamelets.add(path);
                dependencies.addAll(catalog.action(selection).dependencies);
                for (Map.Entry<String, String> value :
                        effectiveConfiguration(selection).entrySet()) {
                    String configured = value.getValue();
                    configuration.put(
                            "action." + selection.label + "." + value.getKey(),
                            secretReference(selection, value.getKey())
                                    ? "{{env:" + configured.substring(4) + "}}"
                                    : configured);
                }
            }
            YAMLMapper yaml = YAMLMapper.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build();
            entries.put(MAIN, yaml.writeValueAsBytes(routes(definition, false)));
            entries.put("service/preview.camel.yaml", yaml.writeValueAsBytes(routes(definition, true)));
            entries.put("service/dependencies.txt", bytes(String.join("\n", dependencies) + "\n"));
            entries.put("service/service.properties", properties(configuration));
            Map<String, String> manifest = new TreeMap<>();
            manifest.put("contract.version", "1");
            manifest.put("catalog.revision", revision);
            manifest.put("camel.version", CAMEL_VERSION);
            manifest.put("camel.build", CAMEL_BUILD);
            manifest.put("main", MAIN);
            manifest.put("preview.main", "service/preview.camel.yaml");
            manifest.put("input.profile", definition.profile);
            manifest.put("tool.name", definition.toolName);
            manifest.put("tool.tags", "wsr-semantic-router");
            manifest.put("expert.bean", catalog.expert(definition.expertId).bean);
            manifest.put("question", "department");
            manifest.put("kamelets", String.join(",", kamelets));
            manifest.put("dependencies", "service/dependencies.txt");
            manifest.put("configuration", "service/service.properties");
            entries.put("service/semantic-router.properties", properties(manifest));
            entries.put(
                    "index.properties",
                    properties(Map.of(
                            "catalog.name",
                            catalogName,
                            "catalog.description",
                            definition.description,
                            "catalog.services",
                            "service",
                            "catalog.routes.service",
                            MAIN,
                            "catalog.dependencies.service",
                            "service/dependencies.txt",
                            "catalog.properties.service",
                            "service/service.properties")));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    ZipEntry item = new ZipEntry(entry.getKey());
                    item.setTime(0);
                    zip.putNextEntry(item);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            byte[] result = bytes.toByteArray();
            ServiceCatalogIndex.fromZipBytes(result);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot generate semantic catalog", e);
        }
    }

    /** Uses the same native semantic declaration for classification and production dispatch. */
    List<Object> routes(SemanticRouterDefinition definition, boolean preview) {
        Map<String, String> criteria = criteria(definition);
        Map<String, Object> question = map(
                "type",
                "choice",
                "state",
                "${body}",
                "instructions",
                definition.instructions,
                "expert",
                catalog.expert(definition.expertId).bean,
                "criteria",
                criteria);
        List<Object> routes = new ArrayList<>();
        routes.add(map("semantic", map("question", map("department", question))));
        routes.add(route(
                "router-classification",
                "direct:classify-router",
                List.of(map(
                        "setProperty",
                        map(
                                "name",
                                "department",
                                "expression",
                                map("language", map("language", "semantic", "expression", "ref:department")))))));
        if (preview) return routes;
        List<Object> branches = new ArrayList<>();
        for (SemanticActionSelection selection : definition.actions) {
            Map<String, Object> parameters = new TreeMap<>();
            for (String key : effectiveConfiguration(selection).keySet())
                parameters.put(key, "{{action." + selection.label + "." + key + "}}");
            List<Object> steps = new ArrayList<>();
            steps.add(map("to", map("uri", "kamelet:" + selection.actionId, "parameters", parameters)));
            if ("sink".equals(catalog.action(selection).type))
                steps.add(map("setBody", map("constant", "Routed to " + selection.label + ".")));
            branches.add(map("simple", "${exchangeProperty.department} == '" + selection.label + "'", "steps", steps));
        }
        branches.add(map(
                "simple",
                "${exchangeProperty.department} == 'no_match'",
                "steps",
                List.of(map("setBody", map("constant", "No matching action.")))));
        List<Object> dispatch = List.of(
                map("to", "direct:classify-router"),
                map(
                        "choice",
                        map(
                                "when",
                                branches,
                                "otherwise",
                                map(
                                        "steps",
                                        List.of(map(
                                                "throwException",
                                                map(
                                                        "exceptionType",
                                                        "java.lang.IllegalStateException",
                                                        "message",
                                                        "Semantic evaluation returned an invalid label")))))));
        routes.add(route("router-dispatch", "direct:dispatch-router", dispatch));
        routes.add(map(
                "route",
                map(
                        "id",
                        "router-mcp-entry",
                        "from",
                        map(
                                "uri",
                                "ai-tool:" + definition.toolName,
                                "parameters",
                                map(
                                        "description",
                                        definition.description,
                                        "tags",
                                        "wsr-semantic-router",
                                        "parameter.message",
                                        "string",
                                        "parameter.message.required",
                                        "true",
                                        "parameter.message.description",
                                        "The message to classify and route"),
                                "steps",
                                List.of(
                                        map("setBody", map("simple", "${header.message}")),
                                        map("to", "direct:dispatch-router"))))));
        return routes;
    }

    /** Returns fixed labels and criteria shared with the isolated native preview service. */
    static Map<String, String> criteria(SemanticRouterDefinition definition) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SemanticActionSelection action : definition.actions) result.put(action.label, action.criteria);
        result.put("no_match", definition.noMatchCriteria);
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean secretReference(SemanticActionSelection action, String property) {
        Map<String, Object> properties =
                (Map<String, Object>) catalog.action(action).configurationSchema.getOrDefault("properties", Map.of());
        return Boolean.TRUE.equals(((Map<String, Object>) properties.get(property)).get("x-secret-reference"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> effectiveConfiguration(SemanticActionSelection action) {
        Map<String, String> values = new TreeMap<>();
        Map<String, Object> properties =
                (Map<String, Object>) catalog.action(action).configurationSchema.getOrDefault("properties", Map.of());
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            Object fallback = ((Map<String, Object>) property.getValue()).get("default");
            if (fallback != null) values.put(property.getKey(), fallback.toString());
        }
        if (action.configuration != null) values.putAll(action.configuration);
        return values;
    }

    private static Object route(String id, String uri, List<Object> steps) {
        return map("route", map("id", id, "from", map("uri", uri, "steps", steps)));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] properties(Map<String, String> values) throws IOException {
        // Properties.store timestamps vary, so serialize each sorted key and remove its comment line.
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            Properties property = new Properties();
            property.setProperty(entry.getKey(), entry.getValue());
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            property.store(text, null);
            String value = text.toString(StandardCharsets.ISO_8859_1);
            output.append(value.substring(value.indexOf('\n') + 1));
        }
        return bytes(output.toString());
    }
    /** Returns the deployment pin for the complete ZIP. */
    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
