package ai.wanaku.cli.main.commands.evaluators;

import java.util.List;
import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.core.services.api.EvaluatorRevision;
import ai.wanaku.core.services.api.EvaluatorsUpdateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the JSON field names against the router's evaluators management API.
 */
public class EvaluatorsWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Should serialize the update request with snake_case and omit a missing expected revision")
    void shouldSerializeUpdateRequest() throws Exception {
        List<Map<String, Object>> evaluators = List.of(Map.of("name", "a"));

        assertEquals(
                "{\"evaluators\":[{\"name\":\"a\"}],\"expected_revision\":3}",
                mapper.writeValueAsString(new EvaluatorsUpdateRequest(evaluators, 3L)));
        assertEquals(
                "{\"evaluators\":[{\"name\":\"a\"}]}",
                mapper.writeValueAsString(new EvaluatorsUpdateRequest(evaluators, null)));
    }

    @Test
    @DisplayName("Should read a revision response from the router")
    void shouldReadRevisionResponse() throws Exception {
        String json =
                """
                {"data": {
                   "revision": {"id": 7, "created_at": "2026-08-26T16:58:34Z", "activated_at": null,
                                "status": "active", "checksum": "abc", "origin": "api", "actor": null,
                                "failure_reason": null},
                   "evaluators": [{"name": "a", "trigger": {"method": "tools/call"},
                                   "engine": {"type": "passthrough"}, "processor": {"path": "/wasm/a.wasm"}}]},
                 "error": null}
                """;

        WanakuResponse<EvaluatorRevision> response = mapper.readValue(json, new TypeReference<>() {});
        EvaluatorRevision revision = response.data();

        assertEquals(7, revision.getRevision().getId());
        assertEquals("2026-08-26T16:58:34Z", revision.getRevision().getCreatedAt());
        assertNull(revision.getRevision().getActivatedAt());
        assertEquals("api", revision.getRevision().getOrigin());
        assertEquals("a", revision.getEvaluators().get(0).get("name"));
    }
}
