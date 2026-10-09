package ai.wanaku.backend.audit;

import jakarta.inject.Inject;

import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

/**
 * Verifies that audit events carry the trace identifier of the incoming W3C trace context when tracing is enabled.
 */
@QuarkusTest
@TestProfile(AuditTracingTest.TracingEnabled.class)
class AuditTracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    public static class TracingEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.otel.sdk.disabled", "false", "quarkus.otel.traces.exporter", "none");
        }
    }

    @Inject
    AuditStore store;

    @Test
    void eventsCarryTheParentTraceIdentifier() {
        store.clear();
        given().header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .contentType(ContentType.JSON)
                .body(new DataStore(null, "traced", "content"))
                .post("/api/v1/data-store")
                .then()
                .statusCode(200);

        given().get("/api/v1/audit/events")
                .then()
                .statusCode(200)
                .body("data.events[0].operation", equalTo("data_store.create"))
                .body("data.events[0].attributes.trace_id", equalTo(TRACE_ID));
    }
}
