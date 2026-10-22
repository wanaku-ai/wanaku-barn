package ai.wanaku.cli.main.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.commands.mcp.McpToolList;
import com.sun.net.httpserver.HttpServer;
import picocli.CommandLine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpErrorHelperRedirectTest {

    private HttpServer server;
    private String location;
    private int status;
    private final AtomicReference<String> probeAuthorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        status = 302;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                probeAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String uri(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private static Exception redirectException(int status) {
        return new RuntimeException(new RuntimeException("Unexpected status code: " + status));
    }

    @Test
    @DisplayName("Should tell the user to use https:// when the server redirects to HTTPS")
    void shouldSuggestHttps() {
        location = "https://localhost:" + server.getAddress().getPort() + "/test-ns/mcp/";

        String message = McpErrorHelper.friendlyMessage(redirectException(302), uri("/test-ns/mcp/"), null);

        assertTrue(message.contains("HTTP 302"));
        assertTrue(message.contains(location));
        assertTrue(message.contains("The server redirects to HTTPS. Use --uri " + location));
    }

    @Test
    @DisplayName("Should report a login redirect to the oauth2-proxy start page")
    void shouldReportOauth2ProxyLogin() {
        location = "/oauth2/start?rd=%2Ftest-ns%2Fmcp%2F";

        String message = McpErrorHelper.friendlyMessage(redirectException(302), uri("/test-ns/mcp/"), "my-token");

        assertTrue(message.contains("/oauth2/start"));
        assertTrue(message.contains("browser login"));
        assertTrue(message.contains("The token is expired"));
        assertEquals("Bearer my-token", probeAuthorization.get());
    }

    @Test
    @DisplayName("Should report a login redirect to another host")
    void shouldReportLoginOnAnotherHost() {
        location = "https://keycloak.example.com/realms/wanaku/protocol/openid-connect/auth";

        String message = McpErrorHelper.friendlyMessage(redirectException(302), uri("/mcp/"), null);

        assertTrue(message.contains("keycloak.example.com"));
        assertTrue(message.contains("browser login"));
        assertTrue(message.contains("The token is missing"));
    }

    @Test
    @DisplayName("Should suggest the redirect target for other redirects")
    void shouldSuggestOtherTarget() {
        status = 308;
        location = "/other/mcp/";

        String message = McpErrorHelper.friendlyMessage(redirectException(308), uri("/mcp/"), null);

        assertTrue(message.contains("HTTP 308"));
        assertTrue(message.contains("Try --uri " + uri("/other/mcp/")));
        assertFalse(message.contains("browser login"));
    }

    @Test
    @DisplayName("Should give a generic hint when the redirect target cannot be read")
    void shouldGiveGenericHintWithoutLocation() {
        status = 200;

        String message = McpErrorHelper.friendlyMessage(redirectException(302), uri("/mcp/"), null);

        assertTrue(message.contains("HTTP 302"));
        assertTrue(message.contains("use --uri https://"));
        assertTrue(message.contains("browser login"));
    }

    @Test
    @DisplayName("Should not treat other status codes as redirects")
    void shouldIgnoreNonRedirectStatus() {
        String message = McpErrorHelper.friendlyMessage(redirectException(500), uri("/mcp/"), null);

        assertEquals("Unexpected status code: 500", message);
    }

    @Test
    @DisplayName("wanaku mcp tool list should report an HTTPS redirect from the real MCP transport")
    void shouldReportRedirectFromMcpCommand() {
        String address = uri("/test-ns/mcp/");
        location = address.replace("http://", "https://");

        McpToolList command = new McpToolList();
        new CommandLine(command).parseArgs("--uri", address);

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(printer).printErrorMessage(captor.capture());
        assertTrue(captor.getValue().contains("The server redirects to HTTPS. Use --uri " + location));
    }
}
