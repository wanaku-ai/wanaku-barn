package ai.wanaku.backend.api.v1.semanticrouter;

import java.util.List;
import java.util.Map;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class SemanticRouterResourceTest {
    @InjectMock
    SemanticRouterBean bean;

    @Test
    void exposesContractAndPreservesDraftOnRestTransport() {
        SemanticRouterDefinition draft = SemanticCatalogTest.definition();
        draft.id = "draft1";
        when(bean.save(eq(null), any())).thenReturn(draft);
        given().contentType("application/json")
                .body("{\"name\":\"Support\"}")
                .post("/api/v1/semantic-routers")
                .then()
                .statusCode(200)
                .body("data.id", equalTo("draft1"));
        when(bean.list()).thenReturn(List.of(draft));
        given().get("/api/v1/semantic-routers").then().statusCode(200).body("data[0].name", equalTo("Support"));
        given().delete("/api/v1/semantic-routers/draft1").then().statusCode(200);
        verify(bean).remove("draft1");
    }

    @Test
    void generatedFilesAreExposedThroughTheTypedRestContract() {
        when(bean.files(any())).thenReturn(Map.of("service/router.camel.yaml", "- route: {}\n"));
        given().contentType("application/json")
                .body(SemanticCatalogTest.definition())
                .post("/api/v1/semantic-routers/files")
                .then()
                .statusCode(200)
                .body("data.'service/router.camel.yaml'", equalTo("- route: {}\n"));
        given().accept("application/json")
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body(
                        "paths.'/api/v1/semantic-routers/files'.post.summary",
                        equalTo("Inspect generated semantic router files"));
    }

    @Test
    void incompleteGeneratedFileRequestUsesExistingInvalidPayloadMapping() {
        when(bean.files(any())).thenThrow(new InvalidPayloadException("instructions: Value is required"));
        given().contentType("application/json")
                .body("{}")
                .post("/api/v1/semantic-routers/files")
                .then()
                .statusCode(422)
                .body("error.message", equalTo("instructions: Value is required"));
    }

    @Test
    void transportRejectsInvalidNamesBeforeSavingOrGeneratingFiles() {
        var invalid = SemanticCatalogTest.definition();
        for (String name : List.of("support route", " ", "9support", "A".repeat(121))) {
            invalid.name = name;
            given().contentType("application/json")
                    .body(invalid)
                    .post("/api/v1/semantic-routers")
                    .then()
                    .statusCode(400);
            given().contentType("application/json")
                    .body(invalid)
                    .put("/api/v1/semantic-routers/draft1")
                    .then()
                    .statusCode(400);
            given().contentType("application/json")
                    .body(invalid)
                    .post("/api/v1/semantic-routers/files")
                    .then()
                    .statusCode(400);
        }
        invalid.name = "Support-Route";
        for (String tool : List.of("support route", " ", "9support", "A".repeat(65))) {
            invalid.toolName = tool;
            given().contentType("application/json")
                    .body(invalid)
                    .post("/api/v1/semantic-routers")
                    .then()
                    .statusCode(400);
        }
        verifyNoInteractions(bean);
    }

    @Test
    void beanValidationRejectsInvalidPreviewInputs() {
        given().contentType("application/json")
                .body("{\"message\":\"\"}")
                .post("/api/v1/semantic-routers/draft1/preview")
                .then()
                .statusCode(400);
        given().get("/q/openapi").then().statusCode(200);
    }
}
