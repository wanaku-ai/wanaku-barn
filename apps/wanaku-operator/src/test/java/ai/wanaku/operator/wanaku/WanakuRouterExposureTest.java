package ai.wanaku.operator.wanaku;

import java.util.Map;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.ServerListener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuRouterExposureTest {
    private static final String NAMESPACE = "test";

    KubernetesClient client;

    private WanakuRouterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuRouterReconciler();
        reconciler.kubernetesClient = client;
    }

    @Test
    void sharedHostCreatesOneIngressForManagementAndMcp() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.example.com"));
        WanakuRouterStatus status = new WanakuRouterStatus();
        status.setSseEndpoint("https://wanaku.example.com/mcp/sse");
        router.setStatus(status);

        reconciler.reconcile(router, null);

        assertThat(ingresses().list().getItems())
                .extracting(i -> i.getMetadata().getName())
                .containsExactly("wanaku-dev");
        Ingress ingress = ingress("wanaku-dev");
        assertThat(ingress.getSpec().getRules().getFirst().getHost()).isEqualTo("wanaku.example.com");
        assertThat(ingress.getSpec().getRules().getFirst().getHttp().getPaths())
                .extracting(p -> p.getPath())
                .containsExactlyInAnyOrder("/", "/default/mcp");
        assertThat(router.getStatus().getStreamableEndpoint()).isEqualTo("https://wanaku.example.com/default/mcp");
        assertThat(router.getStatus().getEndpoints()).containsEntry("mcp", "https://wanaku.example.com/default/mcp");
        assertThat(router.getStatus().getSseEndpoint()).isNull();
        assertThat(deployment("wanaku-dev-server")
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getEnv())
                .anyMatch(e -> "WANAKU_MCP_PUBLIC_URL".equals(e.getName())
                        && "https://wanaku.example.com".equals(e.getValue()));
        assertThat(condition(router, "ExposureReady").getStatus()).isEqualTo("True");
        assertThat(condition(router, "ExposureReady").getReason()).isEqualTo("Exposed");
    }

    @Test
    void exposureIsEnabledByDefault() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.example.com"));

        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(exposure.host).isEqualTo("wanaku.example.com");
        assertThat(ingress("wanaku-dev")).isNotNull();
    }

    @Test
    void withoutExposureNothingIsCreated() {
        WanakuRouter router = createRouter(null);

        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(ingresses().list().getItems()).isEmpty();
        assertThat(exposure.hosts).isEmpty();
        assertThat(exposure.host).isEqualTo("server-wanaku-dev");
    }

    @Test
    void unchangedIngressesAreNotUpdated() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        String version = ingress("wanaku-dev").getMetadata().getResourceVersion();

        reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(ingress("wanaku-dev").getMetadata().getResourceVersion()).isEqualTo(version);
    }

    @Test
    void perListenerExposureCreatesOneIngressPerListenerAndPrunesTheSharedIngress() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        assertThat(ingress("wanaku-dev")).isNotNull();

        router.getSpec().setExposure(listenerExposure("mgmt.example.com", "mcp.example.com", null, "a2a.example.com"));
        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(ingress("wanaku-dev")).isNull();
        assertThat(ingresses().list().getItems())
                .extracting(i -> i.getMetadata().getName())
                .containsExactlyInAnyOrder("wanaku-dev-management", "wanaku-dev-mcp", "wanaku-dev-a2a");
        Ingress a2a = ingress("wanaku-dev-a2a");
        assertThat(a2a.getSpec().getRules().getFirst().getHost()).isEqualTo("a2a.example.com");
        assertThat(backend(a2a).getPort().getNumber()).isEqualTo(8084);
        assertThat(backend(ingress("wanaku-dev-mcp")).getName()).isEqualTo("server-wanaku-dev");
        assertThat(backend(ingress("wanaku-dev-mcp")).getPort().getNumber()).isEqualTo(8081);
        assertThat(ingress("wanaku-dev-mcp").getSpec().getRules().getFirst().getHost())
                .isEqualTo("mcp.example.com");
        assertThat(exposure.hosts)
                .containsEntry(ServerListener.MANAGEMENT, "mgmt.example.com")
                .containsEntry(ServerListener.A2A, "a2a.example.com")
                .doesNotContainKey(ServerListener.INFERENCE);
    }

    @Test
    void disablingAListenerRemovesItsIngress() {
        WanakuRouter router =
                createRouter(listenerExposure("mgmt.example.com", "mcp.example.com", "inf.example.com", null));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        assertThat(ingress("wanaku-dev-inference")).isNotNull();

        router.getSpec().getExposure().getListeners().getInference().setEnabled(false);
        reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(ingress("wanaku-dev-inference")).isNull();
        assertThat(ingress("wanaku-dev-management")).isNotNull();
    }

    @Test
    void disablingExposureRemovesEveryIngress() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        assertThat(ingresses().list().getItems()).hasSize(1);

        router.getSpec().getExposure().setEnabled(false);
        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(ingresses().list().getItems()).isEmpty();
        assertThat(exposure.hosts).isEmpty();
        assertThat(exposure.host).isEqualTo("server-wanaku-dev");
    }

    @Test
    void disabledPerListenerExposureCreatesNothing() {
        WanakuRouter router = createRouter(listenerExposure("mgmt.example.com", "mcp.example.com", null, null));
        router.getSpec().getExposure().setEnabled(false);

        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(ingresses().list().getItems()).isEmpty();
        assertThat(exposure.hosts).isEmpty();
    }

    @Test
    void protectedListenersRouteThroughTheirProxyAndA2aGetsAPublicUrl() {
        client.secrets()
                .inNamespace(NAMESPACE)
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName("wanaku-auth")
                        .endMetadata()
                        .withStringData(
                                Map.of("client-secret", "s3cret", "cookie-secret", "0123456789abcdef0123456789abcdef"))
                        .build())
                .create();
        WanakuRouter router =
                createRouter(listenerExposure("mgmt.example.com", "mcp.example.com", null, "a2a.example.com"));
        WanakuRouterSpec.AuthSpec auth = new WanakuRouterSpec.AuthSpec();
        auth.setEnabled(true);
        auth.setIssuerUrl("https://keycloak.example.com/realms/wanaku");
        auth.setSecretName("wanaku-auth");
        router.getSpec().setAuth(auth);

        reconciler.reconcile(router, null);

        var a2a = backend(ingress("wanaku-dev-a2a"));
        assertThat(a2a.getName()).isEqualTo("oauth2-proxy-wanaku-dev");
        assertThat(a2a.getPort().getNumber()).isEqualTo(4184);
        Deployment proxy = deployment("wanaku-dev-oauth2-proxy");
        assertThat(proxy.getSpec().getTemplate().getSpec().getContainers())
                .extracting(c -> c.getName())
                .contains("oauth2-proxy-a2a")
                .doesNotContain("oauth2-proxy-inference");
        assertThat(client.services()
                        .inNamespace(NAMESPACE)
                        .withName("oauth2-proxy-wanaku-dev")
                        .get()
                        .getSpec()
                        .getPorts())
                .extracting("port")
                .contains(4184);
        assertThat(deployment("wanaku-dev-server")
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getEnv())
                .anyMatch(e ->
                        "WANAKU_A2A_PUBLIC_URL".equals(e.getName()) && "https://a2a.example.com/".equals(e.getValue()))
                .anyMatch(e ->
                        "WANAKU_MCP_PUBLIC_URL".equals(e.getName()) && "https://mcp.example.com".equals(e.getValue()));
        assertThat(router.getStatus().getEndpoints())
                .containsEntry("management", "https://mgmt.example.com/")
                .containsEntry("mcp", "https://mcp.example.com/default/mcp")
                .containsEntry("a2a", "https://a2a.example.com/");
        assertThat(router.getStatus().getStreamableEndpoint()).isEqualTo("https://mcp.example.com/default/mcp");
        assertThat(router.getStatus().getSseEndpoint()).isNull();
    }

    @Test
    void disablingAuthRemovesTheProxyAndRetargetsIngressesToTheServer() {
        client.secrets()
                .inNamespace(NAMESPACE)
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName("wanaku-auth")
                        .endMetadata()
                        .withStringData(
                                Map.of("client-secret", "s3cret", "cookie-secret", "0123456789abcdef0123456789abcdef"))
                        .build())
                .create();
        WanakuRouter router = createRouter(listenerExposure("mgmt.example.com", "mcp.example.com", null, null));
        WanakuRouterSpec.AuthSpec auth = new WanakuRouterSpec.AuthSpec();
        auth.setEnabled(true);
        auth.setIssuerUrl("https://keycloak.example.com/realms/wanaku");
        auth.setSecretName("wanaku-auth");
        router.getSpec().setAuth(auth);
        reconciler.reconcile(router, null);
        assertThat(deployment("wanaku-dev-oauth2-proxy")).isNotNull();
        assertThat(condition(router, "AuthProxyAvailable")).isNotNull();

        auth.setEnabled(false);
        reconciler.reconcile(router, null);

        assertThat(deployment("wanaku-dev-oauth2-proxy")).isNull();
        assertThat(client.services()
                        .inNamespace(NAMESPACE)
                        .withName("oauth2-proxy-wanaku-dev")
                        .get())
                .isNull();
        var mcp = backend(ingress("wanaku-dev-mcp"));
        assertThat(mcp.getName()).isEqualTo("server-wanaku-dev");
        assertThat(mcp.getPort().getNumber()).isEqualTo(ServerListener.MCP.serverPort());
        assertThat(condition(router, "AuthConfigured").getReason()).isEqualTo("AuthDisabled");
        assertThat(condition(router, "AuthProxyAvailable")).isNull();
    }

    private io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<
                    Ingress,
                    io.fabric8.kubernetes.api.model.networking.v1.IngressList,
                    io.fabric8.kubernetes.client.dsl.Resource<Ingress>>
            ingresses() {
        return client.network().v1().ingresses().inNamespace(NAMESPACE);
    }

    private Ingress ingress(String name) {
        return ingresses().withName(name).get();
    }

    private static io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend backend(Ingress ingress) {
        return ingress.getSpec()
                .getRules()
                .getFirst()
                .getHttp()
                .getPaths()
                .getFirst()
                .getBackend()
                .getService();
    }

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NAMESPACE).withName(name).get();
    }

    private static io.fabric8.kubernetes.api.model.Condition condition(WanakuRouter router, String type) {
        return OperatorUtil.findCondition(router.getStatus().getConditions(), type);
    }

    private static WanakuTypes.ExposureSpec sharedExposure(String host) {
        WanakuTypes.ExposureSpec exposure = new WanakuTypes.ExposureSpec();
        exposure.setHost(host);
        return exposure;
    }

    private static WanakuTypes.ExposureSpec listenerExposure(
            String management, String mcp, String inference, String a2a) {
        WanakuTypes.ListenersSpec listeners = new WanakuTypes.ListenersSpec();
        listeners.setManagement(listener(management));
        listeners.setMcp(listener(mcp));
        listeners.setInference(listener(inference));
        listeners.setA2a(listener(a2a));
        WanakuTypes.ExposureSpec exposure = new WanakuTypes.ExposureSpec();
        exposure.setListeners(listeners);
        return exposure;
    }

    private static WanakuTypes.ListenerSpec listener(String host) {
        WanakuTypes.ListenerSpec listener = new WanakuTypes.ListenerSpec();
        listener.setEnabled(host != null);
        listener.setHost(host);
        return listener;
    }

    private static WanakuRouter createRouter(WanakuTypes.ExposureSpec exposure) {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("wanaku-dev")
                .withNamespace(NAMESPACE)
                .withUid("router-uid")
                .withGeneration(1L)
                .build());
        router.setSpec(new WanakuRouterSpec());
        router.getSpec().setExposure(exposure);
        return router;
    }
}
