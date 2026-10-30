package ai.wanaku.backend.api.v1.servicecatalog;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.io.TemplateInstantiationRequest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;

/**
 * Invalid input to the service catalog and template APIs is a client error, not a server error (#289).
 */
@QuarkusTest
class ServiceInputErrorsTest {

    private static void deploy(String name) {
        given().contentType(ContentType.JSON)
                .body(new DataStore(null, name + ".service.zip", CatalogZips.base64(name, name, "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(200);
    }

    private static TemplateInstantiationRequest request(String templateName, String serviceName) {
        TemplateInstantiationRequest request = new TemplateInstantiationRequest();
        request.setTemplateName(templateName);
        request.setServiceName(serviceName);
        return request;
    }

    @Test
    void instantiateWithAnInvalidServiceNameIsABadRequest() {
        given().contentType(ContentType.JSON)
                .body(request("any-template", "bad name"))
                .post("/api/v1/service-template/instantiate")
                .then()
                .statusCode(400)
                .body("error.message", containsString("serviceName"));
    }

    @Test
    void instantiateWithoutATemplateNameIsABadRequest() {
        given().contentType(ContentType.JSON)
                .body(request(null, "my-service"))
                .post("/api/v1/service-template/instantiate")
                .then()
                .statusCode(400);
    }

    @Test
    void instructionsForAnUnsupportedModelAreABadRequest() {
        deploy("instructions-model");

        given().queryParam("name", "instructions-model")
                .queryParam("model", "kubernetes")
                .get("/api/v1/service-catalog/instructions")
                .then()
                .statusCode(400)
                .body("error.message", containsString("kubernetes"));
    }

    @Test
    void deployRejectsAnInvalidRouteAndKeepsNoCatalog() {
        String routes =
                """
                - route:
                    from:
                      uri: "direct:sys"
                      stepz: []
                """;
        given().contentType(ContentType.JSON)
                .body(new DataStore(
                        null,
                        "invalid-route.service.zip",
                        CatalogZips.base64WithRoutes("invalid-route", "invalid", system -> routes, "sys")))
                .post("/api/v1/service-catalog")
                .then()
                .statusCode(422)
                .body("error.message", containsString("stepz"));

        given().get("/api/v1/service-catalog/{name}", "invalid-route").then().statusCode(404);
    }
}
