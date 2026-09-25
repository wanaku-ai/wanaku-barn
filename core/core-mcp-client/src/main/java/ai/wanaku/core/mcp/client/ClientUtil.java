package ai.wanaku.core.mcp.client;

import java.util.HashMap;
import java.util.Map;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

/**
 * Creates MCP clients for outbound calls to MCP servers.
 * <p>
 * Only the Streamable HTTP transport is supported. The legacy HTTP+SSE transport
 * (endpoints ending in {@code /sse}) was deprecated by the MCP specification and its
 * client implementation was removed from LangChain4j 1.19. Callers must use the Streamable
 * HTTP endpoint of the server instead (for Wanaku, {@code /mcp/} rather than {@code /mcp/sse}).
 */
public class ClientUtil {

    public static McpClient createClient(String address) {
        return createClient(address, (McpHeadersSupplier) null);
    }

    public static McpClient createClient(String address, String token) {
        return createClient(address, token, null);
    }

    /**
     * Creates a client with an optional protocol version to skip protocol discovery.
     *
     * @param address the Streamable HTTP endpoint
     * @param token the bearer token, or null for unauthenticated calls
     * @param protocolVersion the MCP version, or null for automatic detection
     * @return the initialized MCP client
     */
    public static McpClient createClient(String address, String token, String protocolVersion) {
        String normalizedToken = token != null ? token.trim() : null;
        McpHeadersSupplier tokenHeaders = (normalizedToken != null && !normalizedToken.isEmpty())
                ? callContext -> Map.of("Authorization", "Bearer " + normalizedToken)
                : null;
        return createClient(address, tokenHeaders, protocolVersion);
    }

    public static McpClient createClient(String address, McpHeadersSupplier headersSupplier) {
        return createClient(address, headersSupplier, null);
    }

    private static McpClient createClient(String address, McpHeadersSupplier headersSupplier, String protocolVersion) {
        if (isLegacySseAddress(address)) {
            throw new IllegalArgumentException("Legacy SSE MCP endpoints are no longer supported: " + address
                    + ". Use the Streamable HTTP endpoint instead (for example, replace '/mcp/sse' with '/mcp/').");
        }

        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(address)
                .logRequests(true)
                .logResponses(true)
                .customHeaders(combineHeaders(headersSupplier))
                .build();

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .protocolVersion(protocolVersion)
                .build();
    }

    /**
     * Returns whether the address points to a legacy HTTP+SSE MCP endpoint (i.e., it ends with {@code /sse}).
     */
    public static boolean isLegacySseAddress(String address) {
        if (address == null) {
            return false;
        }
        String normalized = address.strip();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/sse");
    }

    private static McpHeadersSupplier combineHeaders(McpHeadersSupplier extraHeaders) {
        return callContext -> {
            Map<String, String> headers = new HashMap<>();

            // Propagate W3C trace context
            Span currentSpan = Span.current();
            if (currentSpan.getSpanContext().isValid()) {
                W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();
                propagator.inject(Context.current(), headers, Map::put);
            }

            // Propagate MCP request ID from MDC
            String requestId = (String) org.jboss.logging.MDC.get("requestId");
            if (requestId != null && !requestId.isEmpty()) {
                headers.put("x-wanaku-request-id", requestId);
            }

            if (extraHeaders != null) {
                Map<String, String> extra = extraHeaders.apply(callContext);
                if (extra != null) {
                    headers.putAll(extra);
                }
            }

            return headers;
        };
    }
}
