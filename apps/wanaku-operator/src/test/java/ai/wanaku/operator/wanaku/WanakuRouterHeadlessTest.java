package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import ai.wanaku.operator.util.RouterResourceFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuRouterHeadlessTest {
    private static final String NAMESPACE = "test";

    KubernetesClient client;

    private WanakuRouterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuRouterReconciler();
        reconciler.kubernetesClient = client;
    }

    @Test
    void standardServerAdvertisesTheAdminUi() {
        WanakuRouter router = createRouter(null, null);

        reconciler.reconcile(router, null);

        assertThat(serverImage()).isEqualTo("quay.io/wanaku/wanaku-server:latest");
        assertThat(router.getStatus().getEndpoints()).containsEntry("adminUi", "https://wanaku.example.com/admin/");
    }

    @Test
    void headlessServerUsesTheHeadlessImageAndDoesNotAdvertiseTheAdminUi() {
        WanakuRouter router = createRouter("headless", null);

        reconciler.reconcile(router, null);

        assertThat(serverImage()).isEqualTo(RouterResourceFactory.HEADLESS_SERVER_IMAGE);
        assertThat(router.getStatus().getEndpoints())
                .containsKeys("management", "mcp")
                .doesNotContainKey("adminUi");
    }

    @Test
    void anExplicitImageWinsOverTheVariantDefault() {
        WanakuRouter router = createRouter("headless", "registry.example.com/wanaku-server-headless:0.3.0");

        reconciler.reconcile(router, null);

        assertThat(serverImage()).isEqualTo("registry.example.com/wanaku-server-headless:0.3.0");
        assertThat(router.getStatus().getEndpoints()).doesNotContainKey("adminUi");
    }

    @Test
    void switchingTheVariantUpdatesTheServer() {
        WanakuRouter router = createRouter(null, null);
        reconciler.reconcile(router, null);

        router.getSpec().getServer().setVariant("headless");
        reconciler.reconcile(router, null);

        assertThat(serverImage()).isEqualTo(RouterResourceFactory.HEADLESS_SERVER_IMAGE);
    }

    private String serverImage() {
        Deployment server = client.apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .withName("wanaku-dev-server")
                .get();
        return server.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .getFirst()
                .getImage();
    }

    private static WanakuRouter createRouter(String variant, String image) {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("wanaku-dev")
                .withNamespace(NAMESPACE)
                .withUid("router-uid")
                .withGeneration(1L)
                .build());
        router.setSpec(new WanakuRouterSpec());
        WanakuRouterSpec.ServerSpec server = new WanakuRouterSpec.ServerSpec();
        server.setVariant(variant);
        server.setImage(image);
        router.getSpec().setServer(server);
        WanakuTypes.ExposureSpec exposure = new WanakuTypes.ExposureSpec();
        exposure.setType("Ingress");
        exposure.setHost("wanaku.example.com");
        router.getSpec().setExposure(exposure);
        return router;
    }
}
