package ai.wanaku.backend.api.v1.kamelets;

import java.nio.charset.StandardCharsets;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KameletParserTest {
    private final KameletParser parser = new KameletParser();
    private final String yaml = KameletTestSupport.actionYaml("support-action", "Reply");

    @Test
    void preservesExactUnicodeCommentsAndLineEndings() {
        String original = ("# Café😀\n" + yaml).replace("\n", "\r\n");
        var definition = parser.parse(original, "uploaded");
        assertThat(definition.yaml).isEqualTo(original);
        assertThat(definition.sha256).isEqualTo(KameletParser.digest(original.getBytes(StandardCharsets.UTF_8)));
        assertThat(definition.semanticEligible).isTrue();
        assertThat(parser.action(definition).sha256).isEqualTo(definition.sha256);
    }

    @Test
    void acceptsTheCompleteNativeTemplateWithoutChangingOriginalYaml() {
        String complete = yaml.replace(
                "  template:",
                """
                  template:
                    id: explicit-template-id
                    description: Native template description
                    parameters:
                      - name: optionalParameter
                        defaultValue: original
                        required: false
                    beans:
                      - name: harmless
                        type: java.lang.String
                """);
        var definition = parser.parse(complete, "uploaded");
        assertThat(definition.name).isEqualTo("support-action");
        assertThat(definition.yaml).isEqualTo(complete);
        assertThat(definition.sha256).isEqualTo(KameletParser.digest(complete.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsNativeDashedPropertiesAndPreservesArbitraryParameterKeys() {
        String nativeAliases = yaml.replace(
                        "        - setBody:",
                        "        - setHeader:\n            name: Trace-Id\n            constant: original\n"
                                + "        - transformDataType:\n            to-type: java:java.lang.String\n"
                                + "        - setBody:")
                .replace(
                        "uri: kamelet:source",
                        "uri: kamelet:source\n      parameters:\n        foo-bar: original\n        fooBar: preserved");
        assertThat(parser.parse(nativeAliases, "uploaded").yaml).isEqualTo(nativeAliases);
        for (String invalid : new String[] {
            nativeAliases.replace("to-type: java:java.lang.String", "from-type: java:java.lang.String"),
            nativeAliases.replace("to-type: java:java.lang.String", "to-type: {}"),
            nativeAliases.replace(
                    "to-type: java:java.lang.String", "to-type: java:java.lang.String\n            toType: java:other"),
            nativeAliases.replace("- setHeader:", "- bogusHeader:"),
            nativeAliases.replace("- setBody:", "- setBody:\n            bogus-field: true")
        }) assertThatThrownBy(() -> parser.parse(invalid, "uploaded")).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void validatesNativeExpressionAliasesInsideExclusiveAndNegatedGuards() {
        String expression = yaml.replace("constant: \"{{prefix}}: Reply\"", "exchange-property: response");
        assertThat(expression).contains("exchange-property: response");
        assertThat(parser.parse(expression, "uploaded").yaml).isEqualTo(expression);
        for (String invalid : new String[] {
            expression.replace("exchange-property: response", "exchange-property: {bogus: true}"),
            expression.replace(
                    "exchange-property: response",
                    "exchange-property: response\n            exchangeProperty: duplicate"),
            expression.replace(
                    "exchange-property: response", "exchange-property: response\n            constant: duplicate")
        }) assertThatThrownBy(() -> parser.parse(invalid, "uploaded")).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void rejectsDashedNodeNamesAndExplicitExpressionTypeSelectors() {
        for (String invalid : new String[] {
            yaml.replace("- setBody:", "- set-body:"),
            yaml.replace("- setBody:", "- set-header:\n            name: Header"),
            yaml.replace("constant: \"{{prefix}}: Reply\"", "expression: {exchange-property: response}")
        }) assertThatThrownBy(() -> parser.parse(invalid, "uploaded")).isInstanceOf(InvalidPayloadException.class);
        String explicit = yaml.replace("constant: \"{{prefix}}: Reply\"", "expression: {exchangeProperty: response}");
        assertThat(parser.parse(explicit, "uploaded").yaml).isEqualTo(explicit);
    }

    @Test
    void rejectsMultipleDocumentsDuplicateKeysAndInvalidNativeTemplate() {
        for (String invalid : new String[] {
            yaml + "\n---\n" + yaml,
            yaml.replace("kind: Kamelet", "kind: Kamelet\nkind: Kamelet"),
            yaml.replace("- setBody:", "- bogusStep:"),
            yaml.replace("  template:", "  template:\n    bogus: true"),
            yaml.replace("  template:", "  template:\n    id: {invalid: true}"),
            yaml.replace("  template:", "  template:\n    description: {}"),
            yaml.replace("  template:", "  template:\n    parameters: invalid"),
            yaml.replace("kind: Kamelet", "kind: Route"),
            yaml.replace("apiVersion: camel.apache.org/v1", "apiVersion: wrong/v1"),
            yaml.replace("  dependencies:", "  dependencies: {}\n  ignoredDependencies:"),
            yaml.replace("  template:", "  template: null\n  ignoredTemplate:")
        }) assertThatThrownBy(() -> parser.parse(invalid, "uploaded")).isInstanceOf(InvalidPayloadException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"../support", "support/action", "support.action", "Support", "support_", "-support", "support-"})
    void rejectsUnsafeNativeNames(String name) {
        assertThatThrownBy(() -> parser.parse(yaml.replace("name: support-action", "name: " + name), "uploaded"))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void boundsUtf8BytesUnicodeAndNestingWithoutExecutingBeans() {
        assertThatThrownBy(() -> parser.parse("#" + "é".repeat(KameletParser.MAX_BYTES / 2) + "\n" + yaml, "uploaded"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("1 MiB");
        assertThatThrownBy(() -> parser.parse(yaml + "#\uD800", "uploaded"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("Unicode");
        String deep = yaml + "deep: " + "[".repeat(101) + "0" + "]".repeat(101);
        assertThatThrownBy(() -> parser.parse(deep, "uploaded")).isInstanceOf(InvalidPayloadException.class);
        String bean = yaml.replace(
                "  template:", "  template:\n    beans:\n      - name: harmless\n        type: java.lang.String");
        assertThat(parser.parse(bean, "uploaded").name).isEqualTo("support-action");
    }

    @Test
    void rejectsMalformedParameterSchemasPatternsDefaultsAndReferences() {
        for (String property : new String[] {
            "pattern: '['", "minLength: wrong", "default: {}", "$ref: 'https://invalid.example/schema'"
        }) {
            String invalid = yaml.replace("default: Support", property);
            assertThatThrownBy(() -> parser.parse(invalid, "uploaded")).isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void ordinaryNativeSinkIsEligibleWithoutCustomAnnotationsOrOutputSchema() {
        String original = "# Sink Café😀\n" + KameletTestSupport.sinkYaml("kafka-sink");
        var definition = parser.parse(original, "uploaded");
        assertThat(definition.semanticEligible).isTrue();
        assertThat(definition.yaml).isEqualTo(original);
        var action = parser.action(definition);
        assertThat(action.type).isEqualTo("sink");
        assertThat(action.sha256).isEqualTo(KameletParser.digest(original.getBytes(StandardCharsets.UTF_8)));
        assertThat(action.criteria).isEqualTo("Send the message to a Kafka topic.");
        assertThat(action.inputSchema).containsEntry("type", "string");
        assertThat(action.outputSchema).containsEntry("type", "string");
        assertThat(action.dependencies).contains("camel:kafka");
        assertThat(((java.util.Map<?, ?>)
                                ((java.util.Map<?, ?>) action.configurationSchema.get("properties")).get("password"))
                        .get("x-secret-reference"))
                .isEqualTo(true);
        assertThat(definition.yaml).doesNotContain("x-secret-reference");
        String explicit = original.replace(
                "  dependencies:",
                "  types:\n    in: {schema: {type: string}}\n    out: {schema: {type: object}}\n  dependencies:");
        assertThat(parser.parse(explicit, "uploaded").semanticEligible).isTrue();
    }

    @Test
    void incompatibleSinksRetainSpecificEligibilityReasons() {
        String sink = KameletTestSupport.sinkYaml("kafka-sink");
        for (String incompatible : new String[] {
            sink.replace("uri: kamelet:source", "uri: direct:start"),
            sink.replace("  dependencies:", "  types: {in: {schema: {type: object}}}\n  dependencies:"),
            sink.replace("  labels:", "  annotations: {barn.wanaku.ai/semantic-action: 'false'}\n  labels:"),
            sink.replace("  labels:", "  annotations: {barn.wanaku.ai/contract-profile: another-profile}\n  labels:"),
            sink.replace("uri: kafka:{{topic}}", "uri: kamelet:other-sink"),
            sink.replace("type: string\n        title: Topic", "type: object\n        title: Topic"),
            sink.replace("format: password", "format: password\n        default: inline-secret")
        }) {
            var definition = parser.parse(incompatible, "uploaded");
            assertThat(definition.semanticEligible).as(incompatible).isFalse();
            assertThat(definition.semanticEligibilityReason).isNotBlank();
            assertThat(parser.action(definition)).isNull();
        }
        var source = parser.parse(sink.replace("kamelet.type: sink", "kamelet.type: source"), "uploaded");
        assertThat(source.semanticEligibilityReason).contains("Source Kamelets");
    }

    @Test
    void nativePasswordDescriptorsUseReferencesWithoutGuessingFromPropertyNames() {
        String sink = KameletTestSupport.sinkYaml("kafka-sink");
        var descriptor = parser.action(parser.parse(
                sink.replace("format: password", "x-descriptors: [urn:alm:descriptor:com.tectonic.ui:password]"),
                "uploaded"));
        assertThat(((java.util.Map<?, ?>) descriptor.configurationSchema.get("properties")).get("password"))
                .isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) ((java.util.Map<?, ?>) descriptor.configurationSchema.get("properties"))
                                .get("password"))
                        .get("x-secret-reference"))
                .isEqualTo(true);
        var ordinary = parser.action(parser.parse(sink.replace("        format: password\n", ""), "uploaded"));
        assertThat(((java.util.Map<?, ?>)
                                ((java.util.Map<?, ?>) ordinary.configurationSchema.get("properties")).get("password"))
                        .get("x-secret-reference"))
                .isNull();
        var action = parser.action(parser.parse(yaml, "bundled"));
        assertThat(action.type).isEqualTo("action");
        assertThat(action.criteria).isEqualTo("Questions about invoices, payments, and refunds.");
    }

    @Test
    void genericCatalogAcceptsNonsemanticDefinitionsWithSpecificEligibilityReasons() {
        var source = parser.parse(yaml.replace("kamelet.type: action", "kamelet.type: source"), "uploaded");
        assertThat(source.semanticEligible).isFalse();
        assertThat(source.semanticEligibilityReason).contains("Source Kamelets");
        var nested = parser.parse(yaml.replace("uri: kamelet:source", "uri: kamelet:other-action"), "uploaded");
        assertThat(nested.semanticEligible).isFalse();
        assertThat(nested.semanticEligibilityReason).contains("Nested Kamelet");
        var expression = parser.parse(yaml.replace("default: Support", "default: '{{env:SECRET}}'"), "uploaded");
        assertThat(expression.semanticEligible).isFalse();
        assertThat(expression.semanticEligibilityReason).contains("defaults");
        var secret = parser.parse(
                yaml.replace("x-secret-reference: true", "x-secret-reference: true\n        default: raw-secret"),
                "uploaded");
        assertThat(secret.semanticEligible).isFalse();
        assertThat(secret.semanticEligibilityReason).contains("Secret");
    }
}
