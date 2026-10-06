package ai.wanaku.backend.api.v1.servicecatalog;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.ServiceTemplateSummary;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.core.services.api.ServiceCatalogIndex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTemplateResourceTest {

    @Mock
    ServiceTemplateBean serviceTemplateBean;

    @InjectMocks
    ServiceTemplateResource resource;

    private DataStore template(String name, String description, String... systems) {
        DataStore ds = new DataStore();
        ds.setName(name + ".service.zip");
        ds.setData(createTestZipBase64(name, description, systems));
        return ds;
    }

    @Test
    void testListSortedAlphabetically() {
        DataStore zeta = template("zeta-template", "Zeta template", "zeta-sys");
        DataStore alpha = template("alpha-template", "Alpha template", "sys-b", "sys-a");

        when(serviceTemplateBean.list(null)).thenReturn(List.of(zeta, alpha));
        when(serviceTemplateBean.parseIndex(zeta)).thenReturn(ServiceCatalogIndex.fromBase64(zeta.getData()));
        when(serviceTemplateBean.parseIndex(alpha)).thenReturn(ServiceCatalogIndex.fromBase64(alpha.getData()));

        WanakuResponse<List<ServiceTemplateSummary>> response = resource.list(null);
        assertNotNull(response);
        assertEquals(2, response.data().size());
        assertEquals("alpha-template", response.data().get(0).getName());
        assertEquals("zeta-template", response.data().get(1).getName());

        assertEquals(List.of("sys-a", "sys-b"), response.data().get(0).getServices());
    }

    private String createTestZipBase64(String name, String description, String... systems) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                Properties props = new Properties();
                props.setProperty("catalog.name", name);
                props.setProperty("catalog.description", description);
                props.setProperty("catalog.services", String.join(",", systems));

                for (String sys : systems) {
                    String routesPath = sys + "/" + sys + ".camel.yaml";
                    props.setProperty("catalog.routes." + sys, routesPath);

                    zos.putNextEntry(new ZipEntry(routesPath));
                    zos.write((""
                                    + "- route:\n"
                                    + "    id: " + sys + "-route\n"
                                    + "    from:\n"
                                    + "      uri: direct:" + sys + "\n")
                            .getBytes());
                    zos.closeEntry();
                }

                zos.putNextEntry(new ZipEntry("index.properties"));
                props.store(zos, null);
                zos.closeEntry();
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
