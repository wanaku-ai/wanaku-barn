package ai.wanaku.cli.main.commands.tools;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

@CommandLine.Command(
        name = "tools",
        description = "Manage tools",
        subcommands = {ToolsList.class, ToolsShow.class, ToolsGenerate.class})
public class Tools extends BaseCommand {
    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }
}
