package ai.wanaku.cli.main.commands.mcp;

import java.util.Collections;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.mcp.client.ClientUtil;
import dev.langchain4j.mcp.client.McpClient;
import picocli.CommandLine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPlainHttpTokenWarningTest {

    @Test
    @DisplayName("Should warn when the token is sent over plain HTTP to a remote host")
    void shouldWarnForRemotePlainHttp() {
        WanakuPrinter printer = mock(WanakuPrinter.class);

        Mcp.warnIfTokenOverPlainHttp(printer, "http://wanaku.example.com/test-ns/mcp/", "my-token");

        verify(printer)
                .printWarningMessage(
                        "The token is sent over plain HTTP to wanaku.example.com. Use an https:// URI to protect it");
    }

    @Test
    @DisplayName("Should warn for a hostname that starts with 127.")
    void shouldWarnForHostnameStartingWithLoopbackPrefix() {
        WanakuPrinter printer = mock(WanakuPrinter.class);

        Mcp.warnIfTokenOverPlainHttp(printer, "http://127.example.com/mcp/", "my-token");

        verify(printer)
                .printWarningMessage(
                        "The token is sent over plain HTTP to 127.example.com. Use an https:// URI to protect it");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8080/mcp/",
                "http://127.0.0.1:8080/mcp/",
                "http://[::1]:8080/mcp/",
                "https://wanaku.example.com/mcp/",
                "not a uri"
            })
    @DisplayName("Should not warn for HTTPS, loopback or invalid URIs")
    void shouldNotWarn(String uri) {
        WanakuPrinter printer = mock(WanakuPrinter.class);

        Mcp.warnIfTokenOverPlainHttp(printer, uri, "my-token");

        verify(printer, never()).printWarningMessage(anyString());
    }

    @Test
    @DisplayName("Should not warn without a token")
    void shouldNotWarnWithoutToken() {
        WanakuPrinter printer = mock(WanakuPrinter.class);

        Mcp.warnIfTokenOverPlainHttp(printer, "http://wanaku.example.com/mcp/", null);

        verify(printer, never()).printWarningMessage(anyString());
    }

    @Test
    @DisplayName("wanaku mcp tool list should warn when --token is used with a remote http:// URI")
    void shouldWarnFromCommand() throws Exception {
        String address = "http://wanaku.example.com/mcp/";
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(Collections.emptyList());

        McpToolList command = new McpToolList();
        new CommandLine(command).parseArgs("--uri", address, "--token", "my-token");

        WanakuPrinter printer = mock(WanakuPrinter.class);
        try (MockedStatic<ClientUtil> clientUtil = mockStatic(ClientUtil.class)) {
            clientUtil
                    .when(() -> ClientUtil.createClient(any(String.class), any(String.class), any(String.class)))
                    .thenReturn(client);

            command.doCall(null, printer);
        }

        verify(printer).printWarningMessage(org.mockito.ArgumentMatchers.contains("plain HTTP"));
    }
}
