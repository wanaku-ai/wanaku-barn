package ai.wanaku.backend.api.v1.kamelets;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.util.StringHelper;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.api.v1.kamelets.model.KameletDefinition;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticAction;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Parses native Kamelet documents and derives semantic eligibility without executing YAML. */
@ApplicationScoped
public class KameletParser {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final String PROFILE = "message-to-string/v1";
    private static final SchemaRegistry NATIVE_SCHEMAS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
    private static final Set<String> EXACT_SELECTOR_SCHEMAS = Set.of(
            "org.apache.camel.model.ProcessorDefinition",
            "org.apache.camel.model.language.ExpressionDefinition",
            "org.apache.camel.model.ExpressionSubElementDefinition",
            "org.apache.camel.model.ErrorHandlerDefinition",
            "org.apache.camel.dsl.yaml.deserializers.ErrorHandlerDeserializer",
            "org.apache.camel.model.dataformat.DataFormatsDefinition",
            "org.apache.camel.model.transformer.TransformersDefinition",
            "org.apache.camel.model.validator.ValidatorsDefinition");
    private static final Schema TEMPLATE_SCHEMA = nativeTemplateSchema();
    private static final Schema PARAMETER_SCHEMA =
            NATIVE_SCHEMAS.getSchema(SchemaLocation.of("classpath:/draft-07/schema"));
    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** Creates a bounded, duplicate-key-aware YAML parser. */
    public KameletParser() {
        mapper.getFactory()
                .setStreamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100)
                        .maxStringLength(MAX_BYTES)
                        .build());
    }

    /** Validates a single native document and derives metadata.
     * @param yaml original YAML
     * @param source catalog source
     * @return validated definition */
    public KameletDefinition parse(String yaml, String source) {
        byte[] bytes = bytes(yaml);
        if (bytes.length > MAX_BYTES) throw new InvalidPayloadException("Kamelet YAML exceeds 1 MiB");
        JsonNode root = document(yaml);
        require(
                root.isObject()
                        && "camel.apache.org/v1".equals(root.path("apiVersion").asText())
                        && "Kamelet".equals(root.path("kind").asText()),
                "Use a native camel.apache.org/v1 Kamelet document");
        String name = root.path("metadata").path("name").asText();
        require(
                validName(name),
                "Kamelet name must use lowercase letters, digits, and hyphens, with a letter first and a letter or digit last; maximum 64 characters");
        String type = root.path("metadata")
                .path("labels")
                .path("camel.apache.org/kamelet.type")
                .asText();
        require(
                Set.of("source", "sink", "action").contains(type),
                "Select a native Kamelet source, sink, or action type");
        JsonNode spec = root.path("spec");
        JsonNode definition = spec.path("definition");
        require(spec.isObject() && definition.isObject(), "Kamelet spec.definition must be an object");
        require(
                !definition.has("properties") || definition.path("properties").isObject(),
                "Kamelet definition.properties must be an object");
        for (JsonNode property : definition.path("properties"))
            require(property.isObject(), "Kamelet property schemas must be objects");
        require(
                !definition.has("required") || definition.path("required").isArray(),
                "Kamelet definition.required must be an array");
        for (JsonNode required : definition.path("required"))
            require(
                    required.isTextual() && definition.path("properties").has(required.asText()),
                    "Required Kamelet parameters must name declared properties");
        JsonNode from = spec.path("template").path("from");
        require(
                spec.path("template").isObject()
                        && from.isObject()
                        && from.path("uri").isTextual()
                        && !from.path("uri").asText().isBlank()
                        && from.path("steps").isArray()
                        && !from.path("steps").isEmpty(),
                "Kamelet spec.template.from must contain a URI and nonempty steps");
        for (JsonNode step : from.path("steps"))
            require(step.isObject() && !step.isEmpty(), "Kamelet steps must be nonempty objects");
        require(
                spec.path("dependencies").isArray() && spec.path("dependencies").size() <= 128,
                "Kamelet dependencies must be an array of at most 128 items");
        for (JsonNode dependency : spec.path("dependencies"))
            require(
                    dependency.isTextual()
                            && dependency
                                    .asText()
                                    .matches(
                                            "(?:camel:[a-z][a-z0-9-]*|mvn:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"),
                    "Use native camel:component or mvn:group:artifact:version dependencies");
        validateParameters(definition);
        try {
            Map<String, Object> template = mapper.convertValue(spec.path("template"), new TypeReference<>() {});
            if (!template.containsKey("id")) template.put("id", name);
            require(
                    TEMPLATE_SCHEMA
                            .validate(
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .writeValueAsString(List.of(Map.of("routeTemplate", template))),
                                    InputFormat.JSON)
                            .isEmpty(),
                    "Kamelet template must use valid native Camel YAML DSL");
        } catch (IOException e) {
            throw new InvalidPayloadException("Cannot validate Kamelet template");
        }
        KameletDefinition result = new KameletDefinition();
        result.name = name;
        result.title = definition.path("title").asText(name);
        result.description = definition.path("description").asText();
        result.type = type;
        result.sha256 = digest(bytes);
        result.source = source;
        result.yaml = yaml;
        result.semanticEligibilityReason = eligibility(root);
        result.semanticEligible = result.semanticEligibilityReason == null;
        result.downloadUrl = "/api/v1/kamelets/" + name + ".kamelet.yaml?sha256=" + result.sha256;
        return result;
    }

    /** Returns the semantic adapter for an eligible definition.
     * @param definition exact validated revision
     * @return semantic metadata or null */
    public SemanticAction action(KameletDefinition definition) {
        if (!definition.semanticEligible) return null;
        JsonNode root = document(definition.yaml);
        JsonNode spec = root.path("spec");
        SemanticAction action = new SemanticAction();
        action.id = definition.name;
        action.sha256 = definition.sha256;
        action.type = definition.type;
        action.name = definition.title;
        action.description = definition.description;
        action.profile = PROFILE;
        action.criteria = root.path("metadata")
                .path("annotations")
                .path("barn.wanaku.ai/routing-description")
                .asText("sink".equals(definition.type) ? definition.description : "");
        ObjectNode configuration = spec.path("definition").deepCopy();
        for (JsonNode property : configuration.path("properties")) {
            if (secretParameter(property)) ((ObjectNode) property).put("x-secret-reference", true);
        }
        action.configurationSchema = mapper.convertValue(configuration, new TypeReference<Map<String, Object>>() {});
        if ("sink".equals(definition.type)) {
            action.inputSchema = Map.of("type", "string");
            action.outputSchema = Map.of("type", "string");
        } else {
            action.inputSchema = mapper.convertValue(
                    spec.path("types").path("in").path("schema"), new TypeReference<Map<String, Object>>() {});
            action.outputSchema = mapper.convertValue(
                    spec.path("types").path("out").path("schema"), new TypeReference<Map<String, Object>>() {});
        }
        action.dependencies = mapper.convertValue(spec.path("dependencies"), new TypeReference<List<String>>() {});
        return action;
    }

    /** Encodes original YAML without replacing malformed Unicode.
     * @param yaml original text
     * @return UTF-8 bytes */
    public static byte[] bytes(String yaml) {
        if (yaml == null || yaml.isBlank()) throw new InvalidPayloadException("Kamelet YAML is required");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(yaml));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new InvalidPayloadException("Kamelet YAML must contain valid Unicode");
        }
    }

    /** Hashes original resource bytes.
     * @param bytes resource bytes
     * @return lowercase SHA-256 */
    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Checks a native catalog name.
     * @param name candidate name
     * @return whether the name is valid */
    public static boolean validName(String name) {
        return name != null && name.matches("[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?");
    }

    private static Schema nativeTemplateSchema() {
        JsonNode schema = NATIVE_SCHEMAS
                .getSchema(SchemaLocation.of("classpath:/schema/camelYamlDsl.json"))
                .getSchemaNode()
                .deepCopy();
        // Camel passes node/type selectors verbatim to resolvers. Only model properties accept dashed aliases.
        for (Map.Entry<String, JsonNode> definition :
                schema.path("items").path("definitions").properties()) {
            if (!EXACT_SELECTOR_SCHEMAS.contains(definition.getKey())) addNativeAliases(definition.getValue());
        }
        return NATIVE_SCHEMAS.getSchema(schema);
    }

    // Camel accepts dashed DSL names, while its generated schema contains camelCase names.
    // Expand only schema properties: original YAML and arbitrary parameter/map keys stay intact.
    private static void addNativeAliases(JsonNode node) {
        for (JsonNode child : node) addNativeAliases(child);
        if (!node.isObject()) return;
        ObjectNode schema = (ObjectNode) node;
        if (schema.get("required") instanceof ArrayNode required) {
            for (JsonNode field : required.deepCopy()) {
                String name = field.asText();
                String alias = StringHelper.camelCaseToDash(name);
                if (!alias.equals(name)) allowRequiredAlias(schema, schema.withArray("allOf"), name, alias);
            }
        }
        if (!schema.path("properties").isObject()) return;
        ObjectNode properties = (ObjectNode) schema.get("properties");
        for (Map.Entry<String, JsonNode> property : List.copyOf(properties.properties())) {
            String name = property.getKey();
            String alias = StringHelper.camelCaseToDash(name);
            if (alias.equals(name)) continue;
            properties.set(alias, property.getValue());
            ArrayNode constraints = schema.withArray("allOf");
            constraints
                    .addObject()
                    .putObject("not")
                    .putArray("required")
                    .add(name)
                    .add(alias);
        }
    }

    private static void allowRequiredAlias(ObjectNode schema, ArrayNode constraints, String name, String alias) {
        if (!(schema.get("required") instanceof ArrayNode required)) return;
        int index = 0;
        while (index < required.size() && !name.equals(required.get(index).asText())) index++;
        if (index == required.size()) return;
        required.remove(index);
        if (required.isEmpty()) schema.remove("required");
        ArrayNode alternatives = constraints.addObject().putArray("anyOf");
        alternatives.addObject().putArray("required").add(name);
        alternatives.addObject().putArray("required").add(alias);
    }

    private JsonNode document(String yaml) {
        try (JsonParser parser = mapper.createParser(yaml)) {
            JsonNode root = mapper.readTree(parser);
            require(root != null && parser.nextToken() == null, "Upload exactly one Kamelet YAML document");
            return root;
        } catch (IOException e) {
            throw new InvalidPayloadException("Cannot parse a single native Kamelet YAML document");
        }
    }

    private static String eligibility(JsonNode root) {
        String contractReason = contractEligibility(root);
        if (contractReason != null) return contractReason;
        JsonNode spec = root.path("spec");
        if (nestedKamelet(spec.path("template")))
            return "Nested Kamelet dependencies are not supported for semantic actions";
        JsonNode definition = spec.path("definition");
        if (definition.has("type") && !"object".equals(definition.path("type").asText()))
            return "Semantic configuration must use an object definition schema";
        for (JsonNode property : definition.path("properties")) {
            String reason = propertyEligibility(property);
            if (reason != null) return reason;
        }
        return null;
    }

    private static String contractEligibility(JsonNode root) {
        String type = root.path("metadata")
                .path("labels")
                .path("camel.apache.org/kamelet.type")
                .asText();
        if ("source".equals(type)) return "Source Kamelets create messages; select an action or sink to route messages";
        JsonNode annotations = root.path("metadata").path("annotations");
        JsonNode semanticAction = annotations.path("barn.wanaku.ai/semantic-action");
        JsonNode profile = annotations.path("barn.wanaku.ai/contract-profile");
        if (("action".equals(type) || !semanticAction.isMissingNode()) && !"true".equals(semanticAction.asText()))
            return "Add barn.wanaku.ai/semantic-action: true";
        if (("action".equals(type) || !profile.isMissingNode()) && !PROFILE.equals(profile.asText()))
            return "Use the message-to-string/v1 contract profile";
        JsonNode spec = root.path("spec");
        JsonNode input = spec.path("types").path("in");
        if ("sink".equals(type)) return sinkInputEligibility(spec, input);
        if (!"string".equals(input.path("schema").path("type").asText())
                || !"string"
                        .equals(spec.path("types")
                                .path("out")
                                .path("schema")
                                .path("type")
                                .asText())) {
            return "Declare native string input and output schemas";
        }
        return null;
    }

    private static String sinkInputEligibility(JsonNode spec, JsonNode input) {
        if (!spec.path("template").path("from").path("uri").asText().matches("kamelet:source(?:\\?.*)?"))
            return "Semantic sinks must consume from the native kamelet:source endpoint";
        if (!input.isMissingNode()
                && !"string".equals(input.path("schema").path("type").asText()))
            return "Semantic sinks must accept a string message input";
        return null;
    }

    private static String propertyEligibility(JsonNode property) {
        if (!Set.of("string", "boolean", "integer", "number")
                .contains(property.path("type").asText()))
            return "Semantic configuration must use primitive string, boolean, integer, or number parameters";
        if (secretParameter(property)) {
            if (!"string".equals(property.path("type").asText())
                    || property.has("default")
                            && !property.path("default").asText().matches("env:[A-Z_][A-Z0-9_]{0,127}"))
                return "Secret parameters must use string environment references such as env:SUPPORT_TOKEN";
        } else if (property.path("default").isTextual()) {
            String value = property.path("default").asText();
            if (value.contains("{{") || value.contains("${") || value.contains("}}") || value.startsWith("#"))
                return "Semantic configuration defaults cannot contain expressions or property placeholders";
        }
        return null;
    }

    private static boolean secretParameter(JsonNode property) {
        if (property.path("x-secret-reference").asBoolean(false)
                || "password".equals(property.path("format").asText())) return true;
        for (JsonNode descriptor : property.path("x-descriptors")) {
            if ("urn:alm:descriptor:com.tectonic.ui:password".equals(descriptor.asText())) return true;
        }
        return false;
    }

    private static void validateParameters(JsonNode definition) {
        require(
                definition.findValue("$ref") == null
                        && definition.findValue("$dynamicRef") == null
                        && definition.findValue("$recursiveRef") == null
                        && definition.findValue("$schema") == null,
                "Kamelet parameter schemas cannot use schema references or external dialects");
        try {
            require(
                    PARAMETER_SCHEMA
                            .validate(definition.toString(), InputFormat.JSON)
                            .isEmpty(),
                    "Kamelet parameter schema is invalid");
            validatePatterns(definition);
            for (JsonNode property : definition.path("properties")) {
                Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
                        .getSchema(property.toString(), InputFormat.JSON);
                if (property.has("default")) {
                    JsonNode value = property.path("default");
                    // Camel binds Kamelet parameters as strings, including unquoted YAML scalars.
                    if ("string".equals(property.path("type").asText()) && (value.isNumber() || value.isBoolean()))
                        value = com.fasterxml.jackson.databind.node.TextNode.valueOf(value.asText());
                    require(
                            schema.validate(value.toString(), InputFormat.JSON).isEmpty(),
                            "Kamelet parameter defaults must match their declared schema");
                }
            }
        } catch (InvalidPayloadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidPayloadException("Kamelet parameter schema contains an invalid pattern or constraint");
        }
    }

    private static void validatePatterns(JsonNode schema) {
        if (schema.has("pattern"))
            java.util.regex.Pattern.compile(schema.path("pattern").asText());
        if (schema.path("patternProperties").isObject())
            schema.path("patternProperties").fieldNames().forEachRemaining(java.util.regex.Pattern::compile);
        for (JsonNode child : schema) if (child.isContainerNode()) validatePatterns(child);
    }

    private static boolean nestedKamelet(JsonNode node) {
        if (node.isTextual()) {
            String value = node.asText();
            return value.startsWith("kamelet:") && !value.matches("kamelet:(?:source|sink)(?:\\?.*)?");
        }
        for (JsonNode child : node) if (nestedKamelet(child)) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new InvalidPayloadException(message);
    }
}
