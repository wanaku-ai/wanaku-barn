package ai.wanaku.backend.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class RequestCorrelationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void suppliedIdentifierIsKept() {
        given().header("x-request-id", "client-123")
                .get("/api/v1/data-store")
                .then()
                .statusCode(200)
                .header("x-request-id", "client-123");
    }

    @Test
    void missingIdentifierIsGenerated() {
        given().get("/api/v1/data-store").then().statusCode(200).header("x-request-id", matchesPattern(UUID_PATTERN));
    }

    @Test
    void invalidIdentifiersAreReplaced() {
        for (String invalid : List.of("", " ", "has space", "a".repeat(129), "line\tbreak", "quote\"d")) {
            given().header("x-request-id", invalid)
                    .get("/api/v1/data-store")
                    .then()
                    .header("x-request-id", matchesPattern(UUID_PATTERN));
        }
    }

    @Test
    void errorResponsesCarryTheIdentifier() {
        given().header("x-request-id", "not-found-1")
                .get("/api/v1/data-store/does-not-exist")
                .then()
                .statusCode(404)
                .header("x-request-id", "not-found-1");

        given().header("x-request-id", "bad-request-1")
                .header("If-Match", "\"abc\"")
                .contentType(ContentType.JSON)
                .body("{\"id\":\"x\",\"name\":\"x\",\"data\":\"x\"}")
                .put("/api/v1/data-store")
                .then()
                .statusCode(400)
                .header("x-request-id", "bad-request-1");

        given().header("x-request-id", "unknown-route-1")
                .get("/api/v1/does-not-exist")
                .then()
                .statusCode(404)
                .header("x-request-id", "unknown-route-1");
    }

    @Test
    void identifierIsAvailableWhileHandlingTheRequest() {
        for (String path : List.of("/test/correlation/blocking", "/test/correlation/non-blocking")) {
            given().header("x-request-id", "probe-1")
                    .get(path)
                    .then()
                    .statusCode(200)
                    .body("requestId", equalTo("probe-1"))
                    .body("mdc", equalTo("probe-1"))
                    .body("traceId", nullValue());
        }
    }

    @Test
    void concurrentRequestsDoNotShareIdentifiers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Response>> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String id = "concurrent-" + i;
                String path = i % 2 == 0 ? "/test/correlation/blocking" : "/test/correlation/non-blocking";
                calls.add(CompletableFuture.supplyAsync(
                        () -> given().header("x-request-id", id).get(path), executor));
            }
            for (int i = 0; i < calls.size(); i++) {
                Response response = calls.get(i).get();
                assertThat(response.getStatusCode()).isEqualTo(200);
                assertThat(response.jsonPath().getString("requestId")).isEqualTo("concurrent-" + i);
                assertThat(response.jsonPath().getString("mdc")).isEqualTo("concurrent-" + i);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void identityHeadersDoNotChangeTheIdentifier() {
        given().header("x-request-id", "identity-1")
                .header("X-Forwarded-User", "mallory")
                .header("Authorization", "Bearer forged")
                .get("/test/correlation/blocking")
                .then()
                .statusCode(200)
                .body("requestId", equalTo("identity-1"))
                .body("requestId", not(equalTo("mallory")));
    }
}
