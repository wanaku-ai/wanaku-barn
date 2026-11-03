package ai.wanaku.cli.main.commands.cloud;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.usage;

/** Runs Camel integrations and semantic routers in a cluster that runs the Wanaku operator. */
@Command(
        name = "cloud",
        description = "Run integrations in a cluster managed by the Wanaku operator",
        subcommands = {CloudTarget.class, CloudRun.class, CloudStatus.class, CloudDelete.class})
public class Cloud extends BaseCommand {
    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        usage(this, System.out);
        return EXIT_ERROR;
    }
}
