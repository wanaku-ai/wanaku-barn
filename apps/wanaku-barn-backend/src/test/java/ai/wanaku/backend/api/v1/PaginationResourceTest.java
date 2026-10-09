package ai.wanaku.backend.api.v1;

import java.time.Instant;
import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies opt-in pagination of the list endpoints and the time-range filter of version listings.
 */
@QuarkusTest
class PaginationResourceTest {

    @Test
    void dataStoreListIsCompleteWithoutPagingParameters() {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "paging-plain", "x"))
                .post("/api/v1/data-store")
                .then()
                .statusCode(200);

        given().get("/api/v1/data-store").then().statusCode(200).header("X-Total-Count", nullValue());
    }

    @Test
    void dataStorePagesCarryTheTotal() {
        for (String name : List.of("page-c", "page-a", "page-b")) {
            DataStore entry = new DataStore(null, name, "x");
            entry.addLabel("paging", "yes");
            given().contentType(ContentType.JSON)
                    .body(entry)
                    .post("/api/v1/data-store")
                    .then()
                    .statusCode(200);
        }

        Response page = given().queryParam("labelFilter", "paging=yes")
                .queryParam("offset", 1)
                .queryParam("limit", 1)
                .get("/api/v1/data-store");
        page.then().statusCode(200).header("X-Total-Count", "3");
        assertThat(page.jsonPath().getList("data.name", String.class)).containsExactly("page-b");

        Response all = given().queryParam("limit", 1000).get("/api/v1/data-store");
        all.then().statusCode(200);
        assertThat(Long.parseLong(all.getHeader("X-Total-Count")))
                .isEqualTo(all.jsonPath().getList("data").size());

        given().queryParam("labelFilter", "paging=yes")
                .queryParam("offset", 10)
                .get("/api/v1/data-store")
                .then()
                .statusCode(200)
                .header("X-Total-Count", "3")
                .body("data.size()", equalTo(0));
    }

    @Test
    void invalidPagingParametersAreRejected() {
        given().queryParam("limit", 0).get("/api/v1/data-store").then().statusCode(400);
        given().queryParam("limit", 1001).get("/api/v1/data-store").then().statusCode(400);
        given().queryParam("offset", -1).get("/api/v1/data-store").then().statusCode(400);
        given().queryParam("limit", 0).get("/api/v1/service-catalog").then().statusCode(400);
    }

    @Test
    void catalogPagesKeepCaseInsensitiveNameOrder() {
        for (String name : List.of("Paged-B", "paged-a", "paged-c")) {
            given().contentType(ContentType.JSON)
                    .body(new DataStore(null, name + ".zip", CatalogZips.base64(name, "paging test", "sys")))
                    .post("/api/v1/service-catalog")
                    .then()
                    .statusCode(200);
        }

        Response page = given().queryParam("search", "paging test")
                .queryParam("offset", 0)
                .queryParam("limit", 2)
                .get("/api/v1/service-catalog");
        page.then().statusCode(200).header("X-Total-Count", "3");
        assertThat(page.jsonPath().getList("data.name", String.class)).containsExactly("paged-a", "Paged-B");

        Response all = given().queryParam("limit", 1000).get("/api/v1/service-catalog");
        List<String> names = all.jsonPath().getList("data.name", String.class);
        assertThat(names).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(Long.parseLong(all.getHeader("X-Total-Count"))).isEqualTo(names.size());
    }

    @Test
    void templatePagesCarryTheTotal() {
        Response page = given().queryParam("limit", 1).get("/api/v1/service-template/list");
        page.then().statusCode(200);
        assertThat(page.jsonPath().getList("data")).hasSizeLessThanOrEqualTo(1);
        assertThat(page.getHeader("X-Total-Count")).isNotNull();
    }

    @Test
    void versionsCanBeFilteredByCreationTime() {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "timed.zip", CatalogZips.base64("timed", "v1", "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(200);

        given().queryParam("from", Instant.now().plusSeconds(3600).toString())
                .get("/api/v1/service-catalog/{name}/versions", "timed")
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(0));
        given().queryParam("to", Instant.now().plusSeconds(3600).toString())
                .get("/api/v1/service-catalog/{name}/versions", "timed")
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(1));
        given().queryParam("from", "yesterday")
                .get("/api/v1/service-catalog/{name}/versions", "timed")
                .then()
                .statusCode(400);
    }
}
