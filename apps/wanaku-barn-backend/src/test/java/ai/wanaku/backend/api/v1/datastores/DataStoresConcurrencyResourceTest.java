package ai.wanaku.backend.api.v1.datastores;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

/**
 * Verifies the optimistic concurrency contract of the data store REST API.
 */
@QuarkusTest
class DataStoresConcurrencyResourceTest {

    private static String create(String name) {
        return given().contentType(ContentType.JSON)
                .body(new DataStore(null, name, "v1"))
                .post("/api/v1/data-store")
                .then()
                .statusCode(200)
                .header("ETag", "\"1\"")
                .extract()
                .path("data.id");
    }

    @Test
    void entityTagRoundTrip() {
        String id = create("etag-round-trip");

        given().get("/api/v1/data-store/{id}", id).then().statusCode(200).header("ETag", "\"1\"");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(new DataStore(id, "etag-round-trip", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(200)
                .header("ETag", "\"2\"");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(new DataStore(id, "etag-round-trip", "stale"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(409);

        given().get("/api/v1/data-store/{id}", id)
                .then()
                .statusCode(200)
                .header("ETag", "\"2\"")
                .body("data.data", equalTo("v2"));
    }

    @Test
    void expectedRevisionQueryParameterIsAccepted() {
        String id = create("expected-revision-param");

        given().contentType(ContentType.JSON)
                .queryParam("expectedRevision", 2)
                .body(new DataStore(id, "expected-revision-param", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(409);

        given().contentType(ContentType.JSON)
                .queryParam("expectedRevision", 1)
                .body(new DataStore(id, "expected-revision-param", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(200)
                .header("ETag", "\"2\"");
    }

    @Test
    void updateWithoutPreconditionStillWorks() {
        String id = create("no-precondition");

        given().contentType(ContentType.JSON)
                .body(new DataStore(id, "no-precondition", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(200)
                .header("ETag", "\"2\"");
    }

    @Test
    void missingEntryIsReportedBeforeTheRevision() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"7\"")
                .body(new DataStore("does-not-exist", "x", "y"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(404);
    }

    @Test
    void invalidOrContradictoryPreconditionsAreRejected() {
        String id = create("bad-precondition");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"abc\"")
                .body(new DataStore(id, "bad-precondition", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(400);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .queryParam("expectedRevision", 2)
                .body(new DataStore(id, "bad-precondition", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(400);
    }

    @Test
    void staleDeleteConflicts() {
        String id = create("stale-delete");
        given().contentType(ContentType.JSON)
                .body(new DataStore(id, "stale-delete", "v2"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(200);

        given().header("If-Match", "\"1\"")
                .delete("/api/v1/data-store/{id}", id)
                .then()
                .statusCode(409);
        given().get("/api/v1/data-store/{id}", id).then().statusCode(200);

        given().header("If-Match", "\"2\"")
                .delete("/api/v1/data-store/{id}", id)
                .then()
                .statusCode(200);
        given().get("/api/v1/data-store/{id}", id).then().statusCode(404);
    }

    @Test
    void duplicateNamesAndRenamesConflict() {
        create("unique-a");
        String b = create("unique-b");

        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "unique-a", "dup"))
                .post("/api/v1/data-store")
                .then()
                .statusCode(409);

        given().contentType(ContentType.JSON)
                .body(new DataStore(b, "unique-a", "rename"))
                .put("/api/v1/data-store")
                .then()
                .statusCode(409);
    }
}
