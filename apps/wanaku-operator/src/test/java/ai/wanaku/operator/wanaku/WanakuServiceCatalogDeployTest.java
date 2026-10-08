package ai.wanaku.operator.wanaku;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.core.services.api.ServiceCatalogService;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanakuServiceCatalogDeployTest {

    private static WebApplicationException status(int code) {
        return new WebApplicationException(Response.status(code).build());
    }

    @Test
    void removedCatalogIsRestoredBeforeTheDeployIsRepeated() {
        ServiceCatalogService service = mock(ServiceCatalogService.class);
        DataStore catalog = new DataStore(null, "weather", "data");
        when(service.deploy(catalog)).thenThrow(status(409)).thenReturn(new WanakuResponse<>(catalog));

        WanakuServiceCatalogReconciler.deployOrRestore(service, "weather", catalog);

        var order = inOrder(service);
        order.verify(service).deploy(catalog);
        order.verify(service).restore("weather");
        order.verify(service).deploy(catalog);
    }

    @Test
    void otherFailuresAreNotRetried() {
        ServiceCatalogService service = mock(ServiceCatalogService.class);
        DataStore catalog = new DataStore(null, "weather", "data");
        WebApplicationException failure = status(500);
        when(service.deploy(catalog)).thenThrow(failure);

        assertSame(
                failure,
                assertThrows(
                        WebApplicationException.class,
                        () -> WanakuServiceCatalogReconciler.deployOrRestore(service, "weather", catalog)));
        verify(service, never()).restore("weather");
    }

    @Test
    void conflictWithoutARemovedCatalogReportsTheDeployFailure() {
        ServiceCatalogService service = mock(ServiceCatalogService.class);
        DataStore catalog = new DataStore(null, "weather", "data");
        WebApplicationException conflict = status(409);
        when(service.deploy(catalog)).thenThrow(conflict);
        when(service.restore("weather")).thenThrow(status(404));

        assertSame(
                conflict,
                assertThrows(
                        WebApplicationException.class,
                        () -> WanakuServiceCatalogReconciler.deployOrRestore(service, "weather", catalog)));
    }
}
