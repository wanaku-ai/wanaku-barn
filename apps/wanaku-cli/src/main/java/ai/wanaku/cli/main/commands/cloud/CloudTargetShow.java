package ai.wanaku.cli.main.commands.cloud;

import java.nio.file.Path;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine.Command;

@Command(name = "show", description = "Show the stored cloud target")
public class CloudTargetShow extends BaseCommand {
    /** Set by tests; otherwise $WANAKU_HOME/cloud.properties. */
    Path targetFile;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        final CloudTargetConfig target =
                CloudTargetConfig.load(targetFile != null ? targetFile : CloudTargetConfig.defaultPath());
        printer.printValue("context: " + valueOrDefault(target.getContext(), "(current kubeconfig context)"));
        printer.printValue("namespace: " + valueOrDefault(target.getNamespace(), "(context namespace)"));
        printer.printValue("router: " + valueOrDefault(target.getRouter(), "(not set)"));
        return EXIT_OK;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
