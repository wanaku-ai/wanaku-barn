package ai.wanaku.backend.api.v1.servicecatalog;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Stream;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.ValidationResult;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates every service template shipped with the router, guarding against templates that the
 * router itself would reject.
 */
class BuiltInTemplatesValidationTest {

    private static final String TEMPLATES_RESOURCE = "service-templates";

    private static final CatalogValidator validator = new CatalogValidator();

    static Stream<Path> builtInTemplates() throws URISyntaxException, IOException {
        URL resource = Thread.currentThread().getContextClassLoader().getResource(TEMPLATES_RESOURCE);
        assertNotNull(resource, "The built-in service templates are not on the classpath");

        try (Stream<Path> templates = Files.list(Path.of(resource.toURI()))) {
            return templates.filter(Files::isDirectory).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("builtInTemplates")
    void testBuiltInTemplateIsValid(Path template) throws IOException {
        DataStore dataStore = new DataStore();
        dataStore.setName(template.getFileName().toString() + ".service.zip");
        dataStore.setData(Base64.getEncoder().encodeToString(ServiceTemplateInitializer.zipDirectory(template)));

        ValidationResult result = validator.validateTemplate(dataStore);

        assertTrue(result.valid(), () -> "%s is invalid: %s".formatted(template.getFileName(), result.errors()));
    }
}
