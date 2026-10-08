package ai.wanaku.cli.main.commands.service;

import java.lang.reflect.Field;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the version history commands are registered and parse their options.
 */
class ServiceVersionsTest {

    @Test
    void versionsCommandsAreRegistered() {
        CommandLine service = new CommandLine(new Service());
        CommandLine versions = service.getSubcommands().get("versions");

        assertTrue(versions.getSubcommands().keySet().containsAll(java.util.Set.of("list", "download", "restore")));
    }

    @Test
    void restoreParsesItsOptions() throws Exception {
        ServiceVersionsRestore restore = new ServiceVersionsRestore();
        new CommandLine(restore)
                .parseArgs("--name", "weather", "--version", "2", "--expected-version", "5", "--template");

        assertEquals("weather", field(restore, "name"));
        assertEquals(2L, field(restore, "version"));
        assertEquals(5L, field(restore, "expectedVersion"));
        assertEquals(true, field(restore, "template"));
    }

    @Test
    void downloadDefaultsToACatalogWithoutOutputFile() throws Exception {
        ServiceVersionsDownload download = new ServiceVersionsDownload();
        new CommandLine(download).parseArgs("--name", "weather", "--version", "1");

        assertEquals(false, field(download, "template"));
        assertNull(field(download, "output"));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
