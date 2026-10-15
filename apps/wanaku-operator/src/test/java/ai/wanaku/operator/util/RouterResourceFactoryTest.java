package ai.wanaku.operator.util;

import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
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
        Deployment deployment = RouterResourceFactory.makeDesiredBarnDeployment(router, null);

        assertNull(getEnvValue(deployment, "AUTH_SERVER"));
        assertNull(getEnvValue(deployment, "AUTH_PROXY"));
        assertNull(getEnvValue(deployment, "AUTH_REALM"));
    }

    @Test
    void barnDeploymentUsesMatchingServiceAndStorage() {
        WanakuRouter router = createRouter(null);
        Deployment deployment = RouterResourceFactory.makeDesiredBarnDeployment(router, null);
        var service = RouterResourceFactory.makeBarnInternalService(router);
        var pvc = RouterResourceFactory.makeBarnVolumePVC(router);
        var pod = deployment.getSpec().getTemplate();

        assertEquals("test-router-barn", deployment.getMetadata().getName());
        assertEquals("wanaku-barn", pod.getSpec().getContainers().getFirst().getName());
        assertEquals("barn-test-router", service.getMetadata().getName());
        assertEquals(
                8180, service.getSpec().getPorts().getFirst().getTargetPort().getIntVal());
        assertEquals(service.getSpec().getSelector(), pod.getMetadata().getLabels());
        assertEquals(
                service.getSpec().getSelector(),
                deployment.getSpec().getSelector().getMatchLabels());
        assertEquals("barn-volume-claim", pvc.getMetadata().getName());
        assertEquals(
                pvc.getMetadata().getName(),
                pod.getSpec().getVolumes().getFirst().getPersistentVolumeClaim().getClaimName());
    }

    @Test
    void customEnvVarsFromRouterSpec() {
        WanakuRouterSpec.RouterSpec routerSpec = new WanakuRouterSpec.RouterSpec();
        WanakuTypes.EnvVar customVar = new WanakuTypes.EnvVar();
        customVar.setName("MY_VAR");
        customVar.setValue("my_value");
        routerSpec.setEnv(List.of(customVar));

        WanakuRouter router = createRouter(routerSpec);
        Deployment deployment = RouterResourceFactory.makeDesiredBarnDeployment(router, null);

        assertEquals("my_value", getEnvValue(deployment, "MY_VAR"));
    }

    @Test
    void serverDeploymentUsesServerImageAndMatchingResources() {
        WanakuRouter router = createRouter(null);
        Deployment deployment = RouterResourceFactory.makeDesiredServerDeployment(router, null);
        var service = RouterResourceFactory.makeServerInternalService(router);
        var pvc = RouterResourceFactory.makeServerVolumePVC(router);
        var pod = deployment.getSpec().getTemplate();

        assertEquals("test-router-server", deployment.getMetadata().getName());
        assertEquals("wanaku-server", pod.getSpec().getContainers().getFirst().getName());
        assertEquals(
                "quay.io/wanaku/wanaku-server:latest",
                pod.getSpec().getContainers().getFirst().getImage());
        assertEquals("server-test-router", service.getMetadata().getName());
        assertEquals(
                OperatorUtil.getRouterBaseUrl("test-router"),
                getContainerEnvValue(deployment, "wanaku-server", "WANAKU_CLASSIC_URL"));
        assertEquals(
                deployment.getSpec().getSelector().getMatchLabels(),
                service.getSpec().getSelector());
        assertEquals(service.getSpec().getSelector(), pod.getMetadata().getLabels());
        assertEquals("server-volume-claim", pvc.getMetadata().getName());
        assertEquals(
                pvc.getMetadata().getName(),
                pod.getSpec().getVolumes().getFirst().getPersistentVolumeClaim().getClaimName());
    }

    @Test
    void serverListenerPortsMatchServiceAndHealthProbes() {
        WanakuRouter router = createRouter(null);
        Deployment deployment = RouterResourceFactory.makeDesiredServerDeployment(router, null);
        var container =
                deployment.getSpec().getTemplate().getSpec().getContainers().getFirst();
        var service = RouterResourceFactory.makeServerInternalService(router);
        Map<String, Integer> expected = Map.of("management", 8080, "mcp", 8081, "inference", 8083, "a2a", 8084);

        assertEquals(
                expected,
                container.getPorts().stream()
                        .collect(java.util.stream.Collectors.toMap(p -> p.getName(), p -> p.getContainerPort())));
        assertEquals(
                expected,
                service.getSpec().getPorts().stream()
                        .collect(java.util.stream.Collectors.toMap(p -> p.getName(), p -> p.getPort())));
        for (var port : service.getSpec().getPorts()) {
            assertEquals(port.getName(), port.getTargetPort().getStrVal());
        }
        assertEquals("0.0.0.0:8080", getContainerEnvValue(deployment, "wanaku-server", "WANAKU_MGMT_LISTEN"));
        assertEquals("0.0.0.0:8084", getContainerEnvValue(deployment, "wanaku-server", "WANAKU_A2A_LISTEN"));
        assertNull(getContainerEnvValue(deployment, "wanaku-server", "WANAKU_MCP_LISTEN"));
        assertEquals(
                "management",
                container.getLivenessProbe().getHttpGet().getPort().getStrVal());
        assertEquals(
                "management",
                container.getReadinessProbe().getHttpGet().getPort().getStrVal());
        assertEquals(
                "management", container.getStartupProbe().getHttpGet().getPort().getStrVal());
    }

    @Test
    void serverDeploymentUsesCustomServerConfiguration() {
        WanakuRouter router = createRouter(null);
        WanakuRouterSpec.ServerSpec serverSpec = new WanakuRouterSpec.ServerSpec();
        serverSpec.setImage("quay.io/wanaku/wanaku-server:custom");
        serverSpec.setImagePullPolicy("Always");
        WanakuTypes.EnvVar customVar = new WanakuTypes.EnvVar();
        customVar.setName("WANAKU_MGMT_LISTEN");
        customVar.setValue("0.0.0.0:9190");
        serverSpec.setEnv(List.of(customVar));
        router.getSpec().setServer(serverSpec);

        Deployment deployment = RouterResourceFactory.makeDesiredServerDeployment(router, null);

        assertEquals(
                serverSpec.getImage(),
                deployment
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getImage());
        assertEquals(
                "Always",
                deployment
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getImagePullPolicy());
        assertEquals("0.0.0.0:9190", getContainerEnvValue(deployment, "wanaku-server", "WANAKU_MGMT_LISTEN"));
    }

    // ── Server Ingress factory tests ─────────────────────────────────────────

    @Test
    void serverRoutesExposeManagementAndMcpOnOneHost() {
        WanakuRouter router = createRouter(null);
        var routes = List.of(
                RouterResourceFactory.makeServerRoute(router, "wanaku.example.com", false),
                RouterResourceFactory.makeServerRoute(router, "wanaku.example.com", true));
        assertEquals(
                Map.of("/", 8080, "/mcp", 8081),
                routes.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                r -> r.getSpec().getPath(),
                                r -> r.getSpec().getPort().getTargetPort().getIntVal())));
        for (var route : routes) {
            assertEquals("wanaku.example.com", route.getSpec().getHost());
            assertEquals("server-test-router", route.getSpec().getTo().getName());
            assertEquals("edge", route.getSpec().getTls().getTermination());
            assertEquals("Redirect", route.getSpec().getTls().getInsecureEdgeTerminationPolicy());
        }
    }

    @Test
    void authenticatedRoutesTargetProxyPorts() {
        WanakuRouter router = createRouterWithAuth();
        var management = RouterResourceFactory.makeServerRoute(router, null, false);
        var mcp = RouterResourceFactory.makeServerRoute(router, "wanaku.example.com", true);
        assertNull(management.getSpec().getHost());
        assertEquals(4181, management.getSpec().getPort().getTargetPort().getIntVal());
        assertEquals(4180, mcp.getSpec().getPort().getTargetPort().getIntVal());
        assertEquals("oauth2-proxy-test-router", management.getSpec().getTo().getName());
        assertEquals("oauth2-proxy-test-router", mcp.getSpec().getTo().getName());
    }

    @Test
    void serverIngressTargetsPort8081() {
        WanakuRouter router = createRouter(null);
        Ingress ingress = RouterResourceFactory.makeServerIngress(router, "wanaku.example.com");
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
        var management =
                ingress.getSpec().getRules().getFirst().getHttp().getPaths().getLast();
        assertEquals("/", management.getPath());
        assertEquals(8080, management.getBackend().getService().getPort().getNumber());
    }

    @Test
    void serverIngressTargetsServerService() {
        WanakuRouter router = createRouter(null);
        Ingress ingress = RouterResourceFactory.makeServerIngress(router, "wanaku.example.com");
        assertEquals(
                "server-test-router",
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
    void serverIngressWithTlsSecretName() {
        WanakuRouter router = createRouterWithExposure("nginx", null);
        Ingress ingress = RouterResourceFactory.makeServerIngress(router, "wanaku.example.com");
        assertNotNull(ingress.getSpec().getTls());
        assertEquals(1, ingress.getSpec().getTls().size());
    }

    @Test
    void serverIngressWithIngressClassName() {
        WanakuRouter router = createRouterWithExposure("nginx", null);
        Ingress ingress = RouterResourceFactory.makeServerIngress(router, "wanaku.example.com");
        assertEquals("nginx", ingress.getSpec().getIngressClassName());
    }

    @Test
    void serverIngressWithAnnotationsMergesThem() {
        Map<String, String> annotations = Map.of("cert-manager.io/cluster-issuer", "letsencrypt-prod");
        WanakuRouter router = createRouterWithExposure(null, annotations);
        Ingress ingress = RouterResourceFactory.makeServerIngress(router, "wanaku.example.com");
        assertNotNull(ingress.getMetadata().getAnnotations());
        assertEquals("letsencrypt-prod", ingress.getMetadata().getAnnotations().get("cert-manager.io/cluster-issuer"));
    }

    // ── oauth2-proxy factory tests ────────────────────────────────────────────

    @Test
    void oauth2ProxyDeploymentHasMcpAndMgmtContainers() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

        List<String> names = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .map(c -> c.getName())
                .toList();
        assertEquals(List.of("oauth2-proxy-mcp", "oauth2-proxy-mgmt"), names);
    }

    @Test
    void oauth2ProxyMcpContainerTargetsServerMcpPort() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

        assertEquals(
                "http://server-test-router:8081",
                getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_UPSTREAMS"));
        assertEquals("0.0.0.0:4180", getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_HTTP_ADDRESS"));
    }

    @Test
    void oauth2ProxyMgmtContainerTargetsServerMgmtPort() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

        assertEquals(
                "http://server-test-router:8080",
                getContainerEnvValue(deployment, "oauth2-proxy-mgmt", "OAUTH2_PROXY_UPSTREAMS"));
        assertEquals(
                "0.0.0.0:4181", getContainerEnvValue(deployment, "oauth2-proxy-mgmt", "OAUTH2_PROXY_HTTP_ADDRESS"));
    }

    @Test
    void oauth2ProxyUsesIssuerAndSecretsFromSpec() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

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
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

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

        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);
        assertEquals("example.com", getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_EMAIL_DOMAINS"));
    }

    @Test
    void oauth2ProxyKeepsFullIssuerUrlAndUsesInternalIssuerForBackChannel() {
        WanakuRouter router = createRouterWithAuth();
        router.getSpec().getAuth().setIssuerUrl("https://sso.example.com/realms/acme/");
        router.getSpec().getAuth().setInternalIssuerUrl("http://keycloak.sso.svc:8080/realms/acme");
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

        assertEquals(
                "https://sso.example.com/realms/acme",
                getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_OIDC_ISSUER_URL"));
        assertEquals(
                "https://sso.example.com/realms/acme/protocol/openid-connect/auth",
                getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_LOGIN_URL"));
        assertEquals(
                "http://keycloak.sso.svc:8080/realms/acme/protocol/openid-connect/token",
                getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_REDEEM_URL"));
        assertEquals(
                "http://keycloak.sso.svc:8080/realms/acme/protocol/openid-connect/certs",
                getContainerEnvValue(deployment, "oauth2-proxy-mgmt", "OAUTH2_PROXY_OIDC_JWKS_URL"));
    }

    @Test
    void oauth2ProxyTreatsMcpAndManagementApiPathsAsApiRoutes() {
        WanakuRouter router = createRouterWithAuth();
        Deployment deployment = RouterResourceFactory.makeOauth2ProxyDeployment(router, "wanaku.example.com", null);

        assertEquals(
                "^/$,^/mcp/?$,^/[^/]+/mcp/?$",
                getContainerEnvValue(deployment, "oauth2-proxy-mcp", "OAUTH2_PROXY_API_ROUTES"));
        assertEquals("^/api/.*", getContainerEnvValue(deployment, "oauth2-proxy-mgmt", "OAUTH2_PROXY_API_ROUTES"));
    }

    @Test
    void oauth2ProxySecretVersionIsAnnotatedOnPods() {
        Deployment deployment =
                RouterResourceFactory.makeOauth2ProxyDeployment(createRouterWithAuth(), "wanaku.example.com", "42");

        assertEquals(
                "42",
                deployment
                        .getSpec()
                        .getTemplate()
                        .getMetadata()
                        .getAnnotations()
                        .get(RouterResourceFactory.AUTH_SECRET_VERSION_ANNOTATION));
    }

    @Test
    void serverAdvertisesIssuerOnlyWhenAuthIsEnabled() {
        WanakuRouter router = createRouterWithAuth();
        router.getSpec().getAuth().setInternalIssuerUrl("http://keycloak:8080/realms/wanaku");
        Deployment withAuth = RouterResourceFactory.makeDesiredServerDeployment(router, null);
        Deployment withoutAuth = RouterResourceFactory.makeDesiredServerDeployment(createRouter(null), null);

        assertEquals(
                "https://keycloak.example.com/realms/wanaku",
                getContainerEnvValue(withAuth, "wanaku-server", "WANAKU_AUTH_ISSUER"));
        assertEquals(
                "http://keycloak:8080/realms/wanaku",
                getContainerEnvValue(withAuth, "wanaku-server", "WANAKU_AUTH_UPSTREAM_ISSUER"));
        assertNull(getContainerEnv(withoutAuth, "wanaku-server", "WANAKU_AUTH_ISSUER"));
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
    void serverIngressTargetsOauth2ProxyWhenAuthEnabled() {
        WanakuRouter router = createRouterWithAuth();
        Ingress ingress = RouterResourceFactory.makeServerIngress(router, "wanaku.example.com");

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

    // ── Annotation env var tests ──────────────────────────────────────────────

    @Test
    void annotationEnvVarAddsNewVar() {
        WanakuRouter router = createRouter(null, Map.of("env.wanaku.ai/MY_ANNOTATION_VAR", "injected"));
        Deployment deployment = RouterResourceFactory.makeDesiredBarnDeployment(router, null);

        assertEquals("injected", getEnvValue(deployment, "MY_ANNOTATION_VAR"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static WanakuRouter createRouterWithExposure(String ingressClassName, Map<String, String> annotations) {
        WanakuTypes.ExposureSpec exposureSpec = new WanakuTypes.ExposureSpec();
        exposureSpec.setHost("wanaku.example.com");
        exposureSpec.setIngressClassName(ingressClassName);
        exposureSpec.setAnnotations(annotations);
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
        // Deprecated Keycloak base URL format: the operator appends the /realms/wanaku path
        authSpec.setIssuerUrl("https://keycloak.example.com");
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
                .filter(c -> c.getName().equals("wanaku-barn"))
                .findFirst()
                .flatMap(c -> c.getEnv().stream()
                        .filter(e -> e.getName().equals(name))
                        .findFirst())
                .map(EnvVar::getValue)
                .orElse(null);
    }
}
