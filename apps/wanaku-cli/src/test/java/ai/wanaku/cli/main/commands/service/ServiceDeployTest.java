package ai.wanaku.cli.main.commands.service;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class ServiceDeployTest {

    @TempDir
    Path tempDir;

    @Mock
    private Terminal terminal;

    @Mock
    private WanakuPrinter printer;

    @Test
    void testDeployFailsWithMissingIndex() throws Exception {
        tempDir.resolve("empty").toFile().mkdirs();

        ServiceDeploy cmd = new ServiceDeploy();
        new CommandLine(cmd)
                .parseArgs("--path", tempDir.resolve("empty").toAbsolutePath().toString());

        Integer result = cmd.doCall(terminal, printer);
        assertEquals(1, result);
    }

    @Test
    void testDeployFailsWithMissingCatalogName() throws Exception {
        File rootDir = tempDir.resolve("noname").toFile();
        rootDir.mkdirs();

        Properties props = new Properties();
        props.setProperty("catalog.description", "test");
        props.setProperty("catalog.services", "sys1");

        File indexFile = new File(rootDir, "index.properties");
        try (FileWriter fw = new FileWriter(indexFile, StandardCharsets.UTF_8)) {
            props.store(fw, null);
        }

        ServiceDeploy cmd = new ServiceDeploy();
        new CommandLine(cmd).parseArgs("--path", rootDir.getAbsolutePath());

        Integer result = cmd.doCall(terminal, printer);
        assertEquals(1, result);
    }

    @Test
    void testDeployFailsWithMissingRouteFile() throws Exception {
        File rootDir = tempDir.resolve("missingroute").toFile();
        rootDir.mkdirs();

        Properties props = new Properties();
        props.setProperty("catalog.name", "test");
        props.setProperty("catalog.description", "test");
        props.setProperty("catalog.services", "sys1");
        props.setProperty("catalog.routes.sys1", "sys1/sys1.camel.yaml");

        File indexFile = new File(rootDir, "index.properties");
        try (FileWriter fw = new FileWriter(indexFile, StandardCharsets.UTF_8)) {
            props.store(fw, null);
        }

        ServiceDeploy cmd = new ServiceDeploy();
        new CommandLine(cmd).parseArgs("--path", rootDir.getAbsolutePath());

        Integer result = cmd.doCall(terminal, printer);
        assertEquals(1, result);
    }
}
