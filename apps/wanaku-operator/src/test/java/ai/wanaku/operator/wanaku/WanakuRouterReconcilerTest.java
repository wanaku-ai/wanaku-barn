package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class WanakuRouterReconcilerTest {

    @Mock
    KubernetesClient kubernetesClient;

    @InjectMocks
    WanakuRouterReconciler reconciler;

    // ── validateSpec tests (no kube API calls) ──────────────────────────────

    @Test
    void nullIngressPassesValidation() {
        WanakuRouter resource = createRouter(null);
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertTrue(result.valid);
        assertNull(result.errorMessage);
    }

    @Test
    void ingressTypeWithNullHostFailsValidation() {
        WanakuRouter resource = createRouterWithHost(null);
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("host"));
        assertTrue(result.errorMessage.contains("Ingress"));
    }

    @Test
    void ingressTypeWithBlankHostFailsValidation() {
        WanakuRouter resource = createRouterWithHost("   ");
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("host"));
    }

    @Test
    void ingressTypeWithEmptyHostFailsValidation() {
        WanakuRouter resource = createRouterWithHost("");
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertFalse(result.valid);
    }

    // ── auth validation tests ───────────────────────────────────────────────

    @Test
    void authDisabledPassesValidation() {
        WanakuRouter resource = createRouterWithAuth(false, null, null);
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertTrue(result.valid);
    }

    @Test
    void authEnabledWithIssuerAndSecretPassesValidation() {
        WanakuRouter resource = createRouterWithAuth(true, "https://keycloak.example.com/realms/wanaku", "wanaku-auth");
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertTrue(result.valid);
    }

    @Test
    void authEnabledWithoutIssuerUrlFailsValidation() {
        WanakuRouter resource = createRouterWithAuth(true, null, "wanaku-auth");
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("issuerUrl"));
    }

    @Test
    void authEnabledWithoutSecretNameFailsValidation() {
        WanakuRouter resource = createRouterWithAuth(true, "https://keycloak.example.com/realms/wanaku", "  ");
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("secretName"));
    }

    // ── exposure validation (the mocked cluster has no Route API) ─────────────

    @Test
    void unknownExposureTypeFailsValidation() {
        WanakuRouter resource = createRouterWithHost("wanaku.example.com");
        resource.getSpec().getExposure().setType("LoadBalancer");
        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("spec.exposure.type"));
    }

    @Test
    void routeExposureWithoutRouteApiFailsValidation() {
        WanakuRouter resource = createRouterWithHost("wanaku.example.com");
        resource.getSpec().getExposure().setType("Route");
        assertFalse(reconciler.validateSpec(resource).valid);
    }

    @Test
    void noneExposureNeedsNoHost() {
        WanakuRouter resource = createRouterWithHost(null);
        resource.getSpec().getExposure().setType("None");
        assertTrue(reconciler.validateSpec(resource).valid);
    }

    @Test
    void ingressListenerWithoutHostFailsValidation() {
        WanakuRouter resource = createRouter(new WanakuTypes.ExposureSpec());
        WanakuTypes.ListenersSpec listeners = new WanakuTypes.ListenersSpec();
        WanakuTypes.ListenerSpec a2a = new WanakuTypes.ListenerSpec();
        a2a.setEnabled(true);
        listeners.setA2a(a2a);
        resource.getSpec().getExposure().setListeners(listeners);

        WanakuRouterReconciler.ValidateSpecResult result = reconciler.validateSpec(resource);

        assertFalse(result.valid);
        // management is enabled by default and reported first
        assertTrue(result.errorMessage.contains("listeners.management.host"));
    }

    // ── cookie secret validation ─────────────────────────────────────────────

    @Test
    void cookieSecretAcceptsRawAndBase64EncodedAesKeys() {
        assertTrue(WanakuRouterReconciler.isValidCookieSecret("0123456789abcdef".getBytes()));
        assertTrue(WanakuRouterReconciler.isValidCookieSecret("MDEyMzQ1Njc4OWFiY2RlZg==".getBytes()));
        assertTrue(
                WanakuRouterReconciler.isValidCookieSecret("q83vEjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJA".getBytes()));
        assertFalse(WanakuRouterReconciler.isValidCookieSecret("too-short".getBytes()));
        assertFalse(WanakuRouterReconciler.isValidCookieSecret("not base64 at all!!".getBytes()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static WanakuRouter createRouter(WanakuTypes.ExposureSpec ingressSpec) {
        WanakuRouter router = new WanakuRouter();
        router.setMetadata(new ObjectMetaBuilder()
                .withName("test-router")
                .withNamespace("default")
                .withUid("test-uid")
                .withGeneration(1L)
                .build());
        WanakuRouterSpec spec = new WanakuRouterSpec();
        spec.setExposure(ingressSpec);
        router.setSpec(spec);
        return router;
    }

    private static WanakuRouter createRouterWithAuth(boolean enabled, String issuerUrl, String secretName) {
        WanakuRouter router = createRouter(null);
        WanakuRouterSpec.AuthSpec authSpec = new WanakuRouterSpec.AuthSpec();
        authSpec.setEnabled(enabled);
        authSpec.setIssuerUrl(issuerUrl);
        authSpec.setSecretName(secretName);
        router.getSpec().setAuth(authSpec);
        return router;
    }

    private static WanakuRouter createRouterWithHost(String host) {
        WanakuTypes.ExposureSpec ingressSpec = new WanakuTypes.ExposureSpec();
        ingressSpec.setHost(host);
        return createRouter(ingressSpec);
    }
}
