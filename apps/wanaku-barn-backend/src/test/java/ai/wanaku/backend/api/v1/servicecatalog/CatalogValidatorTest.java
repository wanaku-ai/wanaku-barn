package ai.wanaku.backend.api.v1.servicecatalog;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.ValidationIssue;
import ai.wanaku.core.services.api.ValidationResult;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogValidatorTest {

    private static final String VALID_ROUTES =
            """
            - route:
                id: sample
                from:
                  uri: "ai-tool:sample"
                  parameters:
                    tags: wanaku
                    description: "{{tool.description}}"
                  steps:
                    - log:
                        message: "Handling ${body}"
            """;

    private static final CatalogValidator validator = new CatalogValidator();

    @Test
    void testValidCatalog() {
        ValidationResult result = validator.validateCatalog(catalog(defaultIndex(), defaultFiles()));

        assertTrue(result.valid(), () -> "Unexpected errors: " + result.errors());
        assertEquals(ValidationResult.TYPE_CATALOG, result.type());
        assertEquals("sample", result.name());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testMissingDataIsRejected() {
        DataStore empty = new DataStore();
        empty.setName("sample.zip");

        assertThrows(InvalidPayloadException.class, () -> validator.validateCatalog(empty));
        assertThrows(InvalidPayloadException.class, () -> validator.validateCatalog(null));
        assertThrows(InvalidPayloadException.class, () -> validator.validateTemplate(empty));
    }

    @Test
    void testMissingNameIsReported() {
        DataStore dataStore = catalog(defaultIndex(), defaultFiles());
        dataStore.setName(" ");

        ValidationResult result = validator.validateCatalog(dataStore);

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "name", "required"));
    }

    @Test
    void testInvalidBase64() {
        DataStore dataStore = new DataStore();
        dataStore.setName("sample.zip");
        dataStore.setData("not-base-64-@@@");

        ValidationResult result = validator.validateCatalog(dataStore);

        assertFalse(result.valid());
        assertEquals(1, result.errors().size());
        assertEquals("data", result.errors().get(0).path());
    }

    @Test
    void testMissingIndexFile() {
        DataStore dataStore = new DataStore();
        dataStore.setName("sample.zip");
        dataStore.setData(zip(Map.of("sample/sample.camel.yaml", VALID_ROUTES)));

        ValidationResult result = validator.validateCatalog(dataStore);

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "index.properties", "does not contain"));
    }

    @Test
    void testMissingRequiredProperties() {
        ValidationResult result = validator.validateCatalog(catalog("catalog.icon=box\n", Map.of()));

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "index.properties#catalog.name", "missing"));
        assertTrue(hasIssue(result.errors(), "index.properties#catalog.description", "missing"));
        assertTrue(hasIssue(result.errors(), "index.properties#catalog.services", "missing"));
    }

    @Test
    void testAllErrorsAreCollected() {
        String index =
                """
                catalog.name=sample
                catalog.services=sample
                """;

        ValidationResult result = validator.validateCatalog(catalog(index, Map.of()));

        // Missing description and missing routes are both reported at once
        assertFalse(result.valid());
        assertEquals(2, result.errors().size(), () -> "Unexpected errors: " + result.errors());
    }

    @Test
    void testReferencedFileNotInPackage() {
        ValidationResult result = validator.validateCatalog(catalog(defaultIndex(), Map.of()));

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "index.properties#catalog.routes.sample", "not present in the package"));
    }

    @Test
    void testPathTraversalIsRejected() {
        String index =
                """
                catalog.name=sample
                catalog.description=A sample catalog
                catalog.services=sample
                catalog.routes.sample=../../etc/passwd
                """;

        ValidationResult result = validator.validateCatalog(catalog(index, defaultFiles()));

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "index.properties#catalog.routes.sample", "'..'"));
    }

    @Test
    void testUnparseableYamlIsReported() {
        Map<String, String> files = new LinkedHashMap<>(defaultFiles());
        files.put("sample/sample.camel.yaml", "- route:\n    id: sample\n  bad: [unclosed\n");

        ValidationResult result = validator.validateCatalog(catalog(defaultIndex(), files));

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "sample/sample.camel.yaml", "Invalid YAML"));
    }

    @Test
    void testInvalidCamelDslIsReported() {
        // Parses as YAML, but 'from' is required by the Camel YAML DSL schema
        Map<String, String> files = new LinkedHashMap<>(defaultFiles());
        files.put("sample/sample.camel.yaml", "- route:\n    id: sample\n    frm:\n      uri: \"direct:sample\"\n");

        ValidationResult result = validator.validateCatalog(catalog(defaultIndex(), files));

        assertFalse(result.valid());
        assertTrue(
                result.errors().stream()
                        .anyMatch(i -> i.path().startsWith("sample/sample.camel.yaml")
                                && i.message().startsWith("Invalid Camel YAML DSL")),
                () -> "Unexpected errors: " + result.errors());
    }

    @Test
    void testRulesFileIsNotRequired() {
        // As of 0.3.0 the rules file is superseded by Camel's built-in MCP support
        ValidationResult result = validator.validateCatalog(catalog(defaultIndex(), defaultFiles()));

        assertTrue(result.valid(), () -> "Unexpected errors: " + result.errors());
        assertTrue(result.errors().stream().noneMatch(i -> i.path().contains("rules")));
    }

    @Test
    void testMissingIconIsOnlyAWarning() {
        ValidationResult result = validator.validateCatalog(catalog(defaultIndex(), defaultFiles()));

        assertTrue(result.valid());
        assertTrue(hasIssue(result.warnings(), "index.properties#catalog.icon", "default icon"));
    }

    @Test
    void testCatalogWithTemplatePropertiesIsWarned() {
        String index = defaultIndex() + "catalog.properties.sample=sample/service.properties\n";
        Map<String, String> files = new LinkedHashMap<>(defaultFiles());
        files.put("sample/service.properties", "broker.url=\n");

        ValidationResult result = validator.validateCatalog(catalog(index, files));

        assertTrue(result.valid(), () -> "Unexpected errors: " + result.errors());
        assertTrue(hasIssue(result.warnings(), "index.properties#catalog.properties.sample", "service templates"));
    }

    @Test
    void testValidTemplate() {
        String index = defaultIndex() + "catalog.properties.sample=sample/service.properties\n";
        Map<String, String> files = new LinkedHashMap<>(defaultFiles());
        files.put("sample/service.properties", "broker.url=\n");

        ValidationResult result = validator.validateTemplate(catalog(index, files));

        assertTrue(result.valid(), () -> "Unexpected errors: " + result.errors());
        assertEquals(ValidationResult.TYPE_TEMPLATE, result.type());
        assertTrue(result.warnings().stream().noneMatch(i -> i.path().contains("catalog.properties")));
    }

    @Test
    void testTemplateWithConventionalPropertiesFile() {
        Map<String, String> files = new LinkedHashMap<>(defaultFiles());
        files.put("sample/service.properties", "broker.url=\n");

        ValidationResult result = validator.validateTemplate(catalog(defaultIndex(), files));

        assertTrue(result.valid(), () -> "Unexpected errors: " + result.errors());
    }

    @Test
    void testTemplateWithoutPropertiesIsInvalid() {
        ValidationResult result = validator.validateTemplate(catalog(defaultIndex(), defaultFiles()));

        assertFalse(result.valid());
        assertTrue(hasIssue(result.errors(), "index.properties", "must provide a properties file"));
    }

    // Helpers

    private static String defaultIndex() {
        return """
                catalog.name=sample
                catalog.description=A sample catalog
                catalog.services=sample
                catalog.routes.sample=sample/sample.camel.yaml
                """;
    }

    private static Map<String, String> defaultFiles() {
        return Map.of("sample/sample.camel.yaml", VALID_ROUTES);
    }

    private static DataStore catalog(String index, Map<String, String> files) {
        Map<String, String> entries = new LinkedHashMap<>(files);
        entries.put("index.properties", index);

        DataStore dataStore = new DataStore();
        dataStore.setName("sample.zip");
        dataStore.setData(zip(entries));
        return dataStore;
    }

    private static String zip(Map<String, String> entries) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static boolean hasIssue(List<ValidationIssue> issues, String path, String messagePart) {
        return issues.stream()
                .anyMatch(i -> path.equals(i.path()) && i.message().contains(messagePart));
    }
}
