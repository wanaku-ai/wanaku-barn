package ai.wanaku.backend.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds minimal service catalog and template packages for tests.
 */
public final class CatalogZips {

    private CatalogZips() {}

    /**
     * Builds a Base64-encoded package with one valid route and one rules file per system. The route logs the
     * description, so packages with different descriptions have different content.
     *
     * @param name the catalog name in {@code index.properties}
     * @param description the catalog description
     * @param systems the system identifiers
     * @return the Base64-encoded ZIP
     */
    public static String base64(String name, String description, String... systems) {
        return base64WithRoutes(name, description, system -> route(system, description), systems);
    }

    private static String route(String system, String message) {
        return """
                - route:
                    id: %s
                    from:
                      uri: "direct:%s"
                      steps:
                        - log: "%s"
                """
                .formatted(system, system, message);
    }

    /**
     * Builds a Base64-encoded package with the given Camel YAML DSL content for each system.
     *
     * @param name the catalog name in {@code index.properties}
     * @param description the catalog description
     * @param routes the route file content of a system
     * @param systems the system identifiers
     * @return the Base64-encoded ZIP
     */
    public static String base64WithRoutes(
            String name, String description, Function<String, String> routes, String... systems) {
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
                    zos.write(routes.apply(sys).getBytes(StandardCharsets.UTF_8));
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
