package ai.wanaku.cli.main.commands.cloud;

import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "delete",
        description = "Stop a runtime in the cloud target. The runtime removes its forward from the router")
public class CloudDelete extends CloudCommand {
    @Parameters(index = "0", description = "Runtime kind: ${COMPLETION-CANDIDATES}")
    RuntimeKind kind;

    @Parameters(index = "1", description = "Resource name")
    String name;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        final CloudTargetConfig target = target();
        final CloudRunSupport support = new CloudRunSupport(client(target), printer);
        final String ns = support.namespace(target);
        final boolean deleted = !client(target)
                .genericKubernetesResources(kind.context())
                .inNamespace(ns)
                .withName(name)
                .delete()
                .isEmpty();
        if (!deleted) {
            printer.printErrorMessage("%s %s does not exist in namespace %s".formatted(kind.kind(), name, ns));
            return EXIT_ERROR;
        }
        // The route files ConfigMap is owned by the resource, so Kubernetes deletes it too
        printer.printSuccessMessage("Deleted %s %s".formatted(kind.kind(), name));
        return EXIT_OK;
    }
}
