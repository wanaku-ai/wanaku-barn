package ai.wanaku.cli.main.commands.cloud;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "semantic",
        description = "Run a semantic route on the Wanaku Semantic Router, registered with the target WanakuRouter. "
                + "The route needs semantic-router.properties in the same directory")
public class CloudRunSemantic extends CloudCommand {
    @Parameters(index = "0", description = "Camel YAML semantic route file")
    Path file;

    @Option(
            names = {"--name"},
            description = "Resource name (default: derived from the file name)")
    String name;

    @Option(
            names = {"--forward-name"},
            description = "Forward name in the router (default: tool.name from semantic-router.properties)")
    String forwardName;

    @Option(
            names = {"--forward-namespace"},
            description = "Wanaku namespace of the forward",
            defaultValue = "default")
    String forwardNamespace;

    @Option(
            names = {"--dependencies"},
            split = ",",
            description = "Extra Maven dependencies (group:artifact:version), comma separated")
    List<String> dependencies;

    @Option(
            names = {"--image"},
            description = "Semantic router image (default: chosen by the operator)")
    String image;

    @Option(
            names = {"--wait"},
            negatable = true,
            defaultValue = "true",
            fallbackValue = "true",
            description = "Wait until the semantic router is ready (default: true)")
    boolean wait;

    @Option(
            names = {"--timeout"},
            defaultValue = "600",
            description = "Seconds to wait; the runtime resolves Maven dependencies at startup")
    long timeoutSeconds;

    /** Set by tests to poll faster. */
    Duration pollInterval;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        try {
            final SemanticInput input = SemanticInput.read(file);
            final String resourceName = name != null
                    ? name
                    : CloudRunSupport.resourceName(file.getFileName().toString());
            if (!resourceName.matches("[a-z0-9]([-a-z0-9]{0,48}[a-z0-9])?")) {
                throw IntegrationInput.invalid(
                        "'%s' is not a valid resource name: use --name with lowercase letters, digits and '-'"
                                .formatted(resourceName));
            }

            final CloudTargetConfig target = target();
            final KubernetesClient kubernetes = client(target);
            final CloudRunSupport support = new CloudRunSupport(kubernetes, printer);
            if (pollInterval != null) {
                support.setPollInterval(pollInterval);
            }
            final String ns = support.namespace(target);
            support.requireReadyRouter(ns, target.getRouter());

            final Map<String, String> data = new HashMap<>();
            final List<Map<String, String>> items = new ArrayList<>();
            input.files.forEach((path, content) -> {
                data.put(SemanticInput.key(path), content);
                items.add(Map.of("key", SemanticInput.key(path), "path", path));
            });
            final ConfigMap files = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(resourceName + "-semantic")
                    .withNamespace(ns)
                    .addToLabels("app.kubernetes.io/managed-by", "wanaku-cli")
                    .endMetadata()
                    .withData(data)
                    .build();
            final GenericKubernetesResource submitted = support.submit(
                    RuntimeKind.SEMANTIC,
                    ns,
                    files,
                    semanticRouter(resourceName, ns, target.getRouter(), input, items));
            printer.printInfoMessage("Submitted WanakuSemanticRouter %s in namespace %s"
                    .formatted(submitted.getMetadata().getName(), ns));

            if (!wait) {
                printer.printInfoMessage("Follow it with: wanaku cloud status semantic " + resourceName);
                return EXIT_OK;
            }
            return support.waitForReady(RuntimeKind.SEMANTIC, ns, resourceName, Duration.ofSeconds(timeoutSeconds));
        } catch (CloudRunSupport.CloudException e) {
            printer.printErrorMessage(e.getMessage());
            return e.exitCode();
        }
    }

    private GenericKubernetesResource semanticRouter(
            String resourceName, String ns, String router, SemanticInput input, List<Map<String, String>> items) {
        final Map<String, Object> spec = new HashMap<>();
        spec.put("routerRef", router);
        spec.put(
                "source",
                Map.of("configMapRef", Map.of("name", resourceName + "-semantic", "main", input.main, "items", items)));
        spec.put(
                "forward",
                Map.of("name", forwardName != null ? forwardName : input.toolName, "namespace", forwardNamespace));
        if (dependencies != null && !dependencies.isEmpty()) {
            spec.put("dependencies", dependencies);
        }
        if (image != null) {
            spec.put("image", image);
        }
        return new GenericKubernetesResourceBuilder()
                .withApiVersion(CloudRunSupport.API_VERSION)
                .withKind(RuntimeKind.SEMANTIC.kind())
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(ns)
                .addToLabels("app.kubernetes.io/managed-by", "wanaku-cli")
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }
}
