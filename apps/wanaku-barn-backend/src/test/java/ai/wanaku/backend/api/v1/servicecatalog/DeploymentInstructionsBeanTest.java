package ai.wanaku.backend.api.v1.servicecatalog;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DeploymentInstructions;
import ai.wanaku.core.services.api.PlaceholderDefinition;
import ai.wanaku.core.services.api.ServiceCatalogIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(instruction.contains("--service-catalog testcatalog"), "Instructions should name the catalog");
        assertTrue(instruction.contains("--service-catalog-system sys1"), "Instructions should name the system");
        assertTrue(instruction.contains("--mcp-port <mcp-port>"), "Instructions should set the MCP port");
        assertTrue(instruction.contains("--register"), "The capability should register itself as a forward");
        assertTrue(instruction.contains("--wanaku-url <wanaku-url>"), "Instructions should name Wanaku Server");
        assertTrue(
                instruction.contains("--advertised-address http://localhost:<mcp-port>/mcp"),
                "Instructions should advertise the MCP address");
        assertClassicOptionsAbsent(instruction);
        assertEquals(
                List.of("registration-url", "mcp-port", "wanaku-url"),
                instructions.placeholders().stream()
                        .map(PlaceholderDefinition::key)
                        .toList());
    }

    @Test
    void testDockerCicInstructions() {
        when(serviceCatalogBean.get("testcatalog")).thenReturn(testCatalog);
        when(serviceCatalogBean.parseIndex(testCatalog)).thenReturn(testIndex);

        DeploymentInstructions instructions = bean.generateInstructions("testcatalog", "docker");
        assertNotNull(instructions);

        String instruction = instructions.systems().get(0).instruction();
        assertTrue(instruction.contains("-p <mcp-port>:9090"), "Instructions should map the MCP port");
        assertTrue(instruction.contains("SERVICE_CATALOG_SYSTEM=sys1"), "Instructions should name the system");
        assertTrue(instruction.contains("WANAKU_REGISTER=true"), "The capability should register itself");
        assertTrue(instruction.contains("WANAKU_URL=<wanaku-url>"), "Instructions should name Wanaku Server");
        assertTrue(
                instruction.contains("MCP_ADDRESS=http://localhost:<mcp-port>/mcp"),
                "Instructions should advertise the MCP address");
        assertClassicOptionsAbsent(instruction);
        assertFalse(instruction.contains("CLIENT_ID"), "The capability has no OIDC client options");

        assertEquals(
                List.of("registration-url", "mcp-port", "wanaku-url"),
                instructions.placeholders().stream()
                        .map(PlaceholderDefinition::key)
                        .toList());
    }

    private static void assertClassicOptionsAbsent(String instruction) {
        assertFalse(instruction.toLowerCase().contains("grpc"), "Classic gRPC options must not appear");
        assertFalse(instruction.contains("9190"), "Classic gRPC port must not appear");
        assertFalse(instruction.toLowerCase().contains("announce"), "Classic announce address must not appear");
        assertFalse(instruction.contains("client-id"), "The capability has no --client-id option");
    }

    @Test
    void testKubernetesModelIsUnsupported() {
        when(serviceCatalogBean.get("testcatalog")).thenReturn(testCatalog);
        when(serviceCatalogBean.parseIndex(testCatalog)).thenReturn(testIndex);

        // The kubernetes deployment model emitted WanakuCapability manifests, which the
        // operator no longer supports; until a replacement exists it is not offered
        assertThrows(IllegalArgumentException.class, () -> bean.generateInstructions("testcatalog", "kubernetes"));
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
