package ai.wanaku.cli.main.commands.mcp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.vertx.core.json.JsonObject;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.mcp.client.ClientUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import picocli.CommandLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolTest {

    @Mock
    McpClient mcpClient;

    McpTool command;

    @BeforeEach
    void setUp() {
        command = new McpTool();
        command.mcpClient = mcpClient;
        command.uri = "http://localhost:3001/mcp";
        command.name = "echo";
        command.params = new LinkedHashMap<>(Map.of("message", "hello"));
    }

    @Test
    @DisplayName("Should call tool and print result")
    void shouldCallToolAndPrintResult() throws Exception {
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .resultText("hello world")
                        .isError(false)
                        .build());

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            Integer result = command.doCall(null, mock(WanakuPrinter.class));
            assertEquals(BaseCommand.EXIT_OK, result);
            assertTrue(captured.toString().contains("hello world"));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    @DisplayName("Should return error when tool execution fails")
    void shouldReturnErrorWhenToolFails() throws Exception {
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .resultText("tool failed")
                        .isError(true)
                        .build());

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printErrorMessage("tool failed");
    }

    @Test
    @DisplayName("Should return error on exception")
    void shouldReturnErrorOnException() throws Exception {
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printErrorMessage("connection refused");
    }

    @Test
    @DisplayName("Should show user-friendly message for invalid URI")
    void shouldShowFriendlyMessageForInvalidUri() throws Exception {
        command.uri = "not-a-valid-uri";
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new IllegalArgumentException("URI with undefined scheme"));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(printer).printErrorMessage(captor.capture());
        String message = captor.getValue();
        assertTrue(message.contains("Invalid URI"));
        assertTrue(message.contains("not-a-valid-uri"));
    }

    @Test
    @DisplayName("Should serialize params to JSON")
    void shouldSerializeParamsToJson() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("key1", "value1");
        params.put("key2", "value2");

        String json = McpTool.serializeParams(new JsonObject(), params, null);

        assertEquals("{\"key1\":\"value1\",\"key2\":\"value2\"}", json);
    }

    @Test
    @DisplayName("Should convert params according to the tool input schema")
    void shouldConvertParamsUsingSchema() {
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addNumberProperty("a")
                .addIntegerProperty("n")
                .addBooleanProperty("flag")
                .addStringProperty("s")
                .build();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "1.5");
        params.put("n", "2");
        params.put("flag", "true");
        params.put("s", "42");
        params.put("other", "x");

        String json = McpTool.serializeParams(new JsonObject(), params, schema);

        assertEquals("{\"a\":1.5,\"n\":2,\"flag\":true,\"s\":\"42\",\"other\":\"x\"}", json);
    }

    @Test
    @DisplayName("Should keep the raw string when a value does not match the schema type")
    void shouldKeepStringOnInvalidTypedValue() {
        JsonObjectSchema schema =
                JsonObjectSchema.builder().addNumberProperty("a").build();

        String json = McpTool.serializeParams(new JsonObject(), Map.of("a", "abc"), schema);

        assertEquals("{\"a\":\"abc\"}", json);
    }

    @Test
    @DisplayName("Should not split param values at commas")
    void shouldNotSplitParamsAtCommas() {
        McpTool cmd = new McpTool();
        new CommandLine(cmd).parseArgs("--param", "message=hello, world", "--param", "other=1,2");

        assertEquals(Map.of("message", "hello, world", "other", "1,2"), cmd.params);
    }

    @Test
    @DisplayName("Should send typed arguments using the tool input schema")
    void shouldSendTypedArgumentsFromSchema() throws Exception {
        command.name = "get-sum";
        command.params = new LinkedHashMap<>(Map.of("a", "1"));
        command.arguments = "{\"b\": 2, \"a\": \"ignored\"}";
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addNumberProperty("a")
                .addNumberProperty("b")
                .build();
        when(mcpClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("get-sum")
                        .parameters(schema)
                        .build()));
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .resultText("3")
                        .isError(false)
                        .build());

        Integer result = command.doCall(null, mock(WanakuPrinter.class));

        assertEquals(BaseCommand.EXIT_OK, result);
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(mcpClient).executeTool(captor.capture());
        assertEquals(
                new JsonObject("{\"a\":1,\"b\":2}"),
                new JsonObject(captor.getValue().arguments()));
    }

    @Test
    @DisplayName("Should return error when --arguments is not a JSON object")
    void shouldReturnErrorOnInvalidArguments() throws Exception {
        command.arguments = "[1, 2]";
        WanakuPrinter printer = mock(WanakuPrinter.class);

        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printErrorMessage("Invalid value for --arguments: expected a JSON object");
    }

    @Test
    @DisplayName("Should return error when uri is missing")
    void shouldReturnErrorWhenUriMissing() throws Exception {
        command.uri = null;
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);
        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printErrorMessage("Missing required option: --uri");
    }

    @Test
    @DisplayName("Should return error when name is missing")
    void shouldReturnErrorWhenNameMissing() throws Exception {
        command.name = null;
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);
        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printErrorMessage("Missing required option: --name");
    }

    @Test
    @DisplayName("Should return error on ToolArgumentsException for invalid tool name")
    void shouldReturnErrorOnToolArgumentsException() throws Exception {
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new ToolArgumentsException("Invalid tool name: nonexistent", -32602));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(printer).printErrorMessage(captor.capture());
        String message = captor.getValue();
        assertTrue(message.contains("Invalid tool name"));
    }

    @Test
    @DisplayName("Should return error on ToolExecutionException")
    void shouldReturnErrorOnToolExecutionException() throws Exception {
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new ToolExecutionException("Tool execution failed", -32603));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printErrorMessage(any(String.class));
    }

    @Test
    @DisplayName("Should forward auth token to MCP client")
    void shouldForwardAuthToken() throws Exception {
        McpClient tokenClient = mock(McpClient.class);
        when(tokenClient.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .resultText("ok")
                        .isError(false)
                        .build());

        McpTool cmd = new McpTool();
        new CommandLine(cmd)
                .parseArgs(
                        "--uri",
                        "http://localhost:9999/mcp/sse",
                        "--name",
                        "echo",
                        "--param",
                        "msg=hi",
                        "--token",
                        "my-secret-token");

        try (MockedStatic<ClientUtil> clientUtil = mockStatic(ClientUtil.class)) {
            clientUtil
                    .when(() ->
                            ClientUtil.createClient("http://localhost:9999/mcp/sse", "my-secret-token", "2025-11-25"))
                    .thenReturn(tokenClient);

            Integer result = cmd.doCall(null, mock(WanakuPrinter.class));
            assertEquals(BaseCommand.EXIT_OK, result);

            clientUtil.verify(
                    () -> ClientUtil.createClient("http://localhost:9999/mcp/sse", "my-secret-token", "2025-11-25"));
        }
    }
}
