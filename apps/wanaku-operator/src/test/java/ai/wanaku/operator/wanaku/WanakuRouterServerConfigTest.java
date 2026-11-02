package ai.wanaku.operator.wanaku;

import java.util.Map;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Replaceable;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class WanakuRouterServerConfigTest {
    private static final String NAMESPACE = "test";
    private static final String CUSTOM_CONFIG = "governance:\n  default:\n    no_match: allow\n";

    KubernetesClient client;

    private WanakuRouterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WanakuRouterReconciler();
        reconciler.kubernetesClient = client;
    }

    @Test
    void aCustomConfigIsMountedInTheServerWithoutBarn() {
        createUserConfigMap("my-config", "wanaku.yaml", CUSTOM_CONFIG);
        WanakuRouter router = createRouter(false, "my-config", null);

        reconciler.reconcile(router, null);

        assertThat(managedConfig()).isEqualTo(CUSTOM_CONFIG);
        assertThat(serverDeployment().getSpec().getTemplate().getSpec().getVolumes())
                .anyMatch(v -> "server-config".equals(v.getName()));
    }

    @Test
    void aCustomConfigGetsTheBarnPluginWhenBarnIsEnabled() {
        createUserConfigMap("my-config", "custom.yaml", CUSTOM_CONFIG);
        WanakuRouter router = createRouter(true, "my-config", "custom.yaml");

        reconciler.reconcile(router, null);

        assertThat(managedConfig())
                .contains("no_match: allow")
                .contains("wanaku-barn-api")
                .contains("http://barn-wanaku-dev:8080");
    }

    @Test
    void changingTheCustomConfigRollsTheServer() {
        createUserConfigMap("my-config", "wanaku.yaml", CUSTOM_CONFIG);
        WanakuRouter router = createRouter(false, "my-config", null);
        reconciler.reconcile(router, null);
        String firstHash = configHash(serverDeployment());

        createUserConfigMap("my-config", "wanaku.yaml", "governance:\n  default:\n    mode: audit\n");
        reconciler.reconcile(router, null);

        assertThat(managedConfig()).contains("mode: audit");
        assertThat(configHash(serverDeployment())).isNotEqualTo(firstHash);
    }

    @Test
    void removingTheReferenceRemovesTheManagedConfig() {
        createUserConfigMap("my-config", "wanaku.yaml", CUSTOM_CONFIG);
        WanakuRouter router = createRouter(false, "my-config", null);
        reconciler.reconcile(router, null);

        router.getSpec().getServer().setConfigMapRef(null);
        reconciler.reconcile(router, null);

        assertThat(client.configMaps()
                        .inNamespace(NAMESPACE)
                        .withName("wanaku-dev-server-config")
                        .get())
                .isNull();
        assertThat(serverDeployment().getSpec().getTemplate().getSpec().getVolumes())
                .noneMatch(v -> "server-config".equals(v.getName()));
    }

    @Test
    void aMissingConfigMapIsReported() {
        WanakuRouter router = createRouter(false, "my-config", null);

        reconciler.reconcile(router, null);

        assertThat(reconciledCondition(router).getReason()).isEqualTo("InvalidServerConfig");
        assertThat(reconciledCondition(router).getMessage()).contains("ConfigMap my-config does not exist");
        assertThat(serverDeployment()).isNull();
    }

    @Test
    void aMissingKeyIsReported() {
        createUserConfigMap("my-config", "other.yaml", CUSTOM_CONFIG);
        WanakuRouter router = createRouter(false, "my-config", null);

        reconciler.reconcile(router, null);

        assertThat(reconciledCondition(router).getMessage()).contains("ConfigMap my-config has no key wanaku.yaml");
    }

    @Test
    void invalidYamlIsReported() {
        createUserConfigMap("my-config", "wanaku.yaml", "governance: [");
        WanakuRouter router = createRouter(false, "my-config", null);

        reconciler.reconcile(router, null);

        assertThat(reconciledCondition(router).getMessage())
                .contains("The wanaku.yaml key of ConfigMap my-config is not a valid wanaku.yaml");
    }

    @Test
    void theManagedConfigMapCannotBeReferenced() {
        WanakuRouter router = createRouter(false, "wanaku-dev-server-config", null);

        reconciler.reconcile(router, null);

        assertThat(reconciledCondition(router).getMessage()).contains("the operator owns that ConfigMap");
    }

    private void createUserConfigMap(String name, String key, String content) {
        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withData(Map.of(key, content))
                .build();
        client.configMaps().inNamespace(NAMESPACE).resource(configMap).createOr(Replaceable::update);
    }

    private String managedConfig() {
        return client.configMaps()
                .inNamespace(NAMESPACE)
                .withName("wanaku-dev-server-config")
                .get()
                .getData()
                .get("wanaku.yaml");
    }

    private Deployment serverDeployment() {
        return client.apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .withName("wanaku-dev-server")
                .get();
    }

    private static String configHash(Deployment deployment) {
        return deployment
                .getSpec()
                .getTemplate()
                .getMetadata()
                .getAnnotations()
                .get(RouterResourceFactory.SERVER_CONFIG_HASH_ANNOTATION);
    }

    private static Condition reconciledCondition(WanakuRouter router) {
        return OperatorUtil.findCondition(router.getStatus().getConditions(), "Reconciled");
    }

    private static WanakuRouter createRouter(boolean barnEnabled, String configMapName, String key) {
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
        WanakuTypes.ConfigMapKeyRef ref = new WanakuTypes.ConfigMapKeyRef();
        ref.setName(configMapName);
        ref.setKey(key);
        WanakuRouterSpec.ServerSpec server = new WanakuRouterSpec.ServerSpec();
        server.setConfigMapRef(ref);
        router.getSpec().setServer(server);
        return router;
    }
}
