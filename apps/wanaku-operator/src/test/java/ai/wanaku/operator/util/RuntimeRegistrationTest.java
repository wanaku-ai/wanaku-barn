package ai.wanaku.operator.util;

import java.util.Map;
import ai.wanaku.operator.wanaku.WanakuRouter;
import ai.wanaku.operator.wanaku.WanakuRouterStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeRegistrationTest {
    @ParameterizedTest
    @CsvSource({
        "https://mcp.example.com/mcp, default",
        "https://mcp.example.com/mcp, finance",
        "https://mcp.example.com/default/mcp, default",
        "https://mcp.example.com/default/mcp, finance"
    })
    void wanakuEndpointUsesTheForwardNamespace(String endpoint, String namespace) {
        WanakuRouter router = new WanakuRouter();
        WanakuRouterStatus status = new WanakuRouterStatus();
        status.setEndpoints(Map.of("mcp", endpoint));
        router.setStatus(status);

        assertEquals(
                "https://mcp.example.com/" + namespace + "/mcp", RuntimeRegistration.wanakuEndpoint(router, namespace));
    }

    @Test
    void wanakuEndpointIsAbsentWhenMcpIsNotExposed() {
        WanakuRouter router = new WanakuRouter();
        assertNull(RuntimeRegistration.wanakuEndpoint(router, "default"));
        router.setStatus(new WanakuRouterStatus());
        assertNull(RuntimeRegistration.wanakuEndpoint(router, "default"));
        router.getStatus().setEndpoints(Map.of("management", "https://mgmt.example.com/"));
        assertNull(RuntimeRegistration.wanakuEndpoint(router, "default"));
    }
}
