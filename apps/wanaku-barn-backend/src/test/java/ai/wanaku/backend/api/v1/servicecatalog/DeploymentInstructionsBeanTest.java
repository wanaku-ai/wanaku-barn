package ai.wanaku.backend.api.v1.servicecatalog;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DeploymentInstructions;
import ai.wanaku.core.services.api.ServiceCatalogIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentInstructionsBeanTest {

    @Mock
    ServiceCatalogBean serviceCatalogBean;

    @InjectMocks
    DeploymentInstructionsBean bean;

    private DataStore testCatalog;
    private ServiceCatalogIndex testIndex;

    @BeforeEach
    void setUp() throws Exception {
        Method loadTemplates = DeploymentInstructionsBean.class.getDeclaredMethod("loadTemplates");
        loadTemplates.setAccessible(true);
        loadTemplates.invoke(bean);

        testCatalog = new DataStore();
        testCatalog.setId("test-id");
        testCatalog.setName("test-catalog.zip");
        testCatalog.setData(createTestZipBase64("testcatalog", "A test catalog", "sys1"));

        testIndex = ServiceCatalogIndex.fromBase64(testCatalog.getData());
    }

    @Test
    void testLocalInstructions() {
        when(serviceCatalogBean.get("testcatalog")).thenReturn(testCatalog);
        when(serviceCatalogBean.parseIndex(testCatalog)).thenReturn(testIndex);

        DeploymentInstructions instructions = bean.generateInstructions("testcatalog", "local");
        assertNotNull(instructions);
        assertEquals(1, instructions.systems().size());

        String instruction = instructions.systems().get(0).instruction();
        assertTrue(instruction.contains("--client-id wanaku-service"), "Instructions should contain --client-id");
        assertTrue(instruction.contains("--fail-fast"), "Instructions should still contain --fail-fast");
    }

    @Test
    void testDockerCicInstructions() {
        when(serviceCatalogBean.get("testcatalog")).thenReturn(testCatalog);
        when(serviceCatalogBean.parseIndex(testCatalog)).thenReturn(testIndex);

        DeploymentInstructions instructions = bean.generateInstructions("testcatalog", "docker");
        assertNotNull(instructions);

        String instruction = instructions.systems().get(0).instruction();
        assertTrue(instruction.contains("CLIENT_ID=wanaku-service"), "Instructions should contain CLIENT_ID");
        assertTrue(instruction.contains("TOKEN_ENDPOINT"), "Instructions should contain TOKEN_ENDPOINT");
        assertTrue(instruction.contains("CLIENT_SECRET"), "Instructions should contain CLIENT_SECRET");

        boolean hasAuthPlaceholders = instructions.placeholders().stream()
                .anyMatch(p -> "client-secret".equals(p.key()) || "token-endpoint".equals(p.key()));
        assertTrue(hasAuthPlaceholders, "Should have auth placeholders");
    }

    @Test
    void testKubernetesInstructions() {
        when(serviceCatalogBean.get("testcatalog")).thenReturn(testCatalog);
        when(serviceCatalogBean.parseIndex(testCatalog)).thenReturn(testIndex);

        DeploymentInstructions instructions = bean.generateInstructions("testcatalog", "kubernetes");
        assertNotNull(instructions);

        String instruction = instructions.systems().get(0).instruction();
        assertTrue(instruction.contains("auth:"), "Instructions should contain auth section");
        assertTrue(instruction.contains("authServer"), "Instructions should contain authServer");

        boolean hasAuthPlaceholders = instructions.placeholders().stream()
                .anyMatch(p -> "auth-server-address".equals(p.key()) || "credentials-secret".equals(p.key()));
        assertTrue(hasAuthPlaceholders, "Should have auth placeholders");
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
                    String rulesPath = sys + "/" + sys + ".wanaku-rules.yaml";
                    props.setProperty("catalog.routes." + sys, routesPath);
                    props.setProperty("catalog.rules." + sys, rulesPath);

                    zos.putNextEntry(new ZipEntry(routesPath));
                    zos.write(("# Routes for " + sys).getBytes());
                    zos.closeEntry();

                    zos.putNextEntry(new ZipEntry(rulesPath));
                    zos.write(("# Rules for " + sys).getBytes());
                    zos.closeEntry();
                }

                zos.putNextEntry(new ZipEntry("index.properties"));
                ByteArrayOutputStream propsOut = new ByteArrayOutputStream();
                props.store(propsOut, null);
                zos.write(propsOut.toByteArray());
                zos.closeEntry();
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
