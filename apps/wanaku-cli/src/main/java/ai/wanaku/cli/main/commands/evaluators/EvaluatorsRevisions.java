package ai.wanaku.cli.main.commands.evaluators;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

/**
 * Parent command for managing the evaluator configuration revisions.
 */
@CommandLine.Command(
        name = "revisions",
        description = "Manage evaluator configuration revisions",
        subcommands = {
            EvaluatorsRevisionsList.class,
            EvaluatorsRevisionsActive.class,
            EvaluatorsRevisionsGet.class,
            EvaluatorsRevisionsActivate.class
        })
public class EvaluatorsRevisions extends BaseCommand {

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }
}
