package ai.wanaku.cli.main.commands.cloud;

import java.nio.file.Path;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "set", description = "Store the Kubernetes context, namespace and WanakuRouter to use")
public class CloudTargetSet extends BaseCommand {
    @Option(
            names = {"--context"},
            description = "Kubernetes context")
    String context;

    @Option(
            names = {"--namespace", "-n"},
            description = "Kubernetes namespace")
    String namespace;

    @Option(
            names = {"--router"},
            description = "Name of the WanakuRouter that runtimes register with")
    String router;

    /** Set by tests; otherwise $WANAKU_HOME/cloud.properties. */
    Path targetFile;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        final Path path = targetFile != null ? targetFile : CloudTargetConfig.defaultPath();
        final CloudTargetConfig target = CloudTargetConfig.load(path).withOverrides(context, namespace, router);
        target.save(path);
        printer.printSuccessMessage("Stored the cloud target in " + path);
        return EXIT_OK;
    }
}
