package ai.wanaku.operator.util;

import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.openshift.api.model.Route;
import ai.wanaku.operator.wanaku.WanakuRouter;
import ai.wanaku.operator.wanaku.WanakuRouterSpec;
import ai.wanaku.operator.wanaku.WanakuTypes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouterResourceFactoryTest {

    @Test
    void backendDeploymentHasNoAuthEnvVars() {
        WanakuRouter router = createRouter(null);
        Deployment deployment = RouterResourceFactory.makeDesiredRouterBackendDeployment(router, null);

        assertNull(getEnvValue(deployment, "AUTH_SERVER"));
        assertNull(getEnvValue(deployment, "AUTH_PROXY"));
        assertNull(getEnvValue(deployment, "AUTH_REALM"));
    }

    @Test
    void customEnvVarsFromRouterSpec() {
        WanakuRouterSpec.RouterSpec routerSpec = new WanakuRouterSpec.RouterSpec();
        WanakuTypes.EnvVar customVar = new WanakuTypes.EnvVar();
        customVar.setName("MY_VAR");
        customVar.setValue("my_value");
        routerSpec.setEnv(List.of(customVar));

        WanakuRouter router = createRouter(routerSpec);
        Deployment deployment = RouterResourceFactory.makeDesiredRouterBackendDeployment(router, null);

        assertEquals("my_value", getEnvValue(deployment, "MY_VAR"));
    }

    // ── Praxis Ingress factory tests ─────────────────────────────────────────

    @Test
    void praxisIngressTargetsPort8081() {
        WanakuRouter router = createRouter(null);
        Ingress ingress = RouterResourceFactory.makePraxisIngress(router, "wanaku.example.com");
        assertEquals(
                8081,
                ingress.getSpec()
                        .getRules()
                        .getFirst()
                        .getHttp()
                        .getPaths()
                        .getFirst()
                        .getBackend()
                        .getService()
                        .getPort()
                        .getNumber());
    }

    @Test
    void praxisIngressTargetsPraxisService() {
        WanakuRouter router = createRouter(null);
        Ingress ingress = RouterResourceFactory.makePraxisIngress(router, "wanaku.example.com");
        assertEquals(
                "praxis-test-router",
                ingress.getSpec()
                        .getRules()
                        .getFirst()
                        .getHttp()
                        .getPaths()
                        .getFirst()
                        .getBackend()
                        .getService()
                        .getName());
    }

    @Test
    void praxisIngressWithTlsSecretName() {
        WanakuTypes.TlsSpec tls = new WanakuTypes.TlsSpec();
        tls.setSecretName("wanaku-tls");
        WanakuRouter router = createRouterWithExposure(null, "nginx", null, tls);
        Ingress ingress = RouterResourceFactory.makePraxisIngress(router, "wanaku.example.com");
        assertNotNull(ingress.getSpec().getTls());
        assertEquals(1, ingress.getSpec().getTls().size());
        assertEquals("wanaku-tls", ingress.getSpec().getTls().getFirst().getSecretName());
    }

    @Test
    void praxisIngressWithIngressClassName() {
        WanakuRouter router = createRouterWithExposure(null, "nginx", null, null);
        Ingress ingress = RouterResourceFactory.makePraxisIngress(router, "wanaku.example.com");
        assertEquals("nginx", ingress.getSpec().getIngressClassName());
    }

    @Test
    void praxisIngressWithAnnotationsMergesThem() {
        Map<String, String> annotations = Map.of("cert-manager.io/cluster-issuer", "letsencrypt-prod");
        WanakuRouter router = createRouterWithExposure(null, null, annotations, null);
        Ingress ingress = RouterResourceFactory.makePraxisIngress(router, "wanaku.example.com");
        assertNotNull(ingress.getMetadata().getAnnotations());
        assertEquals("letsencrypt-prod", ingress.getMetadata().getAnnotations().get("cert-manager.io/cluster-issuer"));
    }

    // ── Praxis Route factory tests ───────────────────────────────────────────

    @Test
    void praxisRouteTargetsPraxisService() {
        WanakuRouter router = createRouter(null);
        Route route = RouterResourceFactory.makePraxisExternalRoute(router);
        assertEquals("praxis-test-router", route.getSpec().getTo().getName());
    }

    @Test
    void praxisRouteTargetsPort8081() {
        WanakuRouter router = createRouter(null);
        Route route = RouterResourceFactory.makePraxisExternalRoute(router);
        assertEquals("8081-tcp", route.getSpec().getPort().getTargetPort().getStrVal());
    }

    @Test
    void praxisRouteWithEdgeTls() {
        WanakuTypes.TlsSpec tls = new WanakuTypes.TlsSpec();
        tls.setTermination(WanakuTypes.TlsTermination.EDGE);
        WanakuRouter router = createRouterWithExposure(WanakuTypes.ExposureType.ROUTE, null, null, tls);
        Route route = RouterResourceFactory.makePraxisExternalRoute(router);
        assertNotNull(route.getSpec().getTls());
        assertEquals("edge", route.getSpec().getTls().getTermination());
    }

    // ── oauth2-proxy factory tests ────────────────────────────────────────────

    @Test
    void oauth2ProxyDeploymentHasMcpAndMgmtContainers() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com");

        List<String> names = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .map(c -> c.getName())
                .toList();
        assertEquals(List.of("oauth2-proxy-mcp", "oauth2-proxy-mgmt"), names);
    }

    @Test
    void oauth2ProxyMcpContainerTargetsPraxisMcpPort() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com");

        assertEquals(
                "http://praxis-test-router:8081",
                getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_UPSTREAMS"));
        assertEquals("0.0.0.0:4180", getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_HTTP_ADDRESS"));
    }

    @Test
    void oauth2ProxyMgmtContainerTargetsPraxisMgmtPort() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com");

        assertEquals(
                "http://praxis-test-router:9090",
                getContainerEnvValue(deployment, "oauth2-proxy-mgmt", "OAUTH2_PROXY_UPSTREAMS"));
        assertEquals(
                "0.0.0.0:4181", getContainerEnvValue(deployment, "oauth2-proxy-mgmt", "OAUTH2_PROXY_HTTP_ADDRESS"));
    }

    @Test
    void oauth2ProxyUsesIssuerAndSecretsFromSpec() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com");

        for (String containerName : List.of("oauth2-proxy-mcp", "oauth2-proxy-mgmt")) {
            assertEquals(
                    "https://keycloak.example.com/realms/wanaku",
                    getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_OIDC_ISSUER_URL"));
            assertEquals(
                    "wanaku-mcp-router", getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_CLIENT_ID"));
            assertEquals(
                    "https://wanaku.example.com/oauth2/callback",
                    getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_REDIRECT_URL"));

            EnvVar clientSecret = getContainerEnv(deployment, containerName, "OAUTH2_PROXY_CLIENT_SECRET");
            assertEquals(
                    "wanaku-auth", clientSecret.getValueFrom().getSecretKeyRef().getName());
            assertEquals(
                    "client-secret",
                    clientSecret.getValueFrom().getSecretKeyRef().getKey());

            EnvVar cookieSecret = getContainerEnv(deployment, containerName, "OAUTH2_PROXY_COOKIE_SECRET");
            assertEquals(
                    "wanaku-auth", cookieSecret.getValueFrom().getSecretKeyRef().getName());
            assertEquals(
                    "cookie-secret",
                    cookieSecret.getValueFrom().getSecretKeyRef().getKey());
        }
    }

    @Test
    void oauth2ProxySkipsOidcDiscoveryAndDerivesEndpoints() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com");

        for (String containerName : List.of("oauth2-proxy-mcp", "oauth2-proxy-mgmt")) {
            assertEquals("true", getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_SKIP_OIDC_DISCOVERY"));
            assertEquals(
                    "https://keycloak.example.com/realms/wanaku/protocol/openid-connect/auth",
                    getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_LOGIN_URL"));
            assertEquals(
                    "https://keycloak.example.com/realms/wanaku/protocol/openid-connect/token",
                    getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_REDEEM_URL"));
            assertEquals(
                    "https://keycloak.example.com/realms/wanaku/protocol/openid-connect/certs",
                    getContainerEnvValue(deployment, containerName, "OAUTH2_PROXY_OIDC_JWKS_URL"));
        }
    }

    @Test
    void oauth2ProxyCustomEnvOverridesDefault() {
        WanakuRouter router = createRouterWithAuth();
        WanakuTypes.EnvVar override = new WanakuTypes.EnvVar();
        override.setName("OAUTH2_PROXY_EMAIL_DOMAINS");
        override.setValue("example.com");
        router.getSpec().getAuth().setEnv(List.of(override));

        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com");
        assertEquals("example.com", getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_EMAIL_DOMAINS"));
    }

    @Test
    void oauth2ProxyServiceExposesBothPorts() {
        WanakuRouter router = createRouterWithAuth();
        io.fabric8.kubernetes.api.model.Service service = RouterResourceFactory.makeOauth2ProxyService(router);

        assertEquals("oauth2-proxy-test-router", service.getMetadata().getName());
        List<Integer> ports =
                service.getSpec().getPorts().stream().map(p -> p.getPort()).toList();
        assertEquals(List.of(4180, 4181), ports);
    }

    @Test
    void praxisIngressTargetsOauth2ProxyWhenAuthEnabled() {
        WanakuRouter router = createRouterWithAuth();
        Ingress ingress = RouterResourceFactory.makePraxisIngress(router, "wanaku.example.com");

        var backendService = ingress.getSpec()
                .getRules()
                .getFirst()
                .getHttp()
                .getPaths()
                .getFirst()
                .getBackend()
                .getService();
        assertEquals("oauth2-proxy-test-router", backendService.getName());
        assertEquals(4180, backendService.getPort().getNumber());
    }

    @Test
    void praxisRouteTargetsOauth2ProxyWhenAuthEnabled() {
        WanakuRouter router = createRouterWithAuth();
        Route route = RouterResourceFactory.makePraxisExternalRoute(router);

        assertEquals("oauth2-proxy-test-router", route.getSpec().getTo().getName());
        assertEquals("4180-tcp", route.getSpec().getPort().getTargetPort().getStrVal());
    }

    // ── Annotation env var tests ──────────────────────────────────────────────

    @Test
    void annotationEnvVarAddsNewVar() {
        WanakuRouter router = createRouter(null, Map.of("env.wanaku.ai/MY_ANNOTATION_VAR", "injected"));
        Deployment deployment = RouterResourceFactory.makeDesiredRouterBackendDeployment(router, null);

        assertEquals("injected", getEnvValue(deployment, "MY_ANNOTATION_VAR"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static WanakuRouter createRouterWithExposure(
            WanakuTypes.ExposureType type,
            String ingressClassName,
            Map<String, String> annotations,
            WanakuTypes.TlsSpec tls) {
        WanakuTypes.ExposureSpec exposureSpec = new WanakuTypes.ExposureSpec();
        exposureSpec.setType(type);
        exposureSpec.setHost("wanaku.example.com");
        exposureSpec.setIngressClassName(ingressClassName);
        exposureSpec.setAnnotations(annotations);
        exposureSpec.setTls(tls);
        WanakuRouter router = createRouter(null);
        router.getSpec().setExposure(exposureSpec);
        return router;
    }

    private static WanakuRouter createRouter(WanakuRouterSpec.RouterSpec routerSpec) {
        return createRouter(routerSpec, null);
    }

    private static WanakuRouter createRouterWithAuth() {
        WanakuRouter router = createRouter(null);
        WanakuRouterSpec.AuthSpec authSpec = new WanakuRouterSpec.AuthSpec();
        authSpec.setEnabled(true);
        authSpec.setIssuerUrl("https://keycloak.example.com/realms/wanaku");
        authSpec.setSecretName("wanaku-auth");
        router.getSpec().setAuth(authSpec);
        return router;
    }

    private static EnvVar getContainerEnv(Deployment deployment, String containerName, String envName) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .filter(c -> c.getName().equals(containerName))
                .findFirst()
                .flatMap(c -> c.getEnv().stream()
                        .filter(e -> e.getName().equals(envName))
                        .findFirst())
                .orElse(null);
    }

    private static String getContainerEnvValue(Deployment deployment, String containerName, String envName) {
        EnvVar envVar = getContainerEnv(deployment, containerName, envName);
        return envVar != null ? envVar.getValue() : null;
    }

    private static WanakuRouter createRouter(WanakuRouterSpec.RouterSpec routerSpec, Map<String, String> annotations) {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("test-router")
                .withNamespace("default")
                .withUid("test-uid-1234")
                .withAnnotations(annotations)
                .build());
        WanakuRouterSpec spec = new WanakuRouterSpec();
        spec.setRouter(routerSpec);
        router.setSpec(spec);
        return router;
    }

    private static String getEnvValue(Deployment deployment, String name) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .filter(c -> c.getName().equals("wanaku-mcp-router"))
                .findFirst()
                .flatMap(c -> c.getEnv().stream()
                        .filter(e -> e.getName().equals(name))
                        .findFirst())
                .map(EnvVar::getValue)
                .orElse(null);
    }
}
