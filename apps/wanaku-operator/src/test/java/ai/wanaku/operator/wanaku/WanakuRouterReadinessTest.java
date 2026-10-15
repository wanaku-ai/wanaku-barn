package ai.wanaku.operator.wanaku;

import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuRouterReadinessTest {
    private static final String NAMESPACE = "test";

    KubernetesClient client;

    private WanakuRouterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuRouterReconciler();
        reconciler.kubernetesClient = client;
    }

    @Test
    void delayedStartupReportsProgressingAndReschedules() {
        WanakuRouter router = createRouter();

        UpdateControl<WanakuRouter> control = reconciler.reconcile(router, null);

        assertThat(condition(router, "Reconciled").getStatus()).isEqualTo("True");
        Condition server = condition(router, "ServerAvailable");
        assertThat(server.getStatus()).isEqualTo("False");
        assertThat(server.getReason()).isEqualTo("Progressing");
        Condition ready = condition(router, OperatorUtil.READY_CONDITION);
        assertThat(ready.getStatus()).isEqualTo("False");
        assertThat(ready.getReason()).isEqualTo("Progressing");
        assertThat(ready.getMessage()).startsWith("ServerAvailable: Deployment wanaku-dev-server");
        assertThat(router.getStatus().getObservedGeneration()).isEqualTo(3L);
        assertThat(control.getScheduleDelay()).isPresent();
        assertThat(client.v1().events().inNamespace(NAMESPACE).list().getItems())
                .noneMatch(e -> "Progressing".equals(e.getReason()));
    }

    @Test
    void successfulStartupReportsReady() {
        WanakuRouter router = createRouter();
        reconciler.reconcile(router, null);
        markAvailable("wanaku-dev-server");

        UpdateControl<WanakuRouter> control = reconciler.reconcile(router, null);

        Condition ready = condition(router, OperatorUtil.READY_CONDITION);
        assertThat(ready.getStatus()).isEqualTo("True");
        assertThat(ready.getObservedGeneration()).isEqualTo(3L);
        assertThat(condition(router, "ServerAvailable").getStatus()).isEqualTo("True");
        assertThat(control.getScheduleDelay()).isEmpty();
    }

    @Test
    void readyTransitionTimeIsKeptWhileStatusIsUnchanged() {
        WanakuRouter router = createRouter();
        reconciler.reconcile(router, null);
        markAvailable("wanaku-dev-server");
        reconciler.reconcile(router, null);
        String firstTransition = condition(router, OperatorUtil.READY_CONDITION).getLastTransitionTime();

        reconciler.reconcile(router, null);

        assertThat(condition(router, OperatorUtil.READY_CONDITION).getLastTransitionTime())
                .isEqualTo(firstTransition);
    }

    @Test
    void imagePullFailureIsReportedWithPodReason() {
        WanakuRouter router = createRouter();
        reconciler.reconcile(router, null);
        client.pods().inNamespace(NAMESPACE).resource(failingPod()).create();

        reconciler.reconcile(router, null);

        Condition ready = condition(router, OperatorUtil.READY_CONDITION);
        assertThat(ready.getStatus()).isEqualTo("False");
        assertThat(ready.getReason()).isEqualTo("ImagePullBackOff");
        assertThat(ready.getMessage()).contains("container wanaku-server").contains("not found");
        assertThat(client.v1().events().inNamespace(NAMESPACE).list().getItems())
                .anyMatch(e -> "ImagePullBackOff".equals(e.getReason()) && "Warning".equals(e.getType()));
    }

    @Test
    void enabledComponentsAreRequiredForReadiness() {
        WanakuRouter router = createRouter();
        WanakuRouterSpec.RouterSpec barn = new WanakuRouterSpec.RouterSpec();
        barn.setEnabled(true);
        router.getSpec().setRouter(barn);
        reconciler.reconcile(router, null);
        markAvailable("wanaku-dev-server");

        reconciler.reconcile(router, null);

        assertThat(condition(router, "ServerAvailable").getStatus()).isEqualTo("True");
        assertThat(condition(router, "BarnAvailable").getStatus()).isEqualTo("False");
        assertThat(condition(router, OperatorUtil.READY_CONDITION).getMessage()).startsWith("BarnAvailable:");
    }

    @Test
    void failedReconciliationPublishesErrorCondition() {
        WanakuRouter router = createRouter();

        ErrorStatusUpdateControl<WanakuRouter> control = reconciler.updateErrorStatus(
                router,
                null,
                new KubernetesClientException(
                        "spec.host: Forbidden: you do not have permission to set the host field of the route"));

        assertThat(control.getResource()).isPresent();
        Condition reconciled = condition(router, "Reconciled");
        assertThat(reconciled.getStatus()).isEqualTo("False");
        assertThat(reconciled.getReason()).isEqualTo("ReconcileError");
        Condition ready = condition(router, OperatorUtil.READY_CONDITION);
        assertThat(ready.getStatus()).isEqualTo("False");
        assertThat(ready.getReason()).isEqualTo("ReconcileError");
        assertThat(ready.getMessage()).contains("Forbidden").doesNotContain("null");
    }

    @Test
    void validationErrorIsReported() {
        WanakuRouter router = createRouter();
        WanakuRouterSpec.AuthSpec auth = new WanakuRouterSpec.AuthSpec();
        auth.setEnabled(true);
        router.getSpec().setAuth(auth);

        reconciler.reconcile(router, null);

        assertThat(condition(router, "Reconciled").getReason()).isEqualTo("ValidationError");
        assertThat(condition(router, OperatorUtil.READY_CONDITION).getStatus()).isEqualTo("False");
    }

    @Test
    void servicesFromOlderOperatorsAreUpdated() {
        Service legacy = new ServiceBuilder()
                .withNewMetadata()
                .withName("server-wanaku-dev")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .addNewPort()
                .withName("management")
                .withPort(9090)
                .endPort()
                .endSpec()
                .build();
        client.services().inNamespace(NAMESPACE).resource(legacy).create();

        reconciler.reconcile(createRouter(), null);

        Service updated = client.services()
                .inNamespace(NAMESPACE)
                .withName("server-wanaku-dev")
                .get();
        assertThat(updated.getSpec().getPorts()).extracting("port").contains(8080, 8081, 8083, 8084);
        assertThat(updated.getMetadata().getAnnotations()).containsKey(OperatorUtil.DESIRED_HASH_ANNOTATION);
    }

    @Test
    void disabledAuthIsReportedOnce() {
        WanakuRouter router = createRouter();
        reconciler.reconcile(router, null);
        reconciler.reconcile(router, null);

        Condition auth = condition(router, "AuthConfigured");
        assertThat(auth.getStatus()).isEqualTo("True");
        assertThat(auth.getReason()).isEqualTo("AuthDisabled");
        assertThat(client.v1().events().inNamespace(NAMESPACE).list().getItems())
                .filteredOn(e -> "AuthDisabled".equals(e.getReason()))
                .hasSize(1);
    }

    @Test
    void missingAuthSecretBlocksTheProxy() {
        WanakuRouter router = createRouterWithAuth("https://keycloak.example.com/realms/wanaku");

        reconciler.reconcile(router, null);

        Condition auth = condition(router, "AuthConfigured");
        assertThat(auth.getStatus()).isEqualTo("False");
        assertThat(auth.getReason()).isEqualTo("SecretNotFound");
        assertThat(condition(router, OperatorUtil.READY_CONDITION).getReason()).isEqualTo("SecretNotFound");
        assertThat(deployment("wanaku-dev-oauth2-proxy")).isNull();
    }

    @Test
    void missingSecretKeyIsReported() {
        createAuthSecret(Map.of("client-secret", "s3cret"));
        WanakuRouter router = createRouterWithAuth("https://keycloak.example.com/realms/wanaku");

        reconciler.reconcile(router, null);

        assertThat(condition(router, "AuthConfigured").getReason()).isEqualTo("SecretKeyMissing");
    }

    @Test
    void invalidCookieSecretIsReported() {
        createAuthSecret(Map.of("client-secret", "s3cret", "cookie-secret", "too-short"));
        WanakuRouter router = createRouterWithAuth("https://keycloak.example.com/realms/wanaku");

        reconciler.reconcile(router, null);

        assertThat(condition(router, "AuthConfigured").getReason()).isEqualTo("InvalidCookieSecret");
    }

    @Test
    void validAuthDeploysProxyAndConfiguresServer() {
        Secret secret = createAuthSecret(
                Map.of("client-secret", "s3cret", "cookie-secret", "0123456789abcdef0123456789abcdef"));
        WanakuRouter router = createRouterWithAuth("https://keycloak.example.com/realms/wanaku");

        reconciler.reconcile(router, null);

        Condition auth = condition(router, "AuthConfigured");
        assertThat(auth.getStatus()).isEqualTo("True");
        assertThat(auth.getReason()).isEqualTo("Configured");
        Deployment proxy = deployment("wanaku-dev-oauth2-proxy");
        assertThat(proxy).isNotNull();
        assertThat(proxy.getSpec().getTemplate().getMetadata().getAnnotations())
                .containsEntry(
                        RouterResourceFactory.AUTH_SECRET_VERSION_ANNOTATION,
                        client.secrets()
                                .inNamespace(NAMESPACE)
                                .withName(secret.getMetadata().getName())
                                .get()
                                .getMetadata()
                                .getResourceVersion());
        assertThat(deployment("wanaku-dev-server")
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getEnv())
                .anyMatch(e -> "WANAKU_AUTH_ISSUER".equals(e.getName())
                        && "https://keycloak.example.com/realms/wanaku".equals(e.getValue()));
    }

    @Test
    void legacyIssuerFormatIsAcceptedWithWarning() {
        createAuthSecret(Map.of("client-secret", "s3cret", "cookie-secret", "MDEyMzQ1Njc4OWFiY2RlZg=="));
        WanakuRouter router = createRouterWithAuth("https://keycloak.example.com");

        reconciler.reconcile(router, null);

        Condition auth = condition(router, "AuthConfigured");
        assertThat(auth.getStatus()).isEqualTo("True");
        assertThat(auth.getReason()).isEqualTo("DeprecatedIssuerFormat");
        assertThat(auth.getMessage()).contains("https://keycloak.example.com/realms/wanaku");
        assertThat(client.v1().events().inNamespace(NAMESPACE).list().getItems())
                .anyMatch(e -> "DeprecatedIssuerFormat".equals(e.getReason()));
    }

    @Test
    void deprecatedAuthFieldsAreReportedInsteadOfIgnored() {
        WanakuRouter router = createRouter();
        WanakuRouterSpec.AuthSpec auth = new WanakuRouterSpec.AuthSpec();
        auth.setAuthServer("http://keycloak.example.com");
        auth.setAuthProxy("auto");
        router.getSpec().setAuth(auth);

        reconciler.reconcile(router, null);

        Condition authCondition = condition(router, "AuthConfigured");
        assertThat(authCondition.getStatus()).isEqualTo("False");
        assertThat(authCondition.getReason()).isEqualTo("DeprecatedFields");
        assertThat(condition(router, OperatorUtil.READY_CONDITION).getStatus()).isEqualTo("False");
    }

    private Secret createAuthSecret(Map<String, String> values) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName("wanaku-auth")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withStringData(values)
                .build();
        return client.secrets().inNamespace(NAMESPACE).resource(secret).create();
    }

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NAMESPACE).withName(name).get();
    }

    private static WanakuRouter createRouterWithAuth(String issuerUrl) {
        WanakuRouter router = createRouter();
        WanakuRouterSpec.AuthSpec auth = new WanakuRouterSpec.AuthSpec();
        auth.setEnabled(true);
        auth.setIssuerUrl(issuerUrl);
        auth.setSecretName("wanaku-auth");
        router.getSpec().setAuth(auth);
        return router;
    }

    private void markAvailable(String deploymentName) {
        Deployment deployment = client.apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .withName(deploymentName)
                .get();
        long generation = deployment.getMetadata().getGeneration() != null
                ? deployment.getMetadata().getGeneration()
                : 0L;
        deployment.setStatus(new DeploymentStatusBuilder()
                .withObservedGeneration(generation)
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

    private static Pod failingPod() {
        return new PodBuilder()
                .withNewMetadata()
                .withName("wanaku-dev-server-abc")
                .withNamespace(NAMESPACE)
                .withLabels(Map.of("app", "wanaku-dev-server", "component", "wanaku-server"))
                .endMetadata()
                .withNewStatus()
                .addNewContainerStatus()
                .withName("wanaku-server")
                .withNewState()
                .withNewWaiting()
                .withReason("ImagePullBackOff")
                .withMessage("image quay.io/wanaku/missing:latest not found")
                .endWaiting()
                .endState()
                .endContainerStatus()
                .endStatus()
                .build();
    }

    private static Condition condition(WanakuRouter router, String type) {
        List<Condition> conditions = router.getStatus().getConditions();
        Condition condition = OperatorUtil.findCondition(conditions, type);
        assertThat(condition).as("condition %s in %s", type, conditions).isNotNull();
        return condition;
    }

    private static WanakuRouter createRouter() {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("wanaku-dev")
                .withNamespace(NAMESPACE)
                .withUid("router-uid")
                .withGeneration(3L)
                .build());
        router.setSpec(new WanakuRouterSpec());
        // The mock API server serves Routes but, unlike OpenShift, does not assign hostnames
        WanakuTypes.ExposureSpec exposure = new WanakuTypes.ExposureSpec();
        exposure.setHost("wanaku.apps.example.com");
        router.getSpec().setExposure(exposure);
        return router;
    }
}
