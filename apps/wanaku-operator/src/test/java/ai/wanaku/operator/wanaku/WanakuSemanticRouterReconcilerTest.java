package ai.wanaku.operator.wanaku;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.WanakuForwards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuSemanticRouterReconcilerTest {
    private static final String NAMESPACE = "test";

    KubernetesClient client;

    private WanakuSemanticRouterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuSemanticRouterReconciler();
        reconciler.kubernetesClient = client;
        client.configMaps()
                .inNamespace(NAMESPACE)
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName("support-files")
                        .endMetadata()
                        .addToData("support.camel.yaml", "- route: {}")
                        .build())
                .create();
    }

    @Test
    void aMissingRouterIsAValidationError() {
        WanakuSemanticRouter wsr = createSemanticRouter(configMapSource());

        reconciler.reconcile(wsr, null);

        assertThat(condition(wsr, "Reconciled").getReason()).isEqualTo("ValidationError");
        assertThat(condition(wsr, "Reconciled").getMessage()).contains("WanakuRouter wanaku-dev does not exist");
    }

    @Test
    void exactlyOneSourceIsRequired() {
        createRouter(false);
        WanakuSemanticRouterSpec.Source source = configMapSource();
        WanakuSemanticRouterSpec.SemanticRouteSource route = new WanakuSemanticRouterSpec.SemanticRouteSource();
        route.setName("support");
        source.setSemanticRoute(route);

        WanakuSemanticRouter wsr = createSemanticRouter(source);
        reconciler.reconcile(wsr, null);

        assertThat(condition(wsr, "Reconciled").getMessage()).contains("exactly one");
    }

    @Test
    void barnSourcesRequireBarn() {
        createRouter(false);
        WanakuSemanticRouterSpec.Source source = new WanakuSemanticRouterSpec.Source();
        WanakuSemanticRouterSpec.SemanticRouteSource route = new WanakuSemanticRouterSpec.SemanticRouteSource();
        route.setName("support");
        source.setSemanticRoute(route);

        WanakuSemanticRouter wsr = createSemanticRouter(source);
        reconciler.reconcile(wsr, null);

        assertThat(condition(wsr, "Reconciled").getMessage()).contains("spec.barn.enabled");
    }

    @Test
    void deploysTheRuntimeAndWaitsForIt() {
        createRouter(true);
        WanakuSemanticRouter wsr = createSemanticRouter(configMapSource());

        UpdateControl<WanakuSemanticRouter> control = reconciler.reconcile(wsr, null);

        Deployment deployment = deployment();
        var container =
                deployment.getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertThat(container.getArgs()).containsExactly("runtime");
        assertThat(env(container.getEnv()))
                .containsEntry("WSR_REGISTRATION_URL", "http://server-wanaku-dev:8080")
                .containsEntry("WSR_BARN_URL", "http://barn-wanaku-dev:8080")
                .containsEntry("WSR_NAME", "support")
                .containsEntry("WSR_NAMESPACE", "finance")
                .containsEntry("WSR_MCP_ADDRESS", "http://support-wsr.test.svc:8090/mcp")
                .containsEntry("WSR_STATUS_BIND", "0.0.0.0")
                .containsEntry("WSR_ROUTES_REF", "file:/opt/wsr/routes/support.camel.yaml");
        assertThat(container.getReadinessProbe().getHttpGet().getPath()).isEqualTo("/api/v1/health/ready");
        assertThat(container.getStartupProbe().getHttpGet().getPath()).isEqualTo("/api/v1/health/started");
        assertThat(deployment.getSpec().getTemplate().getSpec().getVolumes())
                .anyMatch(v -> v.getConfigMap() != null
                        && "support-files".equals(v.getConfigMap().getName()));
        assertThat(deployment.getSpec().getStrategy().getType()).isEqualTo("RollingUpdate");
        Service service =
                client.services().inNamespace(NAMESPACE).withName("support-wsr").get();
        assertThat(service.getSpec().getPublishNotReadyAddresses()).isTrue();
        assertThat(condition(wsr, "Available").getReason()).isEqualTo("Progressing");
        assertThat(condition(wsr, "Registered").getReason()).isEqualTo("WaitingForRuntime");
        assertThat(condition(wsr, "Ready").getStatus()).isEqualTo("False");
        assertThat(control.getScheduleDelay()).isPresent();
        assertThat(wsr.getStatus().getWanakuEndpoint()).isEqualTo("https://mcp.example.com/finance/mcp");
    }

    @Test
    void passesPropertiesAndSecretEnvironmentVariablesToTheRuntime() {
        createRouter(true);
        WanakuSemanticRouter wsr = createSemanticRouter(configMapSource());
        wsr.getSpec()
                .setProperties(Map.of(
                        "camel.component.typesafe-ai.model", "my-model",
                        "camel.component.typesafe-ai.api-key", "{{env:TYPESAFE_API_KEY}}"));
        WanakuTypes.SecretKeyRef secret = new WanakuTypes.SecretKeyRef();
        secret.setName("llm-credentials");
        secret.setKey("api-key");
        WanakuTypes.EnvVarSource source = new WanakuTypes.EnvVarSource();
        source.setSecretKeyRef(secret);
        WanakuTypes.EnvVar apiKey = new WanakuTypes.EnvVar();
        apiKey.setName("TYPESAFE_API_KEY");
        apiKey.setValueFrom(source);
        wsr.getSpec().setEnv(List.of(apiKey));

        reconciler.reconcile(wsr, null);

        var container =
                deployment().getSpec().getTemplate().getSpec().getContainers().getFirst();
        // Sorted by key, so that the Deployment does not change between reconciliations
        assertThat(container.getArgs())
                .containsExactly(
                        "runtime",
                        "-p",
                        "camel.component.typesafe-ai.api-key={{env:TYPESAFE_API_KEY}}",
                        "-p",
                        "camel.component.typesafe-ai.model=my-model");
        EnvVar env = container.getEnv().stream()
                .filter(e -> "TYPESAFE_API_KEY".equals(e.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(env.getValue()).isNull();
        assertThat(env.getValueFrom().getSecretKeyRef().getName()).isEqualTo("llm-credentials");
        assertThat(env.getValueFrom().getSecretKeyRef().getKey()).isEqualTo("api-key");
    }

    @Test
    void aRegisteredRuntimeIsReady() {
        createRouter(true);
        AtomicReference<String> requested = new AtomicReference<>();
        reconciler.forwards = (url, name, token) -> {
            requested.set(url + " " + name + " " + token);
            return new WanakuForwards.Forward(true, null, Map.of("wsr.instance", "abc"));
        };
        client.secrets()
                .inNamespace(NAMESPACE)
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName("wanaku-token")
                        .endMetadata()
                        .withStringData(Map.of("token", "t0ken"))
                        .build())
                .create();
        WanakuSemanticRouter wsr = createSemanticRouter(configMapSource());
        WanakuTypes.SecretKeyRef tokenRef = new WanakuTypes.SecretKeyRef();
        tokenRef.setName("wanaku-token");
        wsr.getSpec().setWanakuTokenSecretRef(tokenRef);
        reconciler.reconcile(wsr, null);
        markAvailable();

        UpdateControl<WanakuSemanticRouter> control = reconciler.reconcile(wsr, null);

        assertThat(condition(wsr, "Registered").getStatus()).isEqualTo("True");
        assertThat(condition(wsr, "Ready").getStatus()).isEqualTo("True");
        assertThat(control.getScheduleDelay()).isEmpty();
        assertThat(requested.get()).isEqualTo("http://server-wanaku-dev:8080 support t0ken");
        assertThat(env(deployment()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getEnv()))
                .containsEntry("WSR_WANAKU_TOKEN_ENV", "WANAKU_TOKEN");
    }

    @Test
    void registrationProblemsAreReported() {
        createRouter(true);
        WanakuSemanticRouter wsr = createSemanticRouter(configMapSource());
        reconciler.reconcile(wsr, null);
        markAvailable();

        reconciler.forwards = (url, name, token) -> null;
        reconciler.reconcile(wsr, null);
        assertThat(condition(wsr, "Registered").getReason()).isEqualTo("NotRegistered");

        reconciler.forwards = (url, name, token) -> new WanakuForwards.Forward(true, null, Map.of());
        reconciler.reconcile(wsr, null);
        assertThat(condition(wsr, "Registered").getReason()).isEqualTo("ForeignForward");

        reconciler.forwards = (url, name, token) ->
                new WanakuForwards.Forward(false, "connection refused", Map.of("wsr.instance", "a"));
        reconciler.reconcile(wsr, null);
        assertThat(condition(wsr, "Registered").getReason()).isEqualTo("ForwardUnavailable");
        assertThat(condition(wsr, "Registered").getMessage()).contains("connection refused");

        reconciler.forwards = (url, name, token) -> {
            throw new IOException("Connection refused");
        };
        reconciler.reconcile(wsr, null);
        assertThat(condition(wsr, "Registered").getReason()).isEqualTo("RouterUnreachable");
        assertThat(condition(wsr, "Ready").getStatus()).isEqualTo("False");
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
                .withName("support-wsr")
                .get();
    }

    private static Map<String, String> env(List<EnvVar> env) {
        Map<String, String> values = new java.util.HashMap<>();
        env.stream().filter(e -> e.getValue() != null).forEach(e -> values.put(e.getName(), e.getValue()));
        return values;
    }

    private static Condition condition(WanakuSemanticRouter wsr, String type) {
        Condition condition = OperatorUtil.findCondition(wsr.getStatus().getConditions(), type);
        assertThat(condition).as(type).isNotNull();
        return condition;
    }

    private static WanakuSemanticRouterSpec.Source configMapSource() {
        WanakuTypes.ConfigMapSource configMap = new WanakuTypes.ConfigMapSource();
        configMap.setName("support-files");
        configMap.setMain("support.camel.yaml");
        WanakuSemanticRouterSpec.Source source = new WanakuSemanticRouterSpec.Source();
        source.setConfigMapRef(configMap);
        return source;
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
        status.setEndpoints(Map.of("mcp", "https://mcp.example.com/mcp"));
        router.setStatus(status);
        client.resource(router).create();
        client.resource(router).updateStatus();
    }

    private static WanakuSemanticRouter createSemanticRouter(WanakuSemanticRouterSpec.Source source) {
        WanakuSemanticRouter wsr = new WanakuSemanticRouter();
        wsr.setMetadata(new ObjectMetaBuilder()
                .withName("support")
                .withNamespace(NAMESPACE)
                .withUid("wsr-uid")
                .withGeneration(1L)
                .build());
        WanakuSemanticRouterSpec spec = new WanakuSemanticRouterSpec();
        spec.setRouterRef("wanaku-dev");
        spec.setSource(source);
        WanakuTypes.ForwardSpec forward = new WanakuTypes.ForwardSpec();
        forward.setNamespace("finance");
        spec.setForward(forward);
        wsr.setSpec(spec);
        return wsr;
    }
}
