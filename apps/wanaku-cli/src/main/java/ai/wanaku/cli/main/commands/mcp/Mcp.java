package ai.wanaku.cli.main.commands.mcp;

import java.net.URI;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

@CommandLine.Command(
        name = "mcp",
        description = "Interact with MCP servers directly",
        subcommands = {McpTool.class, McpResource.class, McpPrompt.class})
public class Mcp extends BaseCommand {

    static final String PROTOCOL_VERSION = "2025-11-25";

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }

    /**
     * Warns the user when the bearer token is sent over plain HTTP to a host other than the local machine.
     */
    static void warnIfTokenOverPlainHttp(WanakuPrinter printer, String uri, String token) {
        if (uri == null || token == null || token.isBlank()) {
            return;
        }
        URI target;
        try {
            target = URI.create(uri.trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        String host = target.getHost();
        if (!"http".equalsIgnoreCase(target.getScheme()) || host == null || isLoopback(host)) {
            return;
        }
        printer.printWarningMessage(
                "The token is sent over plain HTTP to " + host + ". Use an https:// URI to protect it");
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("[::1]");
    }
}
