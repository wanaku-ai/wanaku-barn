package ai.wanaku.backend.api.v1.kamelets;

import java.nio.charset.StandardCharsets;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KameletParameterDefaultsTest {
    private final KameletParser parser = new KameletParser();
    private final String yaml = KameletTestSupport.actionYaml("support-action", "Reply");

    @Test
    void acceptsSshSinkWithNumericPortDefaultWithoutChangingItsSchemaOrYaml() {
        String original =
                """
                apiVersion: camel.apache.org/v1
                kind: Kamelet
                metadata:
                  name: ssh-sink
                  labels:
                    camel.apache.org/kamelet.type: sink
                spec:
                  definition:
                    title: SSH Sink
                    description: Send command through SSH session.
                    required: [connectionHost, connectionPort, username, password]
                    type: object
                    properties:
                      connectionHost:
                        type: string
                      connectionPort:
                        type: string
                        default: 22
                      username:
                        type: string
                      password:
                        type: string
                        format: password
                      knownHostsResource:
                        type: string
                        pattern: "^(http|https|file|classpath|ref|bean):.*"
                  types:
                    in:
                      mediaType: text/plain
                    out:
                      mediaType: text/plain
                  dependencies: [camel:ssh, camel:gson, camel:kamelet]
                  template:
                    from:
                      uri: kamelet:source
                      steps:
                        - to:
                            uri: "ssh://{{connectionHost}}:{{connectionPort}}?knownHostsResource={{?knownHostsResource}}&password=RAW({{password}})&username=RAW({{username}})"
                """;

        var definition = parser.parse(original, "uploaded");

        assertThat(definition.yaml).isEqualTo(original);
        assertThat(definition.sha256).isEqualTo(KameletParser.digest(original.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "1.5", "true", "false"})
    void acceptsScalarDefaultsForStringParametersWithoutChangingConfigurationSchema(String scalar) {
        String original = yaml.replace("default: Support", "default: " + scalar);

        var definition = parser.parse(original, "uploaded");

        assertThat(definition.yaml).isEqualTo(original);
        var action = parser.action(definition);
        var prefix = (java.util.Map<?, ?>)
                ((java.util.Map<?, ?>) action.configurationSchema.get("properties")).get("prefix");
        assertThat(prefix.get("type")).isEqualTo("string");
        assertThat(prefix.get("default")).isNotInstanceOf(String.class);
        assertThat(prefix.get("default").toString()).isEqualTo(scalar);
    }

    @ParameterizedTest
    @CsvSource(
            value = {"22|pattern: '^22$'", "true|enum: ['true']", "22|minLength: 2", "false|maxLength: 5"},
            delimiter = '|')
    void acceptsScalarDefaultsThatSatisfyStringConstraints(String scalar, String constraint) {
        String original = yaml.replace(
                "default: Support\n        minLength: 1\n        maxLength: 120",
                "default: " + scalar + "\n        " + constraint);

        assertThat(parser.parse(original, "uploaded").yaml).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource(
            value = {"22|pattern: '^23$'", "true|enum: ['false']", "22|minLength: 3", "false|maxLength: 4"},
            delimiter = '|')
    void rejectsScalarDefaultsThatViolateStringConstraints(String scalar, String constraint) {
        String invalid = yaml.replace(
                "default: Support\n        minLength: 1\n        maxLength: 120",
                "default: " + scalar + "\n        " + constraint);

        assertThatThrownBy(() -> parser.parse(invalid, "uploaded"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("defaults must match");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null"})
    void rejectsNonScalarDefaultsForStringParameters(String value) {
        String invalid = yaml.replace("default: Support", "default: " + value);

        assertThatThrownBy(() -> parser.parse(invalid, "uploaded"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("defaults must match");
    }

    @Test
    void doesNotCoerceDefaultsWhenStringIsOnlyOneOfSeveralAllowedTypes() {
        String invalid = yaml.replace("default: Support", "default: 22")
                .replace("type: string\n        default: 22", "type: [string, boolean]\n        default: 22");

        assertThatThrownBy(() -> parser.parse(invalid, "uploaded"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("defaults must match");
    }
}
