package ai.wanaku.cli.main.commands.cloud;

import java.nio.file.Path;
import java.time.Duration;
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
        name = "integration",
        description = "Run a Camel YAML route file with MCP tools on the Camel Integration Capability, "
                + "registered with the target WanakuRouter")
public class CloudRunIntegration extends CloudCommand {
    static final String DEFAULT_TAGS = "wanaku";

    @Parameters(index = "0", description = "Camel YAML route file")
    Path file;

    @Option(
            names = {"--name"},
            description = "Resource name (default: derived from the file name)")
    String name;

    @Option(
            names = {"--forward-name"},
            description = "Forward name in the router (default: the resource name)")
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
            names = {"--tags"},
            description = "Route tags to serve as MCP tools",
            defaultValue = DEFAULT_TAGS)
    String tags;

    @Option(
            names = {"--image"},
            description = "Capability image (default: chosen by the operator)")
    String image;

    @Option(
            names = {"--image-pull-policy"},
            description = "Image pull policy: ${COMPLETION-CANDIDATES} (default: chosen by the operator). "
                    + "Use Always to get a new build of a moving tag such as latest")
    CloudRunSupport.PullPolicy imagePullPolicy;

    @Option(
            names = {"--wait"},
            negatable = true,
            defaultValue = "true",
            fallbackValue = "true",
            description = "Wait until the integration is ready (default: true)")
    boolean wait;

    @Option(
            names = {"--timeout"},
            defaultValue = "300",
            description = "Seconds to wait for the integration to become ready")
    long timeoutSeconds;

    /** Set by tests to poll faster. */
    Duration pollInterval;

    @Override
    public Integer doCall(Terminal terminal, WanakuPrinter printer) throws Exception {
        try {
            final String routes = IntegrationInput.read(file, tags);
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

            final String fileName = file.getFileName().toString();
            final ConfigMap files = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(resourceName + "-routes")
                    .withNamespace(ns)
                    .addToLabels("app.kubernetes.io/managed-by", "wanaku-cli")
                    .endMetadata()
                    .addToData(fileName, routes)
                    .build();
            final GenericKubernetesResource submitted = support.submit(
                    RuntimeKind.INTEGRATION, ns, files, integration(resourceName, ns, target.getRouter(), fileName));
            printer.printInfoMessage("Submitted WanakuIntegration %s in namespace %s"
                    .formatted(submitted.getMetadata().getName(), ns));

            if (!wait) {
                printer.printInfoMessage("Follow it with: wanaku cloud status integration " + resourceName);
                return EXIT_OK;
            }
            return support.waitForReady(RuntimeKind.INTEGRATION, ns, resourceName, Duration.ofSeconds(timeoutSeconds));
        } catch (CloudRunSupport.CloudException e) {
            printer.printErrorMessage(e.getMessage());
            return e.exitCode();
        }
    }

    private GenericKubernetesResource integration(String resourceName, String ns, String router, String fileName) {
        final Map<String, Object> spec = new HashMap<>();
        spec.put("routerRef", router);
        spec.put("routes", Map.of("configMapRef", Map.of("name", resourceName + "-routes", "main", fileName)));
        spec.put("mcp", Map.of("tags", tags));
        final Map<String, Object> registration = new HashMap<>();
        registration.put("enabled", true);
        registration.put("namespace", forwardNamespace);
        if (forwardName != null) {
            registration.put("name", forwardName);
        }
        spec.put("registration", registration);
        if (dependencies != null && !dependencies.isEmpty()) {
            spec.put("dependencies", dependencies);
        }
        if (image != null) {
            spec.put("image", image);
        }
        if (imagePullPolicy != null) {
            spec.put("imagePullPolicy", imagePullPolicy.name());
        }
        return new GenericKubernetesResourceBuilder()
                .withApiVersion(CloudRunSupport.API_VERSION)
                .withKind(RuntimeKind.INTEGRATION.kind())
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(ns)
                .addToLabels("app.kubernetes.io/managed-by", "wanaku-cli")
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }
}
