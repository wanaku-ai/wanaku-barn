package ai.wanaku.backend.audit;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AuditTrailTest {

    @Inject
    AuditStore store;

    @BeforeEach
    void clean() {
        store.clear();
    }

    /** The newest events first; the store is cleared before each test. */
    private static JsonPath events() {
        return given().get("/api/v1/audit/events")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static String createDataStore(String name) {
        return given().contentType(ContentType.JSON)
                .body(new DataStore(null, name, "content"))
                .post("/api/v1/data-store")
                .then()
                .statusCode(200)
                .extract()
                .path("data.id");
    }

    @Test
    void createRecordsOneEventWithTheWanakuSchema() {
        String id = createDataStore("audit-create");

        JsonPath page = events();
        assertThat(page.getInt("data.total")).isEqualTo(1);
        String eventId = page.getString("data.events[0].event_id");

        Map<String, Object> event = given().get("/api/v1/audit/events/{id}", eventId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("data");
        assertThat(event)
                .containsEntry("schema_version", "1.0")
                .containsEntry("stream_id", "barn")
                .containsEntry("category", "administrative")
                .containsEntry("decision", "allow")
                .containsEntry("reason_code", "completed")
                .containsEntry("protocol", "http")
                .containsEntry("operation", "data_store.create")
                .containsEntry("target_type", "data_store")
                .containsEntry("target", id)
                .containsEntry("correlation_id", eventId)
                .containsEntry("response_status", 200)
                .containsEntry("coverage_complete", true)
                .containsEntry("dropped_events", 0)
                .containsKeys("sequence", "duration_ms", "redaction", "attributes")
                .doesNotContainKeys("actor", "request_id", "payload", "timestamp_millis");
        assertThat(Instant.parse((String) event.get("timestamp"))).isNotNull();
        assertThat((Map<String, Object>) event.get("attributes"))
                .containsEntry("http_method", "POST")
                .containsEntry("revision", "1");
        assertThat((Map<String, Object>) event.get("redaction")).containsEntry("payload_captured", false);
    }

    @Test
    void everyDataStoreMutationIsRecordedOnce() {
        String id = createDataStore("audit-mutations");

        given().header("If-Match", "\"1\"")
                .contentType(ContentType.JSON)
                .body(new DataStore(id, "audit-mutations", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(200);
        JsonPath update = events();
        assertThat(update.getInt("data.total")).isEqualTo(2);
        assertThat(update.getString("data.events[0].operation")).isEqualTo("data_store.update");
        assertThat(update.getString("data.events[0].target")).isEqualTo(id);
        assertThat(update.getString("data.events[0].attributes.revision")).isEqualTo("2");

        given().delete("/api/v1/data-store/{id}", id).then().statusCode(200);
        assertThat(events().getString("data.events[0].operation")).isEqualTo("data_store.delete");

        createDataStore("audit-by-name");
        given().queryParam("name", "audit-by-name")
                .delete("/api/v1/data-store")
                .then()
                .statusCode(200);
        JsonPath byName = events();
        assertThat(byName.getString("data.events[0].operation")).isEqualTo("data_store.delete_by_name");
        assertThat(byName.getString("data.events[0].target")).isEqualTo("audit-by-name");

        given().queryParam("labelExpression", "audit=nothing-matches")
                .delete("/api/v1/data-store/labels")
                .then()
                .statusCode(200);
        JsonPath byLabels = events();
        assertThat(byLabels.getString("data.events[0].operation")).isEqualTo("data_store.delete_by_labels");
        assertThat(byLabels.getString("data.events[0].target")).isEqualTo("audit=nothing-matches");
        assertThat(byLabels.getString("data.events[0].attributes.count")).isEqualTo("0");
    }

    @Test
    void rejectedOperationsAreRecorded() {
        String id = createDataStore("audit-rejections");

        given().header("If-Match", "\"9\"")
                .contentType(ContentType.JSON)
                .body(new DataStore(id, "audit-rejections", "stale"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(409);
        JsonPath conflict = events();
        assertThat(conflict.getString("data.events[0].decision")).isEqualTo("reject_malformed");
        assertThat(conflict.getString("data.events[0].reason_code")).isEqualTo("conflict");
        assertThat(conflict.getInt("data.events[0].response_status")).isEqualTo(409);
        assertThat(conflict.getString("data.events[0].target")).isEqualTo(id);

        given().delete("/api/v1/data-store/{id}", "missing-entry").then().statusCode(404);
        JsonPath missing = events();
        assertThat(missing.getString("data.events[0].reason_code")).isEqualTo("not_found");
        assertThat(missing.getString("data.events[0].target")).isEqualTo("missing-entry");

        given().header("If-Match", "\"abc\"")
                .contentType(ContentType.JSON)
                .body(new DataStore(id, "audit-rejections", "invalid"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(400);
        assertThat(events().getString("data.events[0].reason_code")).isEqualTo("invalid_request");
    }

    @Test
    void readsAreNotRecorded() {
        given().get("/api/v1/data-store").then().statusCode(200);
        given().get("/api/v1/audit/events").then().statusCode(200);

        assertThat(events().getInt("data.total")).isZero();
    }

    @Test
    void nestedInstantiationIsRecordedOnce() {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "audit-template.zip", CatalogZips.base64("audit-template", "t", "sys")))
                .post("/api/v1/service-template/deploy")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body(Map.of("templateName", "audit-template", "properties", Map.of(), "serviceName", "audit-inst"))
                .post("/api/v1/service-template/instantiate")
                .then()
                .statusCode(200);

        // The deploy and the instantiation, without events for the nested writes
        JsonPath page = events();
        assertThat(page.getInt("data.total")).isEqualTo(2);
        assertThat(page.getString("data.events[0].operation")).isEqualTo("service_template.instantiate");
        assertThat(page.getString("data.events[0].target")).isEqualTo("audit-template");

        given().delete("/api/v1/service-catalog/{name}", "audit-inst").then().statusCode(200);
        given().queryParam("name", "audit-template")
                .delete("/api/v1/service-template/remove")
                .then()
                .statusCode(200);
    }

    @Test
    void secretsInContentAndIdentityHeadersAreNotStored() {
        DataStore secret = new DataStore(null, "audit-secret", "Bearer s3cr3t-value");
        secret.setLabels(Map.of("token", "s3cr3t-value"));
        given().header("Authorization", "Bearer s3cr3t-value")
                .header("X-Forwarded-User", "mallory")
                .contentType(ContentType.JSON)
                .body(secret)
                .post("/api/v1/data-store")
                .then()
                .statusCode(200);

        String json = given().get("/api/v1/audit/events")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(json).contains("data_store.create").doesNotContain("s3cr3t").doesNotContain("mallory");
        assertThat(json).doesNotContain("\"actor\"");
    }

    @Test
    void queriesFilterAndPaginateNewestFirst() {
        for (int i = 0; i < 5; i++) {
            createDataStore("audit-page-" + i);
        }

        JsonPath first = given().queryParam("target_type", "data_store")
                .queryParam("limit", 2)
                .get("/api/v1/audit/events")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(first.getInt("data.total")).isEqualTo(5);
        assertThat(first.getInt("data.limit")).isEqualTo(2);
        List<Integer> sequences = first.getList("data.events.sequence", Integer.class);
        assertThat(sequences).hasSize(2);
        assertThat(sequences.get(0)).isGreaterThan(sequences.get(1));
        assertThat(given().queryParam("target_type", "service_catalog")
                        .get("/api/v1/audit/events")
                        .then()
                        .extract()
                        .jsonPath()
                        .getInt("data.total"))
                .isZero();

        JsonPath last = given().queryParam("target_type", "data_store")
                .queryParam("offset", 4)
                .queryParam("limit", 2)
                .get("/api/v1/audit/events")
                .then()
                .extract()
                .jsonPath();
        assertThat(last.getList("data.events")).hasSize(1);

        assertThat(given().queryParam("operation", "data_store.create")
                        .queryParam("decision", "allow")
                        .queryParam("from", Instant.now().plusSeconds(3600).toString())
                        .get("/api/v1/audit/events")
                        .then()
                        .extract()
                        .jsonPath()
                        .getInt("data.total"))
                .isZero();
        assertThat(given().queryParam("operation", "data_store.create")
                        .queryParam("to", Instant.now().plusSeconds(3600).toString())
                        .get("/api/v1/audit/events")
                        .then()
                        .extract()
                        .jsonPath()
                        .getInt("data.total"))
                .isEqualTo(5);

        given().queryParam("from", "yesterday")
                .get("/api/v1/audit/events")
                .then()
                .statusCode(400);
    }

    @Test
    void schemaHealthAndUnknownEvents() {
        given().get("/api/v1/audit/schema").then().statusCode(200).body("data.schema_version", equalTo("1.0"));
        given().get("/api/v1/audit/health")
                .then()
                .statusCode(200)
                .body("data.healthy", equalTo(true))
                .body("data.dropped_events", equalTo(0))
                .body("data.capacity", equalTo(10000));
        given().get("/api/v1/audit/events/{id}", "no-such-event").then().statusCode(404);
    }
}
