package ai.wanaku.backend.api.v1.semanticrouter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticActionSelection;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticExample;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogValidator;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogZipReader;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticCatalogTest {
    SemanticActionCatalog catalog;
    SemanticDefinitionValidator validator;
    SemanticCatalogGenerator generator;

    @BeforeEach
    void setup() {
        catalog = new SemanticActionCatalog();
        catalog.kamelets = ai.wanaku.backend.api.v1.kamelets.KameletTestSupport.catalog(
                ai.wanaku.backend.api.v1.kamelets.KameletTestSupport.repository(new java.util.LinkedHashMap<>()));
        catalog.parser = new ai.wanaku.backend.api.v1.kamelets.KameletParser();
        catalog.expertsFile = Optional.empty();
        catalog.init();
        validator = new SemanticDefinitionValidator();
        validator.catalog = catalog;
        generator = new SemanticCatalogGenerator();
        generator.catalog = catalog;
    }

    static SemanticRouterDefinition definition() {
        SemanticRouterDefinition definition = new SemanticRouterDefinition();
        definition.name = "Support";
        definition.description = "Route a support message";
        definition.toolName = "route_support";
        definition.profile = SemanticActionCatalog.PROFILE;
        definition.expertId = "support";
        definition.semanticInput = "message";
        definition.instructions = "Select the action for this support message";
        definition.noMatchCriteria = "Neither action applies";
        definition.actions = new ArrayList<>();
        for (String label : List.of("billing", "technical")) {
            SemanticActionSelection action = new SemanticActionSelection();
            action.actionId = "wsr-" + label + "-action";
            action.label = label;
            action.criteria = label + " requests";
            action.configuration = Map.of("prefix", "Support");
            definition.actions.add(action);
        }
        SemanticExample example = new SemanticExample();
        example.message = "Invoice question";
        example.expectedLabel = "billing";
        definition.examples = List.of(example);
        return definition;
    }

    @ParameterizedTest
    @ValueSource(strings = {"support-route", "Support_Route2", "S", "route9"})
    void routerAndToolNamesAcceptAsciiLettersDigitsHyphensAndUnderscores(String name) {
        SemanticRouterDefinition definition = definition();
        definition.name = name;
        definition.toolName = name;
        assertThat(validator.validate(definition, true).valid).isTrue();
        assertThat(validator.validate(definition, false).valid).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "support route",
                " support",
                "support ",
                " ",
                "9support",
                "_support",
                "-support",
                "support.route",
                "Süpport",
                "support\nroute",
                "support/route"
            })
    void routerAndToolNamesRejectInvalidNonemptyDraftAndPublicationValues(String name) {
        SemanticRouterDefinition definition = definition();
        definition.name = name;
        definition.toolName = name;
        for (boolean draft : List.of(true, false)) {
            var validation = validator.validate(definition, draft);
            assertThat(validation.valid).isFalse();
            assertThat(validation.errors).extracting(error -> error.field).contains("name", "toolName");
        }
    }

    @Test
    void identifierLengthLimitsApplyToDraftsAndPublications() {
        SemanticRouterDefinition definition = definition();
        definition.name = "A".repeat(120);
        definition.toolName = "B".repeat(64);
        for (boolean draft : List.of(true, false))
            assertThat(validator.validate(definition, draft).valid).isTrue();
        definition.name += "A";
        definition.toolName += "B";
        for (boolean draft : List.of(true, false))
            assertThat(validator.validate(definition, draft).errors)
                    .extracting(error -> error.field)
                    .contains("name", "toolName");
    }

    @Test
    void missingIdentifiersRemainSaveableOnlyAsIncompleteDrafts() {
        SemanticRouterDefinition definition = definition();
        for (String name : new String[] {null, ""}) {
            definition.name = name;
            definition.toolName = name;
            assertThat(validator.validate(definition, true).valid).isTrue();
            assertThat(validator.validate(definition, false).errors)
                    .extracting(error -> error.field)
                    .contains("name", "toolName");
        }
    }

    @Test
    void curatedMetadataUsesNativeKameletSchemas() {
        assertThat(catalog.actions()).hasSize(2);
        assertThat(catalog.actions().getFirst().configurationSchema).containsKeys("properties", "required");
        assertThat(catalog.actions().getFirst().inputSchema).containsEntry("type", "string");
        assertThat(catalog.experts().getFirst().dependency).contains("camel-typesafe-ai");
    }

    @Test
    void deterministicArchiveContainsAuxiliaryResourcesAndNativeRoutes() throws Exception {
        SemanticRouterDefinition definition = definition();
        assertThat(validator.validate(definition, false).valid).isTrue();
        byte[] archive = generator.generate(definition, "revision1", "support-revision1");
        assertThat(generator.generate(definition, "revision1", "support-revision1"))
                .isEqualTo(archive);
        Map<String, String> files = CatalogZipReader.readEntriesAsText(archive);
        assertThat(files)
                .containsKeys(
                        "index.properties",
                        "service/router.camel.yaml",
                        "service/preview.camel.yaml",
                        "service/semantic-router.properties",
                        "service/service.properties",
                        "service/kamelets/wsr-billing-action.kamelet.yaml",
                        "service/kamelets/wsr-technical-action.kamelet.yaml");
        assertThat(SemanticYamlValidator.validate(
                        files.get(SemanticCatalogGenerator.MAIN).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEmpty();
        DataStore store = new DataStore();
        store.setName("support-revision1");
        store.setData(Base64.getEncoder().encodeToString(archive));
        var validation = new CatalogValidator().validateCatalog(store);
        assertThat(validation.errors()).isEmpty();
        assertThat(validation.valid()).isTrue();
        assertThat(files.get("service/preview.camel.yaml"))
                .contains("semantic", "ref:department", "supportExpert")
                .doesNotContain("kamelet:", "ai-tool:", "dispatch-router", "No matching action");
    }

    @Test
    void nativeSinkUsesRequiredDeploymentParametersAndAcknowledgesOnlyAfterDelivery() throws Exception {
        String original = ai.wanaku.backend.api.v1.kamelets.KameletTestSupport.sinkYaml("kafka-sink");
        var upload = new ai.wanaku.backend.api.v1.kamelets.model.KameletUpload();
        upload.yaml = original;
        var uploaded = catalog.kamelets.upload(upload);
        SemanticRouterDefinition definition = definition();
        var selection = definition.actions.getFirst();
        selection.actionId = uploaded.name;
        selection.sha256 = uploaded.sha256;
        selection.configuration =
                Map.of("topic", "support", "bootstrapServers", "kafka:9092", "password", "env:KAFKA_PASSWORD");
        assertThat(validator.validate(definition, false).valid).isTrue();
        Map<String, String> entries =
                CatalogZipReader.readEntriesAsText(generator.generate(definition, "r1", "support-r1"));
        assertThat(entries.get("service/kamelets/kafka-sink.kamelet.yaml")).isEqualTo(original);
        assertThat(entries.get("service/dependencies.txt")).contains("camel:kafka");
        var branches = new YAMLMapper()
                .readTree(entries.get(SemanticCatalogGenerator.MAIN))
                .get(2)
                .path("route")
                .path("from")
                .path("steps")
                .get(1)
                .path("choice")
                .path("when");
        assertThat(branches.get(0).path("steps")).hasSize(2);
        assertThat(branches.get(0).path("steps").get(0).path("to").path("uri").asText())
                .isEqualTo("kamelet:kafka-sink");
        assertThat(branches.get(0)
                        .path("steps")
                        .get(1)
                        .path("setBody")
                        .path("constant")
                        .asText())
                .isEqualTo("Routed to billing.");
        assertThat(branches.get(1).path("steps")).hasSize(1);
        assertThat(entries.get("service/preview.camel.yaml")).doesNotContain("kamelet:", "Routed to");
        java.util.Properties properties = new java.util.Properties();
        properties.load(new java.io.StringReader(entries.get("service/service.properties")));
        assertThat(properties.getProperty("action.billing.password")).isEqualTo("{{env:KAFKA_PASSWORD}}");
        assertThat(SemanticYamlValidator.validate(
                        entries.get(SemanticCatalogGenerator.MAIN).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEmpty();
        selection.configuration = Map.of("topic", "support", "password", "plain-secret");
        var errors = validator.validate(definition, false).errors;
        assertThat(errors).anySatisfy(error -> assertThat(error.field).isEqualTo("actions[0].configuration.password"));
        assertThat(errors).anySatisfy(error -> assertThat(error.message).contains("bootstrapServers"));
        assertThat(validator.validate(definition, true).valid).isFalse();
    }

    @Test
    void missingRequiredConfigurationAndIncompatibleProfilesAreRejected() {
        SemanticRouterDefinition definition = definition();
        definition.actions.getFirst().configuration = Map.of("prefix", "");
        definition.profile = "unknown";
        var errors = validator.validate(definition, false).errors;
        assertThat(errors).anySatisfy(e -> assertThat(e.field).isEqualTo("profile"));
        assertThat(errors).anySatisfy(e -> assertThat(e.field).contains("configuration"));
        definition = definition();
        definition.actions.getFirst().configuration = Map.of("credentialRef", "plain-secret");
        assertThat(validator.validate(definition, true).valid).isFalse();
    }

    @Test
    void labelsAndParametersCannotCreateExecutableExpressions() {
        for (String unsafe : List.of("{{env:SECRET}}", "${body}", "#bean:dangerous")) {
            SemanticRouterDefinition definition = definition();
            definition.actions.getFirst().configuration = Map.of("prefix", unsafe);
            assertThat(validator.validate(definition, false).valid).isFalse();
        }
        SemanticRouterDefinition definition = definition();
        definition.actions.getFirst().label = "billing' || true";
        assertThat(validator.validate(definition, false).valid).isFalse();
        definition = definition();
        definition.actions.getLast().label = "billing";
        assertThat(validator.validate(definition, false).valid).isFalse();
    }

    @Test
    void yamlAndPropertiesEscapeTextWithoutChangingNativeExpressions() throws IOException {
        SemanticRouterDefinition definition = definition();
        definition.instructions = "hello: \"quoted\"\n- to: exec:bad";
        definition.actions.getFirst().configuration =
                Map.of("prefix", "Café😀\\team\nSecond line", "credentialRef", "env:SUPPORT_TOKEN");
        assertThat(validator.validate(definition, false).valid).isTrue();
        Map<String, String> entries =
                CatalogZipReader.readEntriesAsText(generator.generate(definition, "r1", "support-r1"));
        var yaml = new YAMLMapper().readTree(entries.get(SemanticCatalogGenerator.MAIN));
        assertThat(yaml.get(0)
                        .path("semantic")
                        .path("question")
                        .path("department")
                        .path("instructions")
                        .asText())
                .isEqualTo(definition.instructions);
        java.util.Properties properties = new java.util.Properties();
        properties.load(new java.io.StringReader(entries.get("service/service.properties")));
        assertThat(properties.getProperty("action.billing.prefix")).isEqualTo("Café😀\\team\nSecond line");
        assertThat(properties.getProperty("action.billing.credentialRef")).isEqualTo("{{env:SUPPORT_TOKEN}}");
    }

    @Test
    void ordinaryEnvPrefixedValuesRemainLiteral() throws IOException {
        SemanticRouterDefinition definition = definition();
        definition.actions.getFirst().configuration = Map.of("prefix", "env:SUPPORT_TOKEN");
        assertThat(validator.validate(definition, false).valid).isTrue();
        var entries = CatalogZipReader.readEntriesAsText(generator.generate(definition, "r1", "support-r1"));
        java.util.Properties properties = new java.util.Properties();
        properties.load(new java.io.StringReader(entries.get("service/service.properties")));
        assertThat(properties.getProperty("action.billing.prefix")).isEqualTo("env:SUPPORT_TOKEN");
        assertThat(entries.get("service/service.properties")).doesNotContain("{{env:SUPPORT_TOKEN}}");
    }

    @Test
    void bundledKameletsCannotBeReplacedByUploads() {
        var upload = new ai.wanaku.backend.api.v1.kamelets.model.KameletUpload();
        upload.yaml = catalog.kamelets.get("wsr-billing-action", null).yaml;
        assertThatThrownBy(() -> catalog.kamelets.upload(upload))
                .isInstanceOf(ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException.class);
    }
}
