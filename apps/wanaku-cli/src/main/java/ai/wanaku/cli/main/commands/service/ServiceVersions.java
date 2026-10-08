package ai.wanaku.cli.main.commands.service;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

@CommandLine.Command(
        name = "versions",
        description = "Manage the version history of service catalogs and templates",
        subcommands = {ServiceVersionsList.class, ServiceVersionsDownload.class, ServiceVersionsRestore.class})
public class ServiceVersions extends BaseCommand {
    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }
}
