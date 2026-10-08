package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CatalogRemovalTest {

    @Inject
    CatalogLifecycle lifecycle;

    @Inject
    ServiceTemplateInitializer initializer;

    private static String deploy(String name) {
        return given().contentType(ContentType.JSON)
                .body(new DataStore(null, name + ".service.zip", CatalogZips.base64(name, name, "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(200)
                .extract()
                .path("data.id");
    }

    private static void remove(String name) {
        given().delete("/api/v1/service-catalog/{name}", name).then().statusCode(200);
    }

    @Test
    void removedCatalogsAreHiddenButKeepTheirHistory() {
        deploy("hidden");
        deploy("hidden");
        remove("hidden");

        given().get("/api/v1/service-catalog").then().statusCode(200).body("data.name", not(hasItem("hidden")));
        given().get("/api/v1/service-catalog/{name}", "hidden").then().statusCode(404);
        given().queryParam("name", "hidden")
                .get("/api/v1/service-catalog/download")
                .then()
                .statusCode(404);

        JsonPath removed =
                given().get("/api/v1/service-catalog/removed").then().extract().jsonPath();
        assertThat(removed.getList("data.name", String.class)).contains("hidden");
        Map<String, Object> entry = removed.getList("data", Map.class).stream()
                .filter(row -> "hidden".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(Instant.parse((String) entry.get("removedAt"))).isNotNull();
        assertThat(entry.get("version")).isEqualTo(2);

        given().get("/api/v1/service-catalog/{name}/versions", "hidden")
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(2));
        given().get("/api/v1/service-catalog/{name}/versions/{version}/download", "hidden", 1)
                .then()
                .statusCode(200);
    }

    @Test
    void restoreBringsBackTheSameEntryWithoutANewVersion() {
        String id = deploy("comeback");
        remove("comeback");

        given().post("/api/v1/service-catalog/{name}/restore", "comeback")
                .then()
                .statusCode(200)
                .body("data.id", equalTo(id))
                .body("data.labels.'wanaku.version'", equalTo("1"));

        given().get("/api/v1/service-catalog/{name}", "comeback").then().statusCode(200);
        given().get("/api/v1/service-catalog/{name}/versions", "comeback")
                .then()
                .body("data.size()", equalTo(1))
                .body("data[0].status", equalTo("active"));
        given().post("/api/v1/service-catalog/{name}/restore", "comeback")
                .then()
                .statusCode(404);
    }

    @Test
    void removedNamesAreReservedUntilRestore() {
        deploy("reserved");
        remove("reserved");

        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "reserved.service.zip", CatalogZips.base64("reserved", "new", "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(409);
        given().delete("/api/v1/service-catalog/{name}", "reserved").then().statusCode(404);
    }

    @Test
    void genericDeletesCannotBypassTheLifecycle() {
        String id = deploy("guarded-removal");
        remove("guarded-removal");

        given().delete("/api/v1/data-store/{id}", id).then().statusCode(409);
        given().queryParam("name", "guarded-removal.service.zip")
                .delete("/api/v1/data-store")
                .then()
                .statusCode(409);
        given().queryParam("labelExpression", "wanaku.type=catalog.removed")
                .delete("/api/v1/data-store/labels")
                .then()
                .statusCode(409);

        String active = deploy("guarded-active");
        given().delete("/api/v1/data-store/{id}", active).then().statusCode(409);

        assertThat(lifecycle.removed("catalog")).extracting(DataStore::getId).contains(id);
    }

    @Test
    void removedBuiltInTemplatesAreNotSeededAgain() {
        initializer.loadBuiltInTemplates(null);
        given().queryParam("name", "aws-s3-resource")
                .delete("/api/v1/service-template/remove")
                .then()
                .statusCode(200);

        initializer.loadBuiltInTemplates(null);

        given().queryParam("name", "aws-s3-resource")
                .get("/api/v1/service-template/get")
                .then()
                .statusCode(404);
        given().get("/api/v1/service-template/removed").then().body("data.name", hasItem("aws-s3-resource"));
        given().queryParam("name", "aws-s3-resource")
                .post("/api/v1/service-template/restore")
                .then()
                .statusCode(200);
        given().queryParam("name", "aws-s3-resource")
                .get("/api/v1/service-template/get")
                .then()
                .statusCode(200);
    }

    @Test
    void removedTemplatesCannotBeInstantiated() {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "gone.zip", CatalogZips.base64("gone-template", "t", "sys")))
                .post("/api/v1/service-template/deploy")
                .then()
                .statusCode(200);
        given().queryParam("name", "gone-template")
                .delete("/api/v1/service-template/remove")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body(Map.of("templateName", "gone-template", "serviceName", "from-gone"))
                .post("/api/v1/service-template/instantiate")
                .then()
                .statusCode(404);
    }

    @Test
    void purgeDeletesOnlyEntriesRemovedAtOrBeforeTheCutoff() {
        deploy("purge-old");
        remove("purge-old");
        Instant cutoff = CatalogLifecycle.removedAt(lifecycle.findAny("catalog", "purge-old"));
        deploy("purge-new");
        remove("purge-new");
        Instant newer = CatalogLifecycle.removedAt(lifecycle.findAny("catalog", "purge-new"));

        // Purge is disabled by default
        lifecycle.purgeExpired();
        assertThat(lifecycle.findAny("catalog", "purge-old")).isNotNull();

        int purged = lifecycle.purgeRemovedBefore("catalog", cutoff);
        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(lifecycle.findAny("catalog", "purge-old")).isNull();
        given().get("/api/v1/service-catalog/{name}/versions", "purge-old")
                .then()
                .statusCode(404);
        if (newer.isAfter(cutoff)) {
            assertThat(lifecycle.findAny("catalog", "purge-new")).isNotNull();
        }

        given().queryParam("operation", "service_catalog.purge")
                .queryParam("target", "purge-old")
                .get("/api/v1/audit/events")
                .then()
                .body("data.total", equalTo(1))
                .body("data.events[0].protocol", equalTo("scheduler"));

        // A purged name can be deployed again
        deploy("purge-old");
    }

    @Test
    void removalAndRestoreAreAudited() {
        deploy("audited-removal");
        given().delete("/api/v1/service-catalog/{name}", "audited-removal")
                .then()
                .statusCode(200);
        given().post("/api/v1/service-catalog/{name}/restore", "audited-removal")
                .then()
                .statusCode(200);

        // Newest first: the restore, then the removal
        given().queryParam("target_type", "service_catalog")
                .queryParam("target", "audited-removal")
                .get("/api/v1/audit/events")
                .then()
                .body("data.events[0].operation", equalTo("service_catalog.restore"))
                .body("data.events[1].operation", equalTo("service_catalog.remove"));
    }
}
