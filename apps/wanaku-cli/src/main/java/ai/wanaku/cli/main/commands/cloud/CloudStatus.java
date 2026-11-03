package ai.wanaku.cli.main.commands.cloud;

import java.util.Map;
import org.jline.terminal.Terminal;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "status", description = "Show the status of a runtime in the cloud target")
public class CloudStatus extends CloudCommand {
    @Parameters(index = "0", description = "Runtime kind: ${COMPLETION-CANDIDATES}")
    RuntimeKind kind;

    @Parameters(index = "1", description = "Resource name")
    String name;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        final CloudTargetConfig target = target();
        final CloudRunSupport support = new CloudRunSupport(client(target), printer);
        final String ns = support.namespace(target);
        final GenericKubernetesResource resource = client(target)
                .genericKubernetesResources(kind.context())
                .inNamespace(ns)
                .withName(name)
                .get();
        if (resource == null) {
            printer.printErrorMessage("%s %s does not exist in namespace %s".formatted(kind.kind(), name, ns));
            return EXIT_ERROR;
        }
        for (Map<String, Object> condition : CloudRunSupport.conditions(resource)) {
            printer.printValue("%s=%s (%s): %s"
                    .formatted(
                            condition.get("type"),
                            condition.get("status"),
                            condition.get("reason"),
                            condition.get("message")));
        }
        final Map<String, Object> status = CloudRunSupport.status(resource);
        for (String field : new String[] {"forwardName", "wanakuEndpoint", "mcpAddress"}) {
            if (status.get(field) != null) {
                printer.printValue(field + ": " + status.get(field));
            }
        }
        final Map<String, Object> ready = CloudRunSupport.condition(resource, "Ready");
        return ready != null && "True".equals(ready.get("status")) ? EXIT_OK : EXIT_ERROR;
    }
}
