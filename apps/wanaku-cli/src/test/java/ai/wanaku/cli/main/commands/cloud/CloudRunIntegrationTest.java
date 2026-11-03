package ai.wanaku.cli.main.commands.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import ai.wanaku.cli.main.support.WanakuPrinter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@EnableKubernetesMockClient(crud = true)
class CloudRunIntegrationTest {
    private static final String NAMESPACE = "wanaku";
    private static final String TOOL_ROUTE =
            """
            - route:
                id: echo-tool
                from:
                  uri: ai-tool:echo
                  parameters:
                    tags: wanaku
                    description: Echo the message
                  steps:
                    - setBody:
                        simple: "${header.message}"
            """;

    KubernetesClient client;

    @TempDir
    Path tmp;

    private WanakuPrinter printer;

    @BeforeEach
    void setUp() {
        printer = mock(WanakuPrinter.class);
    }

    @Test
    void aMissingFileIsInvalidInput() throws Exception {
        CloudRunIntegration command = command(tmp.resolve("missing.camel.yaml"));

        assertThat(command.doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
        assertThat(client.configMaps().inNamespace(NAMESPACE).list().getItems()).isEmpty();
    }

    @Test
    void aFileWithoutAnMcpToolIsInvalidInput() throws Exception {
        Path file = write("timer.camel.yaml", "- route:\n    from:\n      uri: timer:tick\n");

        assertThat(command(file).doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
        verify(printer).printErrorMessage(contains("has no MCP tool"));
    }

    @Test
    void aToolWithOtherTagsIsInvalidInput() throws Exception {
        Path file = write("echo.camel.yaml", TOOL_ROUTE);
        CloudRunIntegration command = command(file);
        command.tags = "finance";

        assertThat(command.doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
    }

    @Test
    void notYamlIsInvalidInput() throws Exception {
        Path file = write("broken.camel.yaml", "- route: [unclosed");

        assertThat(command(file).doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
    }

    @Test
    void aMissingRouterMeansTheTargetIsUnreachable() throws Exception {
        assertThat(command(write("echo.camel.yaml", TOOL_ROUTE)).doCall(null, printer))
                .isEqualTo(CloudRunSupport.EXIT_TARGET_UNREACHABLE);
        verify(printer).printErrorMessage(contains("WanakuRouter wanaku-dev does not exist"));
    }

    @Test
    void aRouterThatIsNotReadyMeansTheTargetIsUnreachable() throws Exception {
        createRouter("False", "Progressing");

        assertThat(command(write("echo.camel.yaml", TOOL_ROUTE)).doCall(null, printer))
                .isEqualTo(CloudRunSupport.EXIT_TARGET_UNREACHABLE);
        verify(printer).printErrorMessage(contains("is not ready"));
    }

    @Test
    void submitsTheRoutesAndWaitsUntilReady() throws Exception {
        createRouter("True", "ReconciliationSucceeded");
        CloudRunIntegration command = command(write("echo.camel.yaml", TOOL_ROUTE));
        command.dependencies = List.of("org.apache.camel:camel-http:4.22.1");
        command.image = "quay.io/example/cic:dev";
        command.imagePullPolicy = CloudRunSupport.PullPolicy.Always;
        Thread operator = operatorReports("True", "ReconciliationSucceeded");

        int exit = command.doCall(null, printer);
        operator.join();

        assertThat(exit).isZero();
        GenericKubernetesResource integration = integration("echo");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec =
                (Map<String, Object>) integration.getAdditionalProperties().get("spec");
        assertThat(spec)
                .containsEntry("routerRef", "wanaku-dev")
                .containsEntry("dependencies", List.of("org.apache.camel:camel-http:4.22.1"))
                .containsEntry("image", "quay.io/example/cic:dev")
                .containsEntry("imagePullPolicy", "Always");
        assertThat(spec.get("routes").toString()).contains("echo-routes").contains("echo.camel.yaml");
        ConfigMap files = client.configMaps()
                .inNamespace(NAMESPACE)
                .withName("echo-routes")
                .get();
        assertThat(files.getData()).containsEntry("echo.camel.yaml", TOOL_ROUTE);
        assertThat(files.getMetadata().getOwnerReferences()).singleElement().satisfies(owner -> {
            assertThat(owner.getKind()).isEqualTo("WanakuIntegration");
            assertThat(owner.getUid()).isEqualTo(integration.getMetadata().getUid());
        });
        verify(printer).printInfoMessage(contains("MCP endpoint: https://mcp.example.com/mcp"));
    }

    @Test
    void aRuntimeFailureStopsTheWait() throws Exception {
        createRouter("True", "ReconciliationSucceeded");
        Thread operator = operatorReports("False", "CrashLoopBackOff");

        int exit = command(write("echo.camel.yaml", TOOL_ROUTE)).doCall(null, printer);
        operator.join();

        assertThat(exit).isEqualTo(1);
        verify(printer).printErrorMessage(contains("CrashLoopBackOff"));
    }

    @Test
    void aRegistrationThatNeverCompletesHasItsOwnExitCode() throws Exception {
        createRouter("True", "ReconciliationSucceeded");
        CloudRunIntegration command = command(write("echo.camel.yaml", TOOL_ROUTE));
        command.timeoutSeconds = 1;
        Thread operator = operatorReports("False", "NotRegistered");

        int exit = command.doCall(null, printer);
        operator.join();

        assertThat(exit).isEqualTo(CloudRunSupport.EXIT_REGISTRATION_FAILED);
    }

    @Test
    void aTimeoutReportsWhyThePodIsNotReady() throws Exception {
        createRouter("True", "ReconciliationSucceeded");
        client.pods()
                .inNamespace(NAMESPACE)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName("echo-cic-abc")
                        .addToLabels("app", "echo-cic")
                        .endMetadata()
                        .withNewStatus()
                        .addNewContainerStatus()
                        .withName("cic")
                        .withNewState()
                        .withNewWaiting()
                        .withReason("ImagePullBackOff")
                        .withMessage("Back-off pulling image \"quay.io/example/cic:dev\"")
                        .endWaiting()
                        .endState()
                        .endContainerStatus()
                        .endStatus()
                        .build())
                .create();
        client.v1()
                .events()
                .inNamespace(NAMESPACE)
                .resource(new EventBuilder()
                        .withNewMetadata()
                        .withName("echo-cic-abc.1")
                        .endMetadata()
                        .withType("Warning")
                        .withReason("Unhealthy")
                        .withMessage("Readiness probe failed: HTTP probe failed with statuscode: 404")
                        .withLastTimestamp("2026-10-17T08:00:00Z")
                        .withNewInvolvedObject()
                        .withKind("Pod")
                        .withName("echo-cic-abc")
                        .endInvolvedObject()
                        .build())
                .create();
        CloudRunIntegration command = command(write("echo.camel.yaml", TOOL_ROUTE));
        command.timeoutSeconds = 1;
        Thread operator = operatorReports("False", "Progressing");

        int exit = command.doCall(null, printer);
        operator.join();

        assertThat(exit).isEqualTo(1);
        verify(printer).printErrorMessage(contains("is not ready after 1 seconds"));
        verify(printer).printWarningMessage(contains("ImagePullBackOff: Back-off pulling image"));
        verify(printer).printWarningMessage(contains("Unhealthy: Readiness probe failed"));
    }

    @Test
    void noWaitReturnsAfterSubmission() throws Exception {
        createRouter("True", "ReconciliationSucceeded");
        CloudRunIntegration command = command(write("echo.camel.yaml", TOOL_ROUTE));
        command.wait = false;
        command.name = "orders";

        assertThat(command.doCall(null, printer)).isZero();
        assertThat(integration("orders")).isNotNull();
    }

    @Test
    void statusAndDeleteWorkOnTheSubmittedIntegration() throws Exception {
        createRouter("True", "ReconciliationSucceeded");
        CloudRunIntegration run = command(write("echo.camel.yaml", TOOL_ROUTE));
        run.wait = false;
        run.doCall(null, printer);
        setStatus("echo", "True", "ReconciliationSucceeded");

        CloudStatus status = new CloudStatus();
        status.client = client;
        status.namespace = NAMESPACE;
        status.targetFile = tmp.resolve("cloud.properties");
        status.kind = RuntimeKind.INTEGRATION;
        status.name = "echo";
        assertThat(status.doCall(null, printer)).isZero();

        CloudDelete delete = new CloudDelete();
        delete.client = client;
        delete.namespace = NAMESPACE;
        delete.targetFile = tmp.resolve("cloud.properties");
        delete.kind = RuntimeKind.INTEGRATION;
        delete.name = "echo";
        assertThat(delete.doCall(null, printer)).isZero();
        assertThat(integration("echo")).isNull();
        assertThat(delete.doCall(null, printer)).isEqualTo(1);
    }

    @Test
    void theStoredTargetIsUsedAndFlagsOverrideIt() throws Exception {
        Path targetFile = tmp.resolve("cloud.properties");
        CloudTargetSet set = new CloudTargetSet();
        set.targetFile = targetFile;
        set.namespace = "team-a";
        set.router = "stored-router";
        assertThat(set.doCall(null, printer)).isZero();

        CloudRunIntegration command = command(write("echo.camel.yaml", TOOL_ROUTE));
        command.targetFile = targetFile;
        command.namespace = null;
        command.router = null;
        CloudTargetConfig target = command.target();
        assertThat(target.getNamespace()).isEqualTo("team-a");
        assertThat(target.getRouter()).isEqualTo("stored-router");

        command.router = "override";
        assertThat(command.target().getRouter()).isEqualTo("override");
    }

    @Test
    void resourceNamesAreDerivedFromFileNames() {
        assertThat(CloudRunSupport.resourceName("Orders_API.camel.yaml")).isEqualTo("orders-api");
        assertThat(CloudRunSupport.resourceName("echo.yml")).isEqualTo("echo");
    }

    /** Plays the operator: waits for the integration, then reports the given Ready condition. */
    private Thread operatorReports(String status, String reason) {
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 100 && integration("echo") == null; i++) {
                sleep();
            }
            setStatus("echo", status, reason);
        });
        thread.start();
        return thread;
    }

    private void setStatus(String name, String status, String reason) {
        GenericKubernetesResource integration = integration(name);
        integration
                .getAdditionalProperties()
                .put(
                        "status",
                        Map.of(
                                "forwardName",
                                name,
                                "wanakuEndpoint",
                                "https://mcp.example.com/mcp",
                                "conditions",
                                List.of(Map.of("type", "Ready", "status", status, "reason", reason, "message", "m"))));
        client.genericKubernetesResources(RuntimeKind.INTEGRATION.context())
                .inNamespace(NAMESPACE)
                .resource(integration)
                .updateStatus();
    }

    private GenericKubernetesResource integration(String name) {
        return client.genericKubernetesResources(RuntimeKind.INTEGRATION.context())
                .inNamespace(NAMESPACE)
                .withName(name)
                .get();
    }

    private void createRouter(String status, String reason) {
        GenericKubernetesResource router = new GenericKubernetesResourceBuilder()
                .withApiVersion(CloudRunSupport.API_VERSION)
                .withKind("WanakuRouter")
                .withNewMetadata()
                .withName("wanaku-dev")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withAdditionalProperties(Map.of(
                        "spec",
                        Map.of(),
                        "status",
                        Map.of("conditions", List.of(Map.of("type", "Ready", "status", status, "reason", reason)))))
                .build();
        client.genericKubernetesResources(CloudRunSupport.ROUTERS)
                .inNamespace(NAMESPACE)
                .resource(router)
                .create();
    }

    private CloudRunIntegration command(Path file) {
        CloudRunIntegration command = new CloudRunIntegration();
        command.client = client;
        command.targetFile = tmp.resolve("cloud.properties");
        command.namespace = NAMESPACE;
        command.router = "wanaku-dev";
        command.file = file;
        command.tags = CloudRunIntegration.DEFAULT_TAGS;
        command.forwardNamespace = "default";
        command.wait = true;
        command.timeoutSeconds = 10;
        command.pollInterval = Duration.ofMillis(50);
        return command;
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
