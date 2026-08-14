package ai.wanaku.cli.main.commands.resources;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

@CommandLine.Command(
        name = "resources",
        description = "Manage resources",
        subcommands = {ResourcesList.class, ResourcesShow.class})
public class Resources extends BaseCommand {

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_ERROR;
    }
}
