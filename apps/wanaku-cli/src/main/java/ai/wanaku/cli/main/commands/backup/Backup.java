package ai.wanaku.cli.main.commands.backup;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

@CommandLine.Command(
        name = "backup",
        description = "Export and import the Barn data",
        subcommands = {BackupExport.class, BackupImport.class})
public class Backup extends BaseCommand {
    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }
}
