package ai.wanaku.backend.api.v1.management.statistics;

import jakarta.ws.rs.core.Response;

import ai.wanaku.backend.support.TestIndexHelper;
import ai.wanaku.backend.support.WanakuRouterTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public abstract class AbstractStatisticsResourceTest extends WanakuRouterTest {

    @BeforeAll
    static void setup() {
        TestIndexHelper.clearAllCaches();
    }

    @Test
    public void testGetStatistics() {
        given().headers(getHeaders())
                .when()
                .get("/api/v1/management/statistics")
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .body("data", notNullValue())
                .body("data.serviceCatalogsCount", greaterThanOrEqualTo(0))
                .body("data.serviceTemplatesCount", greaterThanOrEqualTo(0))
                .body("data.dataStoresCount", greaterThanOrEqualTo(0));
    }
}
