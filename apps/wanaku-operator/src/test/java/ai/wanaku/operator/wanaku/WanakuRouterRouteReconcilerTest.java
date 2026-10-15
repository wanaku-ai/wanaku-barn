package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.client.OpenShiftClient;
import ai.wanaku.operator.util.RouterResourceFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WanakuRouterRouteReconcilerTest {
    @Mock
    KubernetesClient kubernetesClient;

    @InjectMocks
    WanakuRouterReconciler reconciler;

    @Test
    void openshiftCreatesRoutesWithoutExposureUsingGeneratedHosts() {
        WanakuRouter resource = createRouter();
        var operations = mockOpenShiftRoutes();
        Resource<Route> read = mock(Resource.class);
        Resource<Route> write = mock(Resource.class);
        when(operations.withName(anyString())).thenReturn(read);
        when(operations.resource(any(Route.class))).thenReturn(write);
        when(write.createOr(any()))
                .thenReturn(RouterResourceFactory.makeServerRoute(resource, "wanaku.apps.example.com", false))
                .thenReturn(RouterResourceFactory.makeServerRoute(resource, "wanaku-mcp.apps.example.com", true));

        var hosts = reconciler.reconcileExternalAccess(resource, "default");
        assertEquals("wanaku.apps.example.com", hosts.management);
        assertEquals("wanaku-mcp.apps.example.com", hosts.mcp);

        // No Route sets spec.host, so the operator does not need the routes/custom-host permission
        ArgumentCaptor<Route> routes = ArgumentCaptor.forClass(Route.class);
        verify(operations, times(2)).resource(routes.capture());
        assertNull(routes.getAllValues().getFirst().getSpec().getHost());
        Route mcp = routes.getAllValues().getLast();
        assertNull(mcp.getSpec().getHost());
        // The MCP Route has its own host, so it also serves the OAuth discovery and namespace endpoints
        assertEquals("/", mcp.getSpec().getPath());
    }

    @Test
    void openshiftExposureHostIsSharedByBothRoutes() {
        WanakuRouter resource = createRouter();
        WanakuTypes.ExposureSpec exposure = new WanakuTypes.ExposureSpec();
        exposure.setHost("wanaku.example.com");
        resource.getSpec().setExposure(exposure);
        var operations = mockOpenShiftRoutes();
        Resource<Route> read = mock(Resource.class);
        Resource<Route> write = mock(Resource.class);
        when(operations.withName(anyString())).thenReturn(read);
        when(operations.resource(any(Route.class))).thenReturn(write);
        when(write.createOr(any()))
                .thenReturn(RouterResourceFactory.makeServerRoute(resource, "wanaku.example.com", false))
                .thenReturn(RouterResourceFactory.makeServerRoute(resource, "wanaku.example.com", true));

        var hosts = reconciler.reconcileExternalAccess(resource, "default");
        assertEquals("wanaku.example.com", hosts.management);
        assertEquals("wanaku.example.com", hosts.mcp);

        ArgumentCaptor<Route> routes = ArgumentCaptor.forClass(Route.class);
        verify(operations, times(2)).resource(routes.capture());
        routes.getAllValues()
                .forEach(route ->
                        assertEquals("wanaku.example.com", route.getSpec().getHost()));
        // On a shared host, the management Route takes "/", so the MCP Route takes only "/mcp"
        assertEquals("/", routes.getAllValues().getFirst().getSpec().getPath());
        assertEquals("/mcp", routes.getAllValues().getLast().getSpec().getPath());
    }

    @Test
    void openshiftKeepsExistingAssignedHostsWithoutUpdatingRoutes() {
        WanakuRouter resource = createRouter();
        var operations = mockOpenShiftRoutes();
        Resource<Route> management = mock(Resource.class);
        Resource<Route> mcp = mock(Resource.class);
        when(operations.withName("test-router")).thenReturn(management);
        when(operations.withName("test-router-mcp")).thenReturn(mcp);
        when(management.get()).thenReturn(assignedRoute(resource, "wanaku.apps.example.com", false));
        when(mcp.get()).thenReturn(assignedRoute(resource, "wanaku-mcp.apps.example.com", true));

        var hosts = reconciler.reconcileExternalAccess(resource, "default");
        assertEquals("wanaku.apps.example.com", hosts.management);
        assertEquals("wanaku-mcp.apps.example.com", hosts.mcp);
        verify(operations, never()).resource(any(Route.class));
    }

    @Test
    void openshiftWidensMcpRouteCreatedWithTheMcpPathOnly() {
        WanakuRouter resource = createRouter();
        var operations = mockOpenShiftRoutes();
        Resource<Route> management = mock(Resource.class);
        Resource<Route> mcp = mock(Resource.class);
        Resource<Route> write = mock(Resource.class);
        when(operations.withName("test-router")).thenReturn(management);
        when(operations.withName("test-router-mcp")).thenReturn(mcp);
        when(management.get()).thenReturn(assignedRoute(resource, "wanaku.apps.example.com", false));
        // An earlier operator version created the MCP Route with the path "/mcp" only
        Route legacy = assignedRoute(resource, "wanaku-mcp.apps.example.com", true);
        legacy.getSpec().setPath("/mcp");
        when(mcp.get()).thenReturn(legacy);
        when(operations.resource(any(Route.class))).thenReturn(write);
        when(write.createOr(any())).thenReturn(assignedRoute(resource, "wanaku-mcp.apps.example.com", true));

        reconciler.reconcileExternalAccess(resource, "default");

        ArgumentCaptor<Route> updated = ArgumentCaptor.forClass(Route.class);
        verify(operations).resource(updated.capture());
        assertEquals("/", updated.getValue().getSpec().getPath());
        assertEquals("wanaku-mcp.apps.example.com", updated.getValue().getSpec().getHost());
    }

    /** A Route as the operator creates it without a host, after OpenShift assigned one. */
    private static Route assignedRoute(WanakuRouter resource, String host, boolean mcp) {
        Route route = RouterResourceFactory.makeServerRoute(resource, null, mcp);
        route.getSpec().setHost(host);
        return route;
    }

    @Test
    void openshiftExposureWithoutHostPassesValidation() {
        when(kubernetesClient.supports(Route.class)).thenReturn(true);
        WanakuRouter resource = createRouter();
        resource.getSpec().setExposure(new WanakuTypes.ExposureSpec());
        assertTrue(reconciler.validateSpec(resource).valid);
    }

    @Test
    void kubernetesWithoutExposureKeepsInternalServiceName() {
        var hosts = reconciler.reconcileExternalAccess(createRouter(), "default");
        assertEquals("server-test-router", hosts.management);
        assertEquals("server-test-router", hosts.mcp);
        verify(kubernetesClient, never()).adapt(OpenShiftClient.class);
    }

    private NonNamespaceOperation<Route, RouteList, Resource<Route>> mockOpenShiftRoutes() {
        OpenShiftClient client = mock(OpenShiftClient.class);
        MixedOperation<Route, RouteList, Resource<Route>> routes = mock(MixedOperation.class);
        NonNamespaceOperation<Route, RouteList, Resource<Route>> operations = mock(NonNamespaceOperation.class);
        when(kubernetesClient.supports(Route.class)).thenReturn(true);
        when(kubernetesClient.adapt(OpenShiftClient.class)).thenReturn(client);
        when(client.routes()).thenReturn(routes);
        when(routes.inNamespace("default")).thenReturn(operations);
        return operations;
    }

    private static WanakuRouter createRouter() {
        WanakuRouter resource = new WanakuRouter();
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("test-router")
                .withNamespace("default")
                .withUid("test-uid")
                .build());
        resource.setSpec(new WanakuRouterSpec());
        return resource;
    }
}
