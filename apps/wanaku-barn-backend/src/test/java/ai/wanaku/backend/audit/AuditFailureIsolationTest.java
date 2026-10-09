package ai.wanaku.backend.audit;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Verifies that an audit failure does not change the management response.
 */
@QuarkusTest
class AuditFailureIsolationTest {

    @InjectMock
    AuditStore store;

    @Test
    void managementResponseIsUnchangedWhenRecordingFails() {
        doThrow(new IllegalStateException("audit broken")).when(store).record(any());

        given().contentType(ContentType.JSON)
                .body(new DataStore(null, "audit-isolation", "content"))
                .post("/api/v1/data-store")
                .then()
                .statusCode(200);
    }
}
