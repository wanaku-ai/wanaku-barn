package ai.wanaku.cli.main.commands.evaluators;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

/**
 * Main command for managing the router's evaluators.
 */
@CommandLine.Command(
        name = "evaluators",
        description = "Manage the router's evaluators",
        subcommands = {EvaluatorsUpdate.class, EvaluatorsRevisions.class})
public class Evaluators extends BaseCommand {

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }
}
