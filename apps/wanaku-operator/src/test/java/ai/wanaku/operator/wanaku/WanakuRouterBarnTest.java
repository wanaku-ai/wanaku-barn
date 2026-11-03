package ai.wanaku.operator.wanaku;

import java.util.List;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuRouterBarnTest {
    private static final String NAMESPACE = "test";

    KubernetesClient client;

    private WanakuRouterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuRouterReconciler();
        reconciler.kubernetesClient = client;
    }

    @Test
    void enablingBarnDeploysItAndWiresTheServerPlugin() {
        WanakuRouter router = createRouter(true);

        reconciler.reconcile(router, null);

        assertThat(deployment("wanaku-dev-barn")).isNotNull();
        assertThat(client.services()
                        .inNamespace(NAMESPACE)
                        .withName("barn-wanaku-dev")
                        .get())
                .isNotNull();
        assertThat(pvc("wanaku-dev-barn-data")).isNotNull();
        assertThat(client.configMaps()
                        .inNamespace(NAMESPACE)
                        .withName("wanaku-dev-server-config")
                        .get()
                        .getData()
                        .get("wanaku.yaml"))
                .contains("target: http://barn-wanaku-dev:8080");
        assertThat(router.getStatus().getBarnUrl()).isEqualTo("http://barn-wanaku-dev:8080");
        Condition barn = OperatorUtil.findCondition(router.getStatus().getConditions(), "BarnAvailable");
        assertThat(barn.getStatus()).isEqualTo("False");
        assertThat(barn.getReason()).isEqualTo("Progressing");
    }

    @Test
    void anOwnedLegacyClaimKeepsItsData() {
        WanakuRouter router = createRouter(true);
        PersistentVolumeClaim legacy =
                RouterResourceFactory.makeBarnVolumePVC(router, RouterResourceFactory.BARN_VOLUME_CLAIM);
        client.persistentVolumeClaims().inNamespace(NAMESPACE).resource(legacy).create();

        reconciler.reconcile(router, null);

        assertThat(pvc("wanaku-dev-barn-data")).isNull();
        assertThat(claimOf(deployment("wanaku-dev-barn"))).isEqualTo(RouterResourceFactory.BARN_VOLUME_CLAIM);
    }

    @Test
    void theServerClaimIsNamedPerRouter() {
        WanakuRouter router = createRouter(false);

        reconciler.reconcile(router, null);

        assertThat(pvc(RouterResourceFactory.SERVER_VOLUME_CLAIM)).isNull();
        assertThat(pvc("wanaku-dev-server-data")).isNotNull();
        assertThat(claimOf(deployment("wanaku-dev-server"), "server-volume")).isEqualTo("wanaku-dev-server-data");
    }

    @Test
    void anOwnedLegacyServerClaimKeepsItsData() {
        WanakuRouter router = createRouter(false);
        PersistentVolumeClaim legacy =
                RouterResourceFactory.makeServerVolumePVC(router, RouterResourceFactory.SERVER_VOLUME_CLAIM);
        client.persistentVolumeClaims().inNamespace(NAMESPACE).resource(legacy).create();

        reconciler.reconcile(router, null);

        assertThat(pvc("wanaku-dev-server-data")).isNull();
        assertThat(claimOf(deployment("wanaku-dev-server"), "server-volume"))
                .isEqualTo(RouterResourceFactory.SERVER_VOLUME_CLAIM);
    }

    @Test
    void aLegacyServerClaimOfAnotherRouterIsNotShared() {
        WanakuRouter other = createRouter(false);
        other.getMetadata().setName("other");
        other.getMetadata().setUid("other-uid");
        PersistentVolumeClaim legacy =
                RouterResourceFactory.makeServerVolumePVC(other, RouterResourceFactory.SERVER_VOLUME_CLAIM);
        client.persistentVolumeClaims().inNamespace(NAMESPACE).resource(legacy).create();

        reconciler.reconcile(createRouter(false), null);

        assertThat(claimOf(deployment("wanaku-dev-server"), "server-volume")).isEqualTo("wanaku-dev-server-data");
        assertThat(pvc(RouterResourceFactory.SERVER_VOLUME_CLAIM).getMetadata().getOwnerReferences())
                .extracting("uid")
                .containsExactly("other-uid");
    }

    @Test
    void anExistingClaimIsUsedAsIs() {
        WanakuRouter router = createRouter(true);
        WanakuRouterSpec.StorageSpec storage = new WanakuRouterSpec.StorageSpec();
        storage.setExistingClaim("my-barn-data");
        router.getSpec().getBarn().setStorage(storage);

        reconciler.reconcile(router, null);

        assertThat(pvc("my-barn-data")).isNull();
        assertThat(pvc("wanaku-dev-barn-data")).isNull();
        assertThat(claimOf(deployment("wanaku-dev-barn"))).isEqualTo("my-barn-data");
    }

    @Test
    void disablingBarnRemovesItsWorkloadButKeepsTheData() {
        WanakuRouter router = createRouter(true);
        reconciler.reconcile(router, null);

        router.getSpec().getBarn().setEnabled(false);
        reconciler.reconcile(router, null);

        assertThat(deployment("wanaku-dev-barn")).isNull();
        assertThat(client.services()
                        .inNamespace(NAMESPACE)
                        .withName("barn-wanaku-dev")
                        .get())
                .isNull();
        assertThat(client.configMaps()
                        .inNamespace(NAMESPACE)
                        .withName("wanaku-dev-server-config")
                        .get())
                .isNull();
        assertThat(pvc("wanaku-dev-barn-data")).isNotNull();
        assertThat(router.getStatus().getBarnUrl()).isNull();
        assertThat(OperatorUtil.findCondition(router.getStatus().getConditions(), "BarnAvailable"))
                .isNull();
        assertThat(deployment("wanaku-dev-server")
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getVolumes())
                .noneMatch(v -> "server-config".equals(v.getName()));
    }

    @Test
    void catalogsWaitUntilBarnIsAvailable() {
        WanakuRouter router = createRouter(true);
        reconciler.reconcile(router, null);
        client.resource(router).create();
        client.resource(router).updateStatus();
        WanakuServiceCatalogReconciler catalogs = new WanakuServiceCatalogReconciler();
        catalogs.kubernetesClient = client;
        WanakuServiceCatalog catalog = new WanakuServiceCatalog();
        catalog.setMetadata(new ObjectMetaBuilder()
                .withName("catalogs")
                .withNamespace(NAMESPACE)
                .withGeneration(1L)
                .build());
        WanakuServiceCatalogSpec spec = new WanakuServiceCatalogSpec();
        spec.setRouterRef("wanaku-dev");
        spec.setCatalogs(List.of());
        catalog.setSpec(spec);

        UpdateControl<WanakuServiceCatalog> control = catalogs.reconcile(catalog, null);

        Condition ready = OperatorUtil.findCondition(catalog.getStatus().getConditions(), "Ready");
        assertThat(ready.getStatus()).isEqualTo("False");
        assertThat(ready.getReason()).isEqualTo("BarnUnavailable");
        assertThat(control.getScheduleDelay()).isPresent();
    }

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NAMESPACE).withName(name).get();
    }

    private PersistentVolumeClaim pvc(String name) {
        return client.persistentVolumeClaims()
                .inNamespace(NAMESPACE)
                .withName(name)
                .get();
    }

    private static String claimOf(Deployment deployment) {
        return claimOf(deployment, "barn-volume");
    }

    private static String claimOf(Deployment deployment, String volume) {
        return deployment.getSpec().getTemplate().getSpec().getVolumes().stream()
                .filter(v -> volume.equals(v.getName()))
                .findFirst()
                .orElseThrow()
                .getPersistentVolumeClaim()
                .getClaimName();
    }

    private static WanakuRouter createRouter(boolean barnEnabled) {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("wanaku-dev")
                .withNamespace(NAMESPACE)
                .withUid("router-uid")
                .withGeneration(1L)
                .build());
        router.setSpec(new WanakuRouterSpec());
        WanakuRouterSpec.BarnSpec barn = new WanakuRouterSpec.BarnSpec();
        barn.setEnabled(barnEnabled);
        router.getSpec().setBarn(barn);
        WanakuTypes.ExposureSpec exposure = new WanakuTypes.ExposureSpec();
        exposure.setType("None");
        router.getSpec().setExposure(exposure);
        return router;
    }
}
