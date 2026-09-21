package ai.wanaku.core.mcp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientUtilTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8080/mcp/sse",
                "http://localhost:8080/mcp/sse/",
                "http://localhost:8080/public/mcp/sse",
                "  http://localhost:8080/ns-1/mcp/sse  "
            })
    void detectsLegacySseAddresses(String address) {
        assertTrue(ClientUtil.isLegacySseAddress(address));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8080/mcp/",
                "http://localhost:8080/mcp",
                "http://localhost:8080/public/mcp/",
                "http://localhost:8080/ns-1/mcp/",
                "http://sse.example.com/mcp/"
            })
    void acceptsStreamableHttpAddresses(String address) {
        assertFalse(ClientUtil.isLegacySseAddress(address));
    }

    @Test
    void nullAddressIsNotLegacy() {
        assertFalse(ClientUtil.isLegacySseAddress(null));
    }

    @Test
    void rejectsLegacySseAddressWithMigrationHint() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ClientUtil.createClient("http://localhost:8080/mcp/sse", "token"));

        assertTrue(ex.getMessage().contains("http://localhost:8080/mcp/sse"));
        assertTrue(ex.getMessage().contains("/mcp/"), "Message must point to the Streamable HTTP endpoint");
    }
}
