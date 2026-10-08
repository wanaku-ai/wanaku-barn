package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.RevisionConflictException;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.CatalogVersion;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CatalogVersioningTest {

    @Inject
    CatalogLifecycle lifecycle;

    @Inject
    ServiceCatalogBean catalogs;

    @Inject
    DataStoreRepository repository;

    private static JsonPath deploy(String name, String content, Long expectedVersion) {
        var request = given().contentType(ContentType.JSON)
                .body(new DataStore(null, name + ".service.zip", CatalogZips.base64(name, content, "sys")));
        if (expectedVersion != null) {
            request.queryParam("expectedVersion", expectedVersion);
        }
        return request.post("/api/v1/service-catalog")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static JsonPath versions(String name) {
        return given().get("/api/v1/service-catalog/{name}/versions", name)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void redeployKeepsTheIdentifierAndAddsAVersion() throws Exception {
        JsonPath first = deploy("versioned", "first", null);
        JsonPath second = deploy("versioned", "second", null);

        assertThat(second.getString("data.id")).isEqualTo(first.getString("data.id"));
        assertThat(first.getString("data.labels.'wanaku.version'")).isEqualTo("1");
        assertThat(second.getString("data.labels.'wanaku.version'")).isEqualTo("2");

        JsonPath history = versions("versioned");
        assertThat(history.getList("data.version", Integer.class)).containsExactly(2, 1);
        assertThat(history.getList("data.status", String.class)).containsExactly("active", "superseded");
        assertThat(history.getString("data[0].origin")).isEqualTo("api");
        assertThat(history.getString("data[0].dataStoreName")).isEqualTo("versioned.service.zip");
        assertThat(history.getString("data[1].activatedAt")).isNotNull();

        byte[] secondZip = Base64.getDecoder().decode(second.getString("data.data"));
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secondZip));
        assertThat(history.getString("data[0].checksum")).isEqualTo(expected);

        given().get("/api/v1/service-catalog/{name}/versions/{version}", "versioned", 1)
                .then()
                .statusCode(200)
                .body("data.status", equalTo("superseded"));
        given().get("/api/v1/service-catalog/{name}/versions/{version}/download", "versioned", 1)
                .then()
                .statusCode(200)
                .body("data.data", equalTo(first.getString("data.data")));
        given().get("/api/v1/service-catalog/{name}/versions/{version}", "versioned", 9)
                .then()
                .statusCode(404);
    }

    @Test
    void restoreCreatesANewVersionWithTheEarlierContent() {
        JsonPath first = deploy("restorable", "first", null);
        deploy("restorable", "second", null);

        given().post("/api/v1/service-catalog/{name}/versions/{version}/activate", "restorable", 1)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(first.getString("data.id")))
                .body("data.data", equalTo(first.getString("data.data")))
                .body("data.labels.'wanaku.version'", equalTo("3"));

        JsonPath history = versions("restorable");
        assertThat(history.getList("data.version", Integer.class)).containsExactly(3, 2, 1);
        assertThat(history.getString("data[0].origin")).isEqualTo("restore");
        assertThat(history.getInt("data[0].restoredFrom")).isEqualTo(1);
        assertThat(history.getString("data[2].checksum")).isEqualTo(history.getString("data[0].checksum"));

        given().get("/api/v1/service-catalog/download?name=restorable")
                .then()
                .body("data.data", equalTo(first.getString("data.data")));
    }

    @Test
    void staleDeploysAndRestoresConflictWithoutConsumingAVersion() {
        deploy("guarded", "first", 0L);
        deploy("guarded", "second", 1L);

        given().contentType(ContentType.JSON)
                .queryParam("expectedVersion", 1)
                .body(new DataStore(null, "guarded.service.zip", CatalogZips.base64("guarded", "stale", "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(409);
        given().queryParam("expectedVersion", 1)
                .post("/api/v1/service-catalog/{name}/versions/{version}/activate", "guarded", 1)
                .then()
                .statusCode(409);

        assertThat(versions("guarded").getList("data.version", Integer.class)).containsExactly(2, 1);
        assertThat(deploy("guarded", "third", 2L).getString("data.labels.'wanaku.version'"))
                .isEqualTo("3");
    }

    @Test
    void concurrentDeploysFromTheSameVersionAllowOneWinner() throws Exception {
        catalogs.deploy(new DataStore(null, "race.zip", CatalogZips.base64("race", "base", "sys")));
        int writers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String content = "writer-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        catalogs.deploy(
                                new DataStore(null, "race.zip", CatalogZips.base64("race", content, "sys")),
                                CatalogLifecycle.ORIGIN_API,
                                1L);
                        return true;
                    } catch (RevisionConflictException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(lifecycle.versions("catalog", "race")).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deployingOverAnImmutableCatalogIsRejectedAndKeepsNoContent() {
        DataStore published = new DataStore(null, "frozen.zip", CatalogZips.base64("frozen", "published", "sys"));
        published.setLabels(Map.of("semantic.immutable", "true"));
        catalogs.deploy(published);

        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "frozen.zip", CatalogZips.base64("frozen", "changed", "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(500);

        List<CatalogVersion> history = lifecycle.versions("catalog", "frozen");
        assertThat(history).extracting(CatalogVersion::getStatus).containsExactly("rejected", "active");
        assertThat(history.get(0).getFailureReason()).isEqualTo("immutable");
        given().get("/api/v1/service-catalog/{name}/versions/{version}/download", "frozen", 2)
                .then()
                .statusCode(422);
        given().post("/api/v1/service-catalog/{name}/versions/{version}/activate", "frozen", 2)
                .then()
                .statusCode(422);
        assertThat(CatalogLifecycle.activeVersion(catalogs.get("frozen"))).isEqualTo(1);
    }

    @Test
    void versionNumbersContinueAfterRemovalAndRestore() {
        deploy("recreated", "first", null);
        given().delete("/api/v1/service-catalog/{name}", "recreated").then().statusCode(200);
        given().post("/api/v1/service-catalog/{name}/restore", "recreated")
                .then()
                .statusCode(200);

        assertThat(deploy("recreated", "again", null).getString("data.labels.'wanaku.version'"))
                .isEqualTo("2");
        assertThat(versions("recreated").getList("data.status", String.class)).containsExactly("active", "superseded");
    }

    @Test
    void legacyEntriesReceiveAnInitialVersionOnce() {
        DataStore legacy = new DataStore(null, "legacy.zip", CatalogZips.base64("legacy", "old", "sys"));
        legacy.setLabels(Map.of("wanaku.type", "catalog"));
        String id = repository.persist(legacy).getId();

        lifecycle.migrateLegacyEntries(null);
        lifecycle.migrateLegacyEntries(null);

        List<CatalogVersion> history = lifecycle.versions("catalog", "legacy");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getOrigin()).isEqualTo("legacy");
        assertThat(history.get(0).getStatus()).isEqualTo("active");
        assertThat(catalogs.get("legacy").getId()).isEqualTo(id);
    }

    @Test
    void instantiationCreatesACatalogVersion() {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "tmpl.zip", CatalogZips.base64("version-template", "t", "sys")))
                .post("/api/v1/service-template/deploy")
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .body(Map.of("templateName", "version-template", "serviceName", "from-template"))
                .post("/api/v1/service-template/instantiate")
                .then()
                .statusCode(200);

        assertThat(lifecycle.versions("catalog", "from-template").get(0).getOrigin())
                .isEqualTo("instantiate");
    }

    @Test
    void templateVersionEndpoints() {
        String v1 = CatalogZips.base64("versioned-template", "one", "sys");
        for (String content : List.of(v1, CatalogZips.base64("versioned-template", "two", "sys"))) {
            given().contentType(ContentType.JSON)
                    .body(new DataStore(null, "vt.zip", content))
                    .post("/api/v1/service-template/deploy")
                    .then()
                    .statusCode(200);
        }

        given().queryParam("name", "versioned-template")
                .get("/api/v1/service-template/versions")
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(2))
                .body("data[0].type", equalTo("template"));
        given().queryParam("name", "versioned-template")
                .queryParam("version", 1)
                .get("/api/v1/service-template/versions/get")
                .then()
                .body("data.status", equalTo("superseded"));
        given().queryParam("name", "versioned-template")
                .queryParam("version", 1)
                .get("/api/v1/service-template/versions/download")
                .then()
                .body("data.data", equalTo(v1));
        given().queryParam("name", "versioned-template")
                .queryParam("version", 1)
                .queryParam("expectedVersion", 2)
                .post("/api/v1/service-template/versions/activate")
                .then()
                .statusCode(200)
                .body("data.labels.'wanaku.version'", equalTo("3"));
        given().queryParam("name", "no-such-template")
                .get("/api/v1/service-template/versions")
                .then()
                .statusCode(404);
    }

    @Test
    void genericDataStoreWritesCannotChangeCatalogContent() {
        String id = deploy("protected", "first", null).getString("data.id");

        given().contentType(ContentType.JSON)
                .body(new DataStore(id, "protected.service.zip", "changed"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(409);
        DataStore labelled = new DataStore(null, "sneaky", CatalogZips.base64("sneaky", "x", "sys"));
        labelled.setLabels(Map.of("wanaku.type", "catalog"));
        given().contentType(ContentType.JSON)
                .body(labelled)
                .post("/api/v1/data-store")
                .then()
                .statusCode(409);
    }

    @Test
    void deployAndRestoreRecordThePolicyRevision() {
        deploy("audited-version", "first", null);
        given().header("x-request-id", "version-audit")
                .post("/api/v1/service-catalog/{name}/versions/{version}/activate", "audited-version", 1)
                .then()
                .statusCode(200);

        given().queryParam("correlation_id", "version-audit")
                .get("/api/v1/audit/events")
                .then()
                .body("data.events[0].operation", equalTo("service_catalog.activate_version"))
                .body("data.events[0].policy_revision", equalTo("2"))
                .body("data.events[0].target", equalTo("audited-version"));
    }
}
