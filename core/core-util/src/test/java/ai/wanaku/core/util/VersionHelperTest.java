package ai.wanaku.core.util;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class VersionHelperTest {

    @Test
    public void testVersion() {
        Assertions.assertNotNull(VersionHelper.VERSION);
        Assertions.assertNotEquals("", VersionHelper.VERSION);
        Assertions.assertNotEquals("${project.version}", VersionHelper.VERSION);
    }

    @Test
    public void dependencyRootVersionCannotShadowWanakuVersion(@TempDir Path dependency) throws Exception {
        Files.writeString(dependency.resolve("version.txt"), "9.9.9-SNAPSHOT");
        URL ownClasses =
                VersionHelper.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {dependency.toUri().toURL(), ownClasses}, null)) {
            Class<?> helper = Class.forName(VersionHelper.class.getName(), true, loader);
            Assertions.assertEquals(
                    VersionHelper.VERSION, helper.getField("VERSION").get(null));
            Assertions.assertNotEquals(
                    "9.9.9-SNAPSHOT", helper.getField("VERSION").get(null));
        }
    }
}
