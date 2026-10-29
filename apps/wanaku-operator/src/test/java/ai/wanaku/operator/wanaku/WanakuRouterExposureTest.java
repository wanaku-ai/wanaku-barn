package ai.wanaku.operator.wanaku;

import java.util.Map;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteStatusBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;
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
    void routesWithoutExposureHostUseTheirGeneratedHosts() {
        WanakuRouter router = createRouter(null);
        // OpenShift assigns a host on creation; the mock API server does not, so pre-create the Routes
        routes().resource(RouterResourceFactory.makeServerRoute(router, "wanaku.apps.example.com", false))
                .create();
        routes().resource(RouterResourceFactory.makeServerRoute(router, "wanaku-mcp.apps.example.com", true))
                .create();

        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(exposure.host).isEqualTo("wanaku.apps.example.com");
        // The MCP Route keeps its own host: copying the management host needs routes/custom-host
        Route mcp = route("wanaku-dev-mcp");
        assertThat(mcp.getSpec().getHost()).isEqualTo("wanaku-mcp.apps.example.com");
        // With its own host, the MCP Route also serves the OAuth discovery and namespace endpoints
        assertThat(mcp.getSpec().getPath()).isEqualTo("/");
        assertThat(exposure.hosts)
                .containsOnly(
                        Map.entry(ServerListener.MANAGEMENT, "wanaku.apps.example.com"),
                        Map.entry(ServerListener.MCP, "wanaku-mcp.apps.example.com"));
    }

    @Test
    void exposureHostIsSharedByBothRoutes() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.example.com"));
        WanakuRouterStatus status = new WanakuRouterStatus();
        status.setSseEndpoint("https://wanaku.example.com/mcp/sse");
        router.setStatus(status);

        reconciler.reconcile(router, null);

        assertThat(route("wanaku-dev").getSpec().getHost()).isEqualTo("wanaku.example.com");
        assertThat(route("wanaku-dev-mcp").getSpec().getHost()).isEqualTo("wanaku.example.com");
        assertThat(route("wanaku-dev").getSpec().getPath()).isEqualTo("/");
        assertThat(route("wanaku-dev-mcp").getSpec().getPath()).isEqualTo("/default/mcp");
        assertThat(router.getStatus().getStreamableEndpoint()).isEqualTo("https://wanaku.example.com/default/mcp");
        assertThat(router.getStatus().getEndpoints()).containsEntry("mcp", "https://wanaku.example.com/default/mcp");
        assertThat(router.getStatus().getSseEndpoint()).isNull();
    }

    @Test
    void unchangedRoutesAreNotUpdated() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        String version = route("wanaku-dev").getMetadata().getResourceVersion();

        reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(route("wanaku-dev").getMetadata().getResourceVersion()).isEqualTo(version);
    }

    @Test
    void perListenerExposureCreatesOneRoutePerListenerAndPrunesSharedRoutes() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        assertThat(route("wanaku-dev")).isNotNull();

        router.getSpec().setExposure(listenerExposure("mgmt.example.com", "mcp.example.com", null, "a2a.example.com"));
        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(route("wanaku-dev")).isNull();
        assertThat(routes().list().getItems())
                .extracting(r -> r.getMetadata().getName())
                .containsExactlyInAnyOrder("wanaku-dev-management", "wanaku-dev-mcp", "wanaku-dev-a2a");
        Route a2a = route("wanaku-dev-a2a");
        assertThat(a2a.getSpec().getHost()).isEqualTo("a2a.example.com");
        assertThat(a2a.getSpec().getPath()).isEqualTo("/");
        assertThat(a2a.getSpec().getPort().getTargetPort().getIntVal()).isEqualTo(8084);
        assertThat(route("wanaku-dev-mcp").getSpec().getPath()).isEqualTo("/");
        assertThat(exposure.hosts)
                .containsEntry(ServerListener.MANAGEMENT, "mgmt.example.com")
                .containsEntry(ServerListener.A2A, "a2a.example.com")
                .doesNotContainKey(ServerListener.INFERENCE);
    }

    @Test
    void disablingAListenerRemovesItsRoute() {
        WanakuRouter router =
                createRouter(listenerExposure("mgmt.example.com", "mcp.example.com", "inf.example.com", null));
        reconciler.reconcileExternalAccess(router, NAMESPACE);
        assertThat(route("wanaku-dev-inference")).isNotNull();

        router.getSpec().getExposure().getListeners().getInference().setEnabled(false);
        reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(route("wanaku-dev-inference")).isNull();
        assertThat(route("wanaku-dev-management")).isNotNull();
    }

    @Test
    void exposureTypeNoneRemovesEveryRoute() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcileExternalAccess(router, NAMESPACE);

        router.getSpec().getExposure().setType(RouterResourceFactory.EXPOSURE_NONE);
        WanakuRouterReconciler.Exposure exposure = reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(routes().list().getItems()).isEmpty();
        assertThat(exposure.hosts).isEmpty();
        assertThat(exposure.host).isEqualTo("server-wanaku-dev");
    }

    @Test
    void ingressTypeCreatesOneIngressPerListenerAndRemovesRoutes() {
        WanakuRouter router = createRouter(listenerExposure("mgmt.example.com", "mcp.example.com", null, null));
        reconciler.reconcileExternalAccess(router, NAMESPACE);

        router.getSpec().getExposure().setType(RouterResourceFactory.EXPOSURE_INGRESS);
        reconciler.reconcileExternalAccess(router, NAMESPACE);

        assertThat(routes().list().getItems()).isEmpty();
        Ingress mcp = client.network()
                .v1()
                .ingresses()
                .inNamespace(NAMESPACE)
                .withName("wanaku-dev-mcp")
                .get();
        assertThat(mcp.getSpec().getRules().getFirst().getHost()).isEqualTo("mcp.example.com");
        assertThat(mcp.getSpec()
                        .getRules()
                        .getFirst()
                        .getHttp()
                        .getPaths()
                        .getFirst()
                        .getBackend()
                        .getService())
                .satisfies(service -> {
                    assertThat(service.getName()).isEqualTo("server-wanaku-dev");
                    assertThat(service.getPort().getNumber()).isEqualTo(8081);
                });
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

        Route a2a = route("wanaku-dev-a2a");
        assertThat(a2a.getSpec().getTo().getName()).isEqualTo("oauth2-proxy-wanaku-dev");
        assertThat(a2a.getSpec().getPort().getTargetPort().getIntVal()).isEqualTo(4184);
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
                        "WANAKU_A2A_PUBLIC_URL".equals(e.getName()) && "https://a2a.example.com/".equals(e.getValue()));
        assertThat(router.getStatus().getEndpoints())
                .containsEntry("management", "https://mgmt.example.com/")
                .containsEntry("mcp", "https://mcp.example.com/default/mcp")
                .containsEntry("a2a", "https://a2a.example.com/");
        assertThat(router.getStatus().getStreamableEndpoint()).isEqualTo("https://mcp.example.com/default/mcp");
        assertThat(router.getStatus().getSseEndpoint()).isNull();
    }

    @Test
    void disablingAuthRemovesTheProxyAndRoutesToTheServer() {
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
        Route mcp = route("wanaku-dev-mcp");
        assertThat(mcp.getSpec().getTo().getName()).isEqualTo("server-wanaku-dev");
        assertThat(mcp.getSpec().getPort().getTargetPort().getIntVal()).isEqualTo(ServerListener.MCP.serverPort());
        assertThat(condition(router, "AuthConfigured").getReason()).isEqualTo("AuthDisabled");
        assertThat(condition(router, "AuthProxyAvailable")).isNull();
    }

    @Test
    void routesWaitForAdmissionAndReportRejections() {
        WanakuRouter router = createRouter(sharedExposure("wanaku.apps.example.com"));
        reconciler.reconcile(router, null);
        assertThat(condition(router, "ExposureReady").getReason()).isEqualTo("Progressing");

        Route management = route("wanaku-dev");
        management.setStatus(new RouteStatusBuilder()
                .addNewIngress()
                .addNewCondition()
                .withType("Admitted")
                .withStatus("False")
                .withReason("HostAlreadyClaimed")
                .withMessage("route wanaku-dev already exposes wanaku.apps.example.com")
                .endCondition()
                .endIngress()
                .build());
        routes().resource(management).updateStatus();
        reconciler.reconcile(router, null);

        assertThat(condition(router, "ExposureReady").getStatus()).isEqualTo("False");
        assertThat(condition(router, "ExposureReady").getReason()).isEqualTo("RouteNotAdmitted");
        assertThat(condition(router, "ExposureReady").getMessage()).contains("HostAlreadyClaimed");

        WanakuRouterReadinessTest.admitRoutes(client);
        reconciler.reconcile(router, null);
        assertThat(condition(router, "ExposureReady").getStatus()).isEqualTo("True");
    }

    private io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<
                    Route, io.fabric8.openshift.api.model.RouteList, io.fabric8.kubernetes.client.dsl.Resource<Route>>
            routes() {
        return client.adapt(OpenShiftClient.class).routes().inNamespace(NAMESPACE);
    }

    private Route route(String name) {
        return routes().withName(name).get();
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
