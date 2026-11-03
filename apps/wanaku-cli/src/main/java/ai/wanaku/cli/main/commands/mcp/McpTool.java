package ai.wanaku.cli.main.commands.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.McpErrorHelper;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.mcp.client.ClientUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.service.tool.ToolExecutionResult;
import picocli.CommandLine;

@CommandLine.Command(
        name = "tool",
        description = "Call a tool on an MCP server",
        subcommands = {McpToolList.class})
public class McpTool extends BaseCommand {

    @CommandLine.Option(
            names = {"--uri"},
            description = "MCP server endpoint URI")
    String uri;

    @CommandLine.Option(
            names = {"--name"},
            description = "Name of the tool to call")
    String name;

    @CommandLine.Option(
            names = {"--param"},
            description = "Tool parameter in key=value format (repeatable). Values are converted to the type declared "
                    + "in the tool input schema (number, integer, boolean, array or object)")
    Map<String, String> params = new LinkedHashMap<>();

    @CommandLine.Option(
            names = {"--arguments"},
            description = "Tool arguments as a JSON object (e.g. '{\"a\": 1, \"b\": 2}'). "
                    + "Values given with --param override the ones given here")
    String arguments;

    McpClient mcpClient;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        if (uri == null) {
            printer.printErrorMessage("Missing required option: --uri");
            return EXIT_ERROR;
        }
        if (name == null) {
            printer.printErrorMessage("Missing required option: --name");
            return EXIT_ERROR;
        }
        JsonObject baseArguments;
        try {
            baseArguments = arguments == null ? new JsonObject() : new JsonObject(arguments);
        } catch (DecodeException e) {
            printer.printErrorMessage("Invalid value for --arguments: expected a JSON object");
            return EXIT_ERROR;
        }
        Mcp.warnIfTokenOverPlainHttp(printer, uri, getAuthTokenOverride());
        try (McpClient client = resolveClient()) {
            JsonObjectSchema schema = params.isEmpty() ? null : findInputSchema(client.listTools(), name);
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(name)
                    .arguments(serializeParams(baseArguments, params, schema))
                    .build();

            ToolExecutionResult result = client.executeTool(request);
            if (result.isError()) {
                printer.printErrorMessage(result.resultText());
                return EXIT_ERROR;
            }

            System.out.println(result.resultText());
            return EXIT_OK;
        } catch (ToolArgumentsException e) {
            printer.printErrorMessage(McpErrorHelper.friendlyToolCallMessage(e, name));
            return EXIT_ERROR;
        } catch (ToolExecutionException e) {
            printer.printErrorMessage(McpErrorHelper.friendlyToolCallMessage(e, name));
            return EXIT_ERROR;
        } catch (Exception e) {
            printer.printErrorMessage(McpErrorHelper.friendlyMessage(e, uri, getAuthTokenOverride()));
            return EXIT_ERROR;
        }
    }

    private McpClient resolveClient() {
        if (mcpClient != null) {
            return mcpClient;
        }
        return ClientUtil.createClient(uri, getAuthTokenOverride(), Mcp.PROTOCOL_VERSION);
    }

    static JsonObjectSchema findInputSchema(List<ToolSpecification> tools, String toolName) {
        if (tools == null) {
            return null;
        }
        for (ToolSpecification tool : tools) {
            if (toolName.equals(tool.name())) {
                return tool.parameters();
            }
        }
        return null;
    }

    static String serializeParams(JsonObject base, Map<String, String> params, JsonObjectSchema schema) {
        JsonObject json = base.copy();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            JsonSchemaElement property = schema != null && schema.properties() != null
                    ? schema.properties().get(entry.getKey())
                    : null;
            json.put(entry.getKey(), convert(entry.getValue(), property));
        }
        return json.toString();
    }

    private static Object convert(String value, JsonSchemaElement property) {
        if (property instanceof JsonNumberSchema
                || property instanceof JsonIntegerSchema
                || property instanceof JsonBooleanSchema
                || property instanceof JsonArraySchema
                || property instanceof JsonObjectSchema) {
            try {
                return Json.decodeValue(value);
            } catch (DecodeException e) {
                // Keep the raw string and let the server report the type mismatch
                return value;
            }
        }
        return value;
    }
}
