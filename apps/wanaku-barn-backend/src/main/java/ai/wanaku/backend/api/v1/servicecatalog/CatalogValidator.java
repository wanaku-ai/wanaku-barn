package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.camel.dsl.yaml.validator.YamlValidator;
import org.jboss.logging.Logger;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.SafeZip;
import ai.wanaku.core.services.api.ServiceCatalogIndex;
import ai.wanaku.core.services.api.ValidationIssue;
import ai.wanaku.core.services.api.ValidationResult;
import ai.wanaku.core.util.StringHelper;
import com.networknt.schema.ValidationMessage;

/**
 * Validates service catalog and service template packages against the structure described in
 * {@link ServiceCatalogIndex}, collecting every problem found instead of failing on the first one.
 * <p>
 * The checks performed are:
 * <ul>
 *   <li>the payload is valid Base64 and a readable ZIP archive within the {@link SafeZip} limits</li>
 *   <li>the archive contains a parseable {@code index.properties} manifest</li>
 *   <li>the manifest declares {@code catalog.name}, {@code catalog.description} and
 *       {@code catalog.services}</li>
 *   <li>every declared system has a {@code catalog.routes.<system>} entry whose path is safe and
 *       present in the archive</li>
 *   <li>the optional {@code catalog.dependencies.<system>} and {@code catalog.properties.<system>}
 *       entries, when declared, point to files present in the archive</li>
 *   <li>the route file of every system is valid Camel YAML DSL, as checked by Camel's own
 *       {@link YamlValidator}</li>
 *   <li>{@code service.properties} files are well-formed property files</li>
 *   <li>a service template declares a properties file for at least one system</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogValidator {
    private static final Logger LOG = Logger.getLogger(CatalogValidator.class);

    private static final String INDEX_FILE = "index.properties";
    private static final String PROP_NAME = "catalog.name";
    private static final String PROP_ICON = "catalog.icon";
    private static final String PROP_DESCRIPTION = "catalog.description";
    private static final String PROP_SERVICES = "catalog.services";
    private static final String PROP_ROUTES_PREFIX = "catalog.routes.";
    private static final String PROP_DEPENDENCIES_PREFIX = "catalog.dependencies.";
    private static final String PROP_PROPERTIES_PREFIX = "catalog.properties.";

    /**
     * Holds a parsed copy of the sizeable Camel YAML DSL JSON schema, so a single instance is shared.
     * It is immutable once initialized and safe to use concurrently.
     */
    private static final YamlValidator YAML_VALIDATOR = createYamlValidator();

    private static YamlValidator createYamlValidator() {
        YamlValidator validator = new YamlValidator();
        try {
            // Parses the schema upfront, instead of on the first request
            validator.init();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize the Camel YAML DSL validator", e);
        }
        return validator;
    }

    /**
     * Validate a service catalog package.
     *
     * @param dataStore the data store entry containing the Base64-encoded ZIP
     * @return the validation result
     * @throws InvalidPayloadException if the payload carries no data to validate
     */
    public ValidationResult validateCatalog(DataStore dataStore) {
        return validate(dataStore, ValidationResult.TYPE_CATALOG);
    }

    /**
     * Validate a service template package.
     *
     * @param dataStore the data store entry containing the Base64-encoded ZIP
     * @return the validation result
     * @throws InvalidPayloadException if the payload carries no data to validate
     */
    public ValidationResult validateTemplate(DataStore dataStore) {
        return validate(dataStore, ValidationResult.TYPE_TEMPLATE);
    }

    private ValidationResult validate(DataStore dataStore, String type) {
        if (dataStore == null || StringHelper.isBlank(dataStore.getData())) {
            throw new InvalidPayloadException("Package data (Base64-encoded ZIP) is required");
        }

        LOG.debugf("Validating service %s: %s", type, dataStore.getName());

        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();

        if (StringHelper.isBlank(dataStore.getName())) {
            errors.add(new ValidationIssue("name", "The package name is required"));
        }

        Map<String, byte[]> entries;
        try {
            entries = CatalogZipReader.readEntries(SafeZip.decodeArchive(dataStore.getData()));
        } catch (WanakuException e) {
            errors.add(new ValidationIssue("data", e.getMessage()));
            return result(type, null, errors, warnings);
        }

        byte[] indexBytes = entries.get(INDEX_FILE);
        if (indexBytes == null) {
            errors.add(new ValidationIssue(
                    INDEX_FILE, "The package does not contain an %s manifest".formatted(INDEX_FILE)));
            return result(type, null, errors, warnings);
        }

        Properties props = new Properties();
        try {
            props.load(new ByteArrayInputStream(indexBytes));
        } catch (IOException e) {
            errors.add(new ValidationIssue(INDEX_FILE, "Failed to parse the manifest: %s".formatted(e.getMessage())));
            return result(type, null, errors, warnings);
        }

        String name = requireProperty(props, PROP_NAME, errors);
        requireProperty(props, PROP_DESCRIPTION, errors);
        String services = requireProperty(props, PROP_SERVICES, errors);

        if (props.getProperty(PROP_ICON) == null) {
            warnings.add(new ValidationIssue(
                    field(PROP_ICON),
                    "Optional property '%s' is not set: a default icon will be used".formatted(PROP_ICON)));
        }

        List<String> systems = parseSystems(services);
        if (services != null && systems.isEmpty()) {
            errors.add(new ValidationIssue(
                    field(PROP_SERVICES), "Property '%s' must list at least one system".formatted(PROP_SERVICES)));
        }

        boolean anyProperties = false;
        for (String system : systems) {
            String routes = checkFileReference(props, PROP_ROUTES_PREFIX + system, entries, true, errors);
            checkFileReference(props, PROP_DEPENDENCIES_PREFIX + system, entries, false, errors);

            checkCamelRoutes(routes, entries, errors);

            String propertiesKey = PROP_PROPERTIES_PREFIX + system;
            if (ValidationResult.TYPE_CATALOG.equals(type) && props.getProperty(propertiesKey) != null) {
                warnings.add(new ValidationIssue(
                        field(propertiesKey),
                        "A service catalog declares '%s': packages with parameterized properties are meant to be deployed as service templates"
                                .formatted(propertiesKey)));
            }

            String propertiesPath = resolvePropertiesPath(props, system, entries, errors);
            if (propertiesPath != null) {
                anyProperties = true;
                checkProperties(propertiesPath, entries, errors);
            }
        }

        if (ValidationResult.TYPE_TEMPLATE.equals(type) && !systems.isEmpty() && !anyProperties) {
            errors.add(new ValidationIssue(
                    INDEX_FILE,
                    "A service template must provide a properties file for at least one system, either declared as '%s<system>' or placed at '<system>/service.properties'"
                            .formatted(PROP_PROPERTIES_PREFIX)));
        }

        return result(type, name, errors, warnings);
    }

    private static ValidationResult result(
            String type, String name, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        return new ValidationResult(type, name, errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    private static String requireProperty(Properties props, String key, List<ValidationIssue> errors) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            errors.add(new ValidationIssue(
                    field(key), "Required property '%s' is missing or empty in %s".formatted(key, INDEX_FILE)));
            return null;
        }
        return value.trim();
    }

    private static List<String> parseSystems(String services) {
        List<String> systems = new ArrayList<>();
        if (services == null) {
            return systems;
        }
        for (String system : services.split(",")) {
            String trimmed = system.trim();
            if (!trimmed.isEmpty()) {
                systems.add(trimmed);
            }
        }
        return systems;
    }

    /**
     * Checks that a file declared in the manifest has a safe path and is present in the archive.
     *
     * @return the declared path when it points to a file in the archive, otherwise null
     */
    private static String checkFileReference(
            Properties props, String key, Map<String, byte[]> entries, boolean required, List<ValidationIssue> errors) {
        String path = props.getProperty(key);
        if (path == null || path.isBlank()) {
            if (required) {
                errors.add(new ValidationIssue(
                        field(key), "Required property '%s' is missing or empty in %s".formatted(key, INDEX_FILE)));
            }
            return null;
        }

        path = path.trim();
        try {
            ServiceCatalogIndex.validateZipEntryPath(path);
        } catch (WanakuException e) {
            errors.add(new ValidationIssue(field(key), e.getMessage()));
            return null;
        }

        if (!entries.containsKey(path)) {
            errors.add(new ValidationIssue(
                    field(key), "Referenced file '%s' is not present in the package".formatted(path)));
            return null;
        }
        return path;
    }

    /**
     * Resolves the properties file of a system, either declared in the manifest or found by convention.
     */
    private static String resolvePropertiesPath(
            Properties props, String system, Map<String, byte[]> entries, List<ValidationIssue> errors) {
        String declared = checkFileReference(props, PROP_PROPERTIES_PREFIX + system, entries, false, errors);
        if (declared != null) {
            return declared;
        }

        String conventional = system + "/service.properties";
        return entries.containsKey(conventional) ? conventional : null;
    }

    /**
     * Validates a route file with Camel's own YAML DSL validator, which checks both that the file is
     * parseable YAML and that it conforms to the Camel YAML DSL schema.
     */
    private static void checkCamelRoutes(String path, Map<String, byte[]> entries, List<ValidationIssue> errors) {
        if (path == null) {
            return;
        }

        // The Camel validator reads from a file, so the entry is written to a temporary one
        Path file = null;
        try {
            file = Files.createTempFile("wanaku-validate-", ".camel.yaml");
            Files.write(file, entries.get(path));

            for (ValidationMessage message : YAML_VALIDATOR.validate(file.toFile())) {
                errors.add(new ValidationIssue(issuePath(path, message), describe(message)));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to validate route file '%s': %s", path, e.getMessage());
            errors.add(new ValidationIssue(path, "Failed to validate the route file: %s".formatted(e.getMessage())));
        } finally {
            deleteQuietly(file);
        }
    }

    private static String issuePath(String path, ValidationMessage message) {
        String location = location(message);
        return location == null ? path : "%s#%s".formatted(path, location);
    }

    private static String describe(ValidationMessage message) {
        String text = message.getMessage();
        String location = location(message);

        // The location is already carried by the issue path, so it is not repeated in the message
        if (location != null && text != null && text.startsWith(location + ": ")) {
            text = text.substring(location.length() + 2);
        }

        // Messages of type 'parser' are produced when the file cannot be read as YAML at all
        String problem = "parser".equals(message.getType()) ? "Invalid YAML" : "Invalid Camel YAML DSL";
        return "%s: %s".formatted(problem, text);
    }

    private static String location(ValidationMessage message) {
        if (message.getInstanceLocation() == null) {
            return null;
        }
        String location = message.getInstanceLocation().toString();
        return location.isEmpty() || "$".equals(location) ? null : location;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warnf("Failed to delete the temporary file '%s': %s", file, e.getMessage());
        }
    }

    private static void checkProperties(String path, Map<String, byte[]> entries, List<ValidationIssue> errors) {
        try {
            new Properties().load(new ByteArrayInputStream(entries.get(path)));
        } catch (IOException | IllegalArgumentException e) {
            errors.add(new ValidationIssue(path, "Invalid properties file: %s".formatted(e.getMessage())));
        }
    }

    private static String field(String key) {
        return "%s#%s".formatted(INDEX_FILE, key);
    }
}
