package ai.wanaku.cli.main.commands.cloud;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.usage;

@Command(
        name = "run",
        description = "Run a route file in the cloud target",
        subcommands = {CloudRunIntegration.class, CloudRunSemantic.class})
public class CloudRun extends BaseCommand {
    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        usage(this, System.out);
        return EXIT_ERROR;
    }
}
