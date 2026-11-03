package ai.wanaku.cli.main.commands.cloud;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.usage;

@Command(
        name = "target",
        description = "Manage the stored cloud target",
        subcommands = {CloudTargetSet.class, CloudTargetShow.class})
public class CloudTarget extends BaseCommand {
    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        usage(this, System.out);
        return EXIT_ERROR;
    }
}
