package ai.wanaku.backend.api.v1.management.backup;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogLifecycle;
import ai.wanaku.backend.audit.AuditStore;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class BackupResourceTest {

    @Inject
    ObjectMapper mapper;

    @Inject
    DataStoreRepository repository;

    @Inject
    CatalogLifecycle lifecycle;

    @Inject
    AuditStore auditStore;

    @Inject
    BackupBean backup;

    private static String exportJson(boolean includeAudit) {
        return given().queryParam("includeAudit", includeAudit)
                .get("/api/v1/management/export")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .prettify();
    }

    private ObjectNode archive(boolean includeAudit) throws Exception {
        return (ObjectNode) mapper.readTree(exportJson(includeAudit)).get("data");
    }

    private static void importArchive(Object archive, boolean replace, int status) {
        given().contentType(ContentType.JSON)
                .queryParam("replace", replace)
                .body(archive.toString())
                .post("/api/v1/management/import")
                .then()
                .statusCode(status);
    }

    private static void deploy(String name, String content) {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, name + ".zip", CatalogZips.base64(name, content, "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(200);
    }

    @Test
    void roundTripRestoresDataVersionsAndAudit() throws Exception {
        deploy("backup-catalog", "v1");
        deploy("backup-catalog", "v2");
        String plainId = given().contentType(ContentType.JSON)
                .body(new DataStore(null, "backup-entry", "kept"))
                .post("/api/v1/data-store")
                .then()
                .extract()
                .path("data.id");
        ObjectNode exported = archive(true);
        long auditSequence = exported.get("auditSequence").asLong();
        assertThat(exported.get("formatVersion").asInt()).isEqualTo(1);

        // Change the state after the export
        given().delete("/api/v1/data-store/{id}", plainId).then().statusCode(200);
        deploy("backup-catalog", "v3");
        deploy("after-export", "x");

        importArchive(exported, false, 409);
        importArchive(exported, true, 200);

        given().get("/api/v1/data-store/{id}", plainId)
                .then()
                .statusCode(200)
                .body("data.data", equalTo("kept"))
                .body("data.revision", equalTo(1));
        assertThat(lifecycle.versions("catalog", "backup-catalog")).hasSize(2);
        given().get("/api/v1/service-catalog/{name}", "after-export").then().statusCode(404);
        // Version numbers continue from the imported counters
        deploy("backup-catalog", "v3 again");
        assertThat(lifecycle.versions("catalog", "backup-catalog").get(0).getVersion())
                .isEqualTo(3);

        // The audit trail is the imported one, followed by the import event
        JsonPath imported = given().queryParam("operation", "barn.import")
                .get("/api/v1/audit/events")
                .then()
                .extract()
                .jsonPath();
        assertThat(imported.getLong("data.events[0].sequence")).isEqualTo(auditSequence + 1);
        assertThat(auditStore.allEvents().get(0).getTimestamp())
                .isEqualTo(exported.get("auditEvents").get(0).get("timestamp").asText());
    }

    @Test
    void importWithoutAuditKeepsTheCurrentAuditTrail() throws Exception {
        ObjectNode exported = archive(false);
        assertThat(exported.get("auditEvents").isNull()).isTrue();
        long before = auditStore.lastSequence();

        importArchive(exported, true, 200);

        assertThat(auditStore.lastSequence()).isGreaterThan(before);
    }

    @Test
    void invalidArchivesAreRejectedWithoutChanges() throws Exception {
        ObjectNode valid = archive(false);
        int entries = repository.listAll().size();

        ObjectNode wrongFormat = valid.deepCopy().put("formatVersion", 2);
        given().contentType(ContentType.JSON)
                .queryParam("replace", true)
                .body(wrongFormat.toString())
                .post("/api/v1/management/import")
                .then()
                .statusCode(400)
                .body("error.message", containsString("format"));

        importArchive(valid.deepCopy().put("schemaVersion", 99), true, 400);

        ObjectNode duplicate = valid.deepCopy();
        duplicate.withArray("dataStores").add(duplicate.withArray("dataStores").get(0));
        importArchive(duplicate, true, 400);

        importArchive("{ not json", true, 400);

        assertThat(repository.listAll()).hasSize(entries);
    }

    @Test
    void olderArchivesAreMigrated() throws Exception {
        ObjectNode exported = archive(false);
        ObjectNode legacyCatalog = mapper.createObjectNode()
                .put("id", "legacy-import")
                .put("name", "legacy.zip")
                .put("data", CatalogZips.base64("legacy-import", "old", "sys"));
        legacyCatalog.putObject("labels").put("wanaku.type", "catalog");
        exported.withArray("dataStores").add(legacyCatalog);
        exported.put("schemaVersion", 0);

        importArchive(exported, true, 200);

        DataStore migrated = lifecycle.find("catalog", "legacy-import");
        assertThat(migrated.getId()).isEqualTo("legacy-import");
        assertThat(CatalogLifecycle.activeVersion(migrated)).isEqualTo(1);
    }

    @Test
    void anInstanceWithOnlyBuiltInTemplatesIsEmpty() {
        assertThat(backup.isEffectivelyEmpty()).isFalse();

        BarnArchive empty = new BarnArchive(1, 1, null, List.of(), List.of(), Map.of(), null, null);
        backup.importArchive(empty, true);
        assertThat(backup.isEffectivelyEmpty()).isTrue();
    }
}
