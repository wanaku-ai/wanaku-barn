package ai.wanaku.cli.main.commands.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ServicePackageTest {

    @TempDir
    Path tempDir;

    @Mock
    Terminal terminal;

    @Mock
    WanakuPrinter printer;

    @Test
    void packagesACatalogWithoutRulesFiles() throws Exception {
        Path dir = tempDir.resolve("weather");
        Files.createDirectories(dir.resolve("weather"));
        Files.writeString(
                dir.resolve("index.properties"),
                "catalog.name=weather\ncatalog.services=weather\ncatalog.routes.weather=weather/weather.camel.yaml\n");
        Files.writeString(dir.resolve("weather/weather.camel.yaml"), "- route: {}\n");
        Path output = tempDir.resolve("weather.b64");

        ServicePackage cmd = new ServicePackage();
        new CommandLine(cmd).parseArgs("--path", dir.toString(), "--output", output.toString());

        assertEquals(BaseCommand.EXIT_OK, cmd.doCall(terminal, printer));
        assertTrue(Files.size(output) > 0);
    }
}
