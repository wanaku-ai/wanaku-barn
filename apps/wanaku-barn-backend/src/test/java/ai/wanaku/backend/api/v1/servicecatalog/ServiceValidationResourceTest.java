package ai.wanaku.backend.api.v1.servicecatalog;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import ai.wanaku.backend.support.NoOidcTestProfile;
import ai.wanaku.backend.support.WanakuRouterTest;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static ai.wanaku.test.assertions.WanakuAssertions.assertHttpStatus;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import org.junit.jupiter.api.Test;

/**
 * Verifies the HTTP contract of the service catalog and service template validation endpoints.
 */
@QuarkusTest
@TestProfile(NoOidcTestProfile.class)
public class ServiceValidationResourceTest extends WanakuRouterTest {

    private static final String CATALOG_PATH = "/api/v1/service-catalog/validate";
    private static final String TEMPLATE_PATH = "/api/v1/service-template/validate";

    @Test
    void testValidCatalogReturnsOk() {
        Response response = validate(CATALOG_PATH, packageOf(index(), files()));

        assertHttpStatus(response, 200);
        response.then()
                .body("data.valid", equalTo(true))
                .body("data.type", equalTo("catalog"))
                .body("data.name", equalTo("sample"))
                .body("data.errors.size()", equalTo(0));
    }

    @Test
    void testInvalidCatalogReturnsOkWithErrors() {
        // The routes file declared in the manifest is missing from the package
        Response response = validate(CATALOG_PATH, packageOf(index(), Map.of()));

        assertHttpStatus(response, 200);
        response.then()
                .body("data.valid", equalTo(false))
                .body("data.errors.size()", greaterThanOrEqualTo(1))
                .body("data.errors[0].path", containsString("index.properties#catalog.routes.sample"))
                .body("data.errors[0].message", containsString("not present in the package"));
    }

    @Test
    void testCatalogWithoutDataReturnsUnprocessableEntity() {
        DataStore dataStore = new DataStore();
        dataStore.setName("sample.zip");

        Response response = validate(CATALOG_PATH, dataStore);

        assertHttpStatus(response, 422);
        response.then().body("error.message", containsString("required"));
    }

    @Test
    void testValidTemplateReturnsOk() {
        Map<String, String> files = new LinkedHashMap<>(files());
        files.put("sample/service.properties", "broker.url=\n");
        String index = index() + "catalog.properties.sample=sample/service.properties\n";

        Response response = validate(TEMPLATE_PATH, packageOf(index, files));

        assertHttpStatus(response, 200);
        response.then().body("data.valid", equalTo(true)).body("data.type", equalTo("template"));
    }

    @Test
    void testTemplateWithoutPropertiesReturnsOkWithErrors() {
        Response response = validate(TEMPLATE_PATH, packageOf(index(), files()));

        assertHttpStatus(response, 200);
        response.then()
                .body("data.valid", equalTo(false))
                .body("data.errors[0].message", containsString("must provide a properties file"));
    }

    private Response validate(String path, DataStore dataStore) {
        return given().headers(getHeaders()).body(dataStore).when().post(path);
    }

    private static String index() {
        return """
                catalog.name=sample
                catalog.description=A sample catalog
                catalog.services=sample
                catalog.routes.sample=sample/sample.camel.yaml
                """;
    }

    private static Map<String, String> files() {
        return Map.of(
                "sample/sample.camel.yaml",
                """
                - route:
                    id: sample
                    from:
                      uri: "ai-tool:sample"
                      steps:
                        - log:
                            message: "Handling ${body}"
                """);
    }

    private static DataStore packageOf(String index, Map<String, String> files) {
        Map<String, String> entries = new LinkedHashMap<>(files);
        entries.put("index.properties", index);

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

        DataStore dataStore = new DataStore();
        dataStore.setName("sample.zip");
        dataStore.setData(Base64.getEncoder().encodeToString(baos.toByteArray()));
        return dataStore;
    }
}
