package ai.wanaku.cli.main.commands.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
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
class CloudRunSemanticTest {
    private static final String NAMESPACE = "wanaku";
    private static final String MANIFEST =
            """
            main=support/support.camel.yaml
            dependencies=support/support.dependencies.txt
            configuration=support/service.properties
            kamelets=support/kamelets
            expert.bean=supportExpert
            tool.name=route_support
            tool.tags=wsr-support
            """;

    KubernetesClient client;

    @TempDir
    Path tmp;

    private WanakuPrinter printer;
    private Path route;

    @BeforeEach
    void setUp() throws Exception {
        printer = mock(WanakuPrinter.class);
        Path support =
                Files.createDirectories(tmp.resolve("catalog/support/kamelets")).getParent();
        route = support.resolve("support.camel.yaml");
        Files.writeString(route, "- route:\n    from:\n      uri: direct:support\n");
        Files.writeString(support.resolve("semantic-router.properties"), MANIFEST);
        Files.writeString(support.resolve("support.dependencies.txt"), "");
        Files.writeString(support.resolve("service.properties"), "department=support\n");
        Files.writeString(support.resolve("kamelets/classify.kamelet.yaml"), "apiVersion: camel.apache.org/v1\n");
        createRouter();
    }

    @Test
    void submitsTheRouteFileTree() throws Exception {
        CloudRunSemantic command = command();
        command.wait = false;
        command.imagePullPolicy = CloudRunSupport.PullPolicy.Always;

        assertThat(command.doCall(null, printer)).isZero();

        ConfigMap files = client.configMaps()
                .inNamespace(NAMESPACE)
                .withName("support-semantic")
                .get();
        assertThat(files.getData())
                .containsOnlyKeys(
                        "support--support.camel.yaml",
                        "support--semantic-router.properties",
                        "support--support.dependencies.txt",
                        "support--service.properties",
                        "support--kamelets--classify.kamelet.yaml");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>)
                semanticRouter("support").getAdditionalProperties().get("spec");
        assertThat(spec.get("source").toString())
                .contains("main=support/support.camel.yaml")
                .contains("path=support/kamelets/classify.kamelet.yaml");
        assertThat(spec.get("forward").toString()).contains("name=route_support");
        assertThat(spec).containsEntry("imagePullPolicy", "Always");
        assertThat(files.getMetadata().getOwnerReferences().getFirst().getKind())
                .isEqualTo("WanakuSemanticRouter");
    }

    @Test
    void aMissingRouteFileIsReportedAsMissing() throws Exception {
        Files.delete(route);

        assertThat(command().doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
        verify(printer).printErrorMessage(contains("does not exist"));
    }

    @Test
    void aMissingManifestIsInvalidInput() throws Exception {
        Files.delete(route.resolveSibling("semantic-router.properties"));

        assertThat(command().doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
        verify(printer).printErrorMessage(contains("semantic-router.properties"));
    }

    @Test
    void aManifestWithoutAToolNameIsInvalidInput() throws Exception {
        Files.writeString(
                route.resolveSibling("semantic-router.properties"), MANIFEST.replace("tool.name=route_support\n", ""));

        assertThat(command().doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
        verify(printer).printErrorMessage(contains("has no tool.name"));
    }

    @Test
    void aMainThatNamesAnotherFileIsInvalidInput() throws Exception {
        Files.writeString(
                route.resolveSibling("semantic-router.properties"),
                MANIFEST.replace("main=support/support.camel.yaml", "main=support/other.camel.yaml"));

        assertThat(command().doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
    }

    @Test
    void referencesOutsideTheRouteTreeAreInvalidInput() throws Exception {
        Files.writeString(
                route.resolveSibling("semantic-router.properties"),
                MANIFEST.replace("configuration=support/service.properties", "configuration=../secret.properties"));

        assertThat(command().doCall(null, printer)).isEqualTo(CloudRunSupport.EXIT_INVALID_INPUT);
        verify(printer).printErrorMessage(contains("points outside"));
    }

    @Test
    void aFailedRuntimeStartIsReported() throws Exception {
        Thread operator = new Thread(() -> {
            for (int i = 0; i < 100 && semanticRouter("support") == null; i++) {
                sleep();
            }
            GenericKubernetesResource resource = semanticRouter("support");
            resource.getAdditionalProperties()
                    .put(
                            "status",
                            Map.of(
                                    "conditions",
                                    List.of(Map.of(
                                            "type", "Ready",
                                            "status", "False",
                                            "reason", "CrashLoopBackOff",
                                            "message", "last termination: McpReadinessException"))));
            client.genericKubernetesResources(RuntimeKind.SEMANTIC.context())
                    .inNamespace(NAMESPACE)
                    .resource(resource)
                    .updateStatus();
        });
        operator.start();

        int exit = command().doCall(null, printer);
        operator.join();

        assertThat(exit).isEqualTo(1);
        verify(printer).printErrorMessage(contains("McpReadinessException"));
    }

    private GenericKubernetesResource semanticRouter(String name) {
        return client.genericKubernetesResources(RuntimeKind.SEMANTIC.context())
                .inNamespace(NAMESPACE)
                .withName(name)
                .get();
    }

    private void createRouter() {
        client.genericKubernetesResources(CloudRunSupport.ROUTERS)
                .inNamespace(NAMESPACE)
                .resource(new GenericKubernetesResourceBuilder()
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
                                Map.of("conditions", List.of(Map.of("type", "Ready", "status", "True")))))
                        .build())
                .create();
    }

    private CloudRunSemantic command() {
        CloudRunSemantic command = new CloudRunSemantic();
        command.client = client;
        command.targetFile = tmp.resolve("cloud.properties");
        command.namespace = NAMESPACE;
        command.router = "wanaku-dev";
        command.file = route;
        command.forwardNamespace = "default";
        command.wait = true;
        command.timeoutSeconds = 10;
        command.pollInterval = Duration.ofMillis(50);
        return command;
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
