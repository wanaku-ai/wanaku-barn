package ai.wanaku.operator.wanaku;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.WanakuForwards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuIntegrationReconcilerTest {
    private static final String NAMESPACE = "test";

    KubernetesClient client;

    private WanakuIntegrationReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuIntegrationReconciler();
        reconciler.kubernetesClient = client;
        client.configMaps()
                .inNamespace(NAMESPACE)
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName("orders-routes")
                        .endMetadata()
                        .addToData("orders.camel.yaml", "- route: {}")
                        .build())
                .create();
    }

    @Test
    void exactlyOneRoutesSourceIsRequired() {
        createRouter(false);
        WanakuIntegration integration = createIntegration(configMapRoutes());
        integration.getSpec().getRoutes().setRoutesRef("file:///tmp/other.camel.yaml");

        reconciler.reconcile(integration, null);

        assertThat(condition(integration, "Reconciled").getReason()).isEqualTo("ValidationError");
        assertThat(condition(integration, "Reconciled").getMessage()).contains("exactly one");
    }

    @Test
    void datastoreRoutesRequireBarn() {
        createRouter(false);
        WanakuIntegrationSpec.Routes routes = new WanakuIntegrationSpec.Routes();
        routes.setRoutesRef("datastore://orders.camel.yaml");

        WanakuIntegration integration = createIntegration(routes);
        reconciler.reconcile(integration, null);

        assertThat(condition(integration, "Reconciled").getMessage()).contains("spec.barn.enabled");
    }

    @Test
    void deploysTheCapabilityWithRegistration() {
        createRouter(false);
        WanakuIntegration integration = createIntegration(configMapRoutes());

        reconciler.reconcile(integration, null);

        Deployment deployment = deployment();
        var container =
                deployment.getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertThat(env(container.getEnv()))
                .containsEntry("REGISTRATION_URL", "http://barn-wanaku-dev:8080")
                .containsEntry("SERVICE_NAME", "orders")
                .containsEntry("ROUTES_PATH", "file:///opt/cic/routes/orders.camel.yaml")
                .containsEntry("MCP_PORT", "9090")
                .containsEntry("WANAKU_REGISTER", "true")
                .containsEntry("WANAKU_URL", "http://server-wanaku-dev:8080")
                .containsEntry("MCP_ADDRESS", "http://orders-cic.test.svc:9090/mcp");
        assertThat(container.getReadinessProbe().getHttpGet().getPath()).isEqualTo("/observe/health/ready");
        assertThat(client.services()
                        .inNamespace(NAMESPACE)
                        .withName("orders-cic")
                        .get()
                        .getSpec()
                        .getPublishNotReadyAddresses())
                .isTrue();
        assertThat(condition(integration, "Registered").getReason()).isEqualTo("WaitingForRuntime");
        assertThat(integration.getStatus().getForwardName()).isEqualTo("orders");
        assertThat(integration.getStatus().getWanakuEndpoint()).isEqualTo("https://mcp.example.com/default/mcp");
    }

    @Test
    void aRegisteredIntegrationIsReady() {
        createRouter(false);
        reconciler.forwards = (url, name, token) -> new WanakuForwards.Forward(true, null, Map.of("cic.instance", "x"));
        WanakuIntegration integration = createIntegration(configMapRoutes());
        reconciler.reconcile(integration, null);
        markAvailable();

        reconciler.reconcile(integration, null);

        assertThat(condition(integration, "Registered").getStatus()).isEqualTo("True");
        assertThat(condition(integration, "Ready").getStatus()).isEqualTo("True");
    }

    @Test
    void aForwardWithoutTheCapabilityOwnerLabelIsReported() {
        createRouter(false);
        // A semantic router registered this name, not the capability
        reconciler.forwards = (url, name, token) -> new WanakuForwards.Forward(true, null, Map.of("wsr.instance", "x"));
        WanakuIntegration integration = createIntegration(configMapRoutes());
        reconciler.reconcile(integration, null);
        markAvailable();

        reconciler.reconcile(integration, null);

        assertThat(condition(integration, "Registered").getReason()).isEqualTo("ForeignForward");
    }

    @Test
    void withoutRegistrationOnlyAvailabilityCounts() {
        createRouter(false);
        reconciler.forwards = (url, name, token) -> {
            throw new AssertionError("no router call expected");
        };
        WanakuIntegration integration = createIntegration(configMapRoutes());
        WanakuIntegrationSpec.Registration registration = new WanakuIntegrationSpec.Registration();
        registration.setEnabled(false);
        integration.getSpec().setRegistration(registration);
        reconciler.reconcile(integration, null);
        markAvailable();

        reconciler.reconcile(integration, null);

        assertThat(env(deployment()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getEnv()))
                .doesNotContainKeys("WANAKU_REGISTER", "WANAKU_URL", "MCP_ADDRESS");
        assertThat(OperatorUtil.findCondition(integration.getStatus().getConditions(), "Registered"))
                .isNull();
        assertThat(condition(integration, "Ready").getStatus()).isEqualTo("True");
        assertThat(integration.getStatus().getForwardName()).isNull();
    }

    private void markAvailable() {
        Deployment deployment = deployment();
        deployment.setStatus(new DeploymentStatusBuilder()
                .withObservedGeneration(
                        deployment.getMetadata().getGeneration() != null
                                ? deployment.getMetadata().getGeneration()
                                : 0L)
                .withReplicas(1)
                .withUpdatedReplicas(1)
                .withAvailableReplicas(1)
                .withConditions(new DeploymentConditionBuilder()
                        .withType("Available")
                        .withStatus("True")
                        .build())
                .build());
        client.apps().deployments().inNamespace(NAMESPACE).resource(deployment).updateStatus();
    }

    private Deployment deployment() {
        return client.apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .withName("orders-cic")
                .get();
    }

    private static Map<String, String> env(List<EnvVar> env) {
        Map<String, String> values = new HashMap<>();
        env.stream().filter(e -> e.getValue() != null).forEach(e -> values.put(e.getName(), e.getValue()));
        return values;
    }

    private static Condition condition(WanakuIntegration integration, String type) {
        Condition condition = OperatorUtil.findCondition(integration.getStatus().getConditions(), type);
        assertThat(condition).as(type).isNotNull();
        return condition;
    }

    private static WanakuIntegrationSpec.Routes configMapRoutes() {
        WanakuTypes.ConfigMapSource configMap = new WanakuTypes.ConfigMapSource();
        configMap.setName("orders-routes");
        configMap.setMain("orders.camel.yaml");
        WanakuIntegrationSpec.Routes routes = new WanakuIntegrationSpec.Routes();
        routes.setConfigMapRef(configMap);
        return routes;
    }

    private void createRouter(boolean barn) {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("wanaku-dev")
                .withNamespace(NAMESPACE)
                .build());
        router.setSpec(new WanakuRouterSpec());
        WanakuRouterSpec.BarnSpec barnSpec = new WanakuRouterSpec.BarnSpec();
        barnSpec.setEnabled(barn);
        router.getSpec().setBarn(barnSpec);
        WanakuRouterStatus status = new WanakuRouterStatus();
        status.setEndpoints(Map.of("mcp", "https://mcp.example.com/default/mcp"));
        router.setStatus(status);
        client.resource(router).create();
    }

    private static WanakuIntegration createIntegration(WanakuIntegrationSpec.Routes routes) {
        WanakuIntegration integration = new WanakuIntegration();
        integration.setMetadata(new ObjectMetaBuilder()
                .withName("orders")
                .withNamespace(NAMESPACE)
                .withUid("cic-uid")
                .withGeneration(1L)
                .build());
        WanakuIntegrationSpec spec = new WanakuIntegrationSpec();
        spec.setRouterRef("wanaku-dev");
        spec.setRoutes(routes);
        integration.setSpec(spec);
        return integration;
    }
}
