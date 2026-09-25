package ai.wanaku.cli.main.commands.mcp;

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
}
