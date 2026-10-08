package ai.wanaku.backend.common;

import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

/**
 * Verifies that the trace identifier comes from the incoming W3C trace context when tracing is enabled.
 */
@QuarkusTest
@TestProfile(RequestCorrelationTracingTest.TracingEnabled.class)
class RequestCorrelationTracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    public static class TracingEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.otel.sdk.disabled", "false", "quarkus.otel.traces.exporter", "none");
        }
    }

    @Test
    void traceIdentifierFollowsTheParentTrace() {
        given().header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .header("x-request-id", "traced-1")
                .get("/test/correlation/blocking")
                .then()
                .statusCode(200)
                .body("requestId", equalTo("traced-1"))
                .body("traceId", equalTo(TRACE_ID));
    }
}
