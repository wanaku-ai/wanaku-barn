package ai.wanaku.cli.main.commands;

import java.lang.reflect.Field;
import java.nio.file.Path;
import ai.wanaku.cli.main.commands.backup.Backup;
import ai.wanaku.cli.main.commands.backup.BackupExport;
import ai.wanaku.cli.main.commands.backup.BackupImport;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupCommandTest {

    @Test
    void backupCommandsParseTheirOptions() throws Exception {
        assertTrue(new CommandLine(new Backup())
                .getSubcommands()
                .keySet()
                .containsAll(java.util.Set.of("export", "import")));

        BackupExport export = new BackupExport();
        new CommandLine(export).parseArgs("--output", "barn.json", "--no-audit");
        assertEquals(Path.of("barn.json"), field(export, "output"));
        assertEquals(true, field(export, "noAudit"));

        BackupImport importCommand = new BackupImport();
        new CommandLine(importCommand).parseArgs("--input", "barn.json", "--replace");
        assertEquals(Path.of("barn.json"), field(importCommand, "input"));
        assertEquals(true, field(importCommand, "replace"));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
