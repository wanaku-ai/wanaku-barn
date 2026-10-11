package ai.wanaku.cli.main.commands.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jline.terminal.Terminal;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.ServiceCatalogService;
import ai.wanaku.core.services.api.ServiceTemplateService;
import ai.wanaku.core.services.api.ValidationIssue;
import ai.wanaku.core.services.api.ValidationResult;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceValidateTest {

    @TempDir
    Path tempDir;

    @Mock
    Terminal terminal;

    @Mock
    WanakuPrinter printer;

    @Mock
    ServiceCatalogService catalogService;

    @Mock
    ServiceTemplateService templateService;

    @Test
    void sendsThePackagedDirectoryAndSucceedsWhenValid() throws Exception {
        Path dir = catalogDir();
        when(catalogService.validate(any()))
                .thenReturn(new WanakuResponse<>(new ValidationResult(
                        ValidationResult.TYPE_CATALOG,
                        "weather",
                        true,
                        List.of(),
                        List.of(new ValidationIssue("index.properties#catalog.icon", "No icon declared")))));

        ServiceValidate cmd = command(dir);
        assertEquals(BaseCommand.EXIT_OK, cmd.doCall(terminal, printer));

        ArgumentCaptor<DataStore> sent = ArgumentCaptor.forClass(DataStore.class);
        verify(catalogService).validate(sent.capture());
        assertEquals(Set.of("index.properties", "weather/weather.camel.yaml"), zipEntries(sent.getValue()));
        verify(printer).printWarningMessage("index.properties#catalog.icon: No icon declared");
        verify(printer).printSuccessMessage("Service catalog 'weather' is valid (1 warning(s))");
        verifyNoInteractions(templateService);
    }

    @Test
    void reportsEveryErrorAndFailsWhenInvalid() throws Exception {
        Path dir = catalogDir();
        when(templateService.validate(any()))
                .thenReturn(new WanakuResponse<>(new ValidationResult(
                        ValidationResult.TYPE_TEMPLATE,
                        "weather",
                        false,
                        List.of(
                                new ValidationIssue("index.properties#catalog.description", "Missing"),
                                new ValidationIssue("weather/weather.camel.yaml", "Invalid YAML")),
                        List.of())));

        ServiceValidate cmd = command(dir);
        new CommandLine(cmd).parseArgs("--path", dir.toString(), "--template");
        assertEquals(BaseCommand.EXIT_ERROR, cmd.doCall(terminal, printer));

        verify(printer).printErrorMessage("index.properties#catalog.description: Missing");
        verify(printer).printErrorMessage("weather/weather.camel.yaml: Invalid YAML");
        verify(printer).printErrorMessage("Service template 'weather' is invalid: 2 error(s), 0 warning(s)");
        verifyNoInteractions(catalogService);
    }

    @Test
    void failsWhenThePathIsNotADirectory() throws Exception {
        ServiceValidate cmd = command(tempDir.resolve("missing"));
        assertEquals(BaseCommand.EXIT_ERROR, cmd.doCall(terminal, printer));
        verifyNoInteractions(catalogService, templateService);
    }

    @Test
    void isRegisteredUnderService() {
        assertTrue(new CommandLine(new Service()).getSubcommands().containsKey("validate"));
    }

    private ServiceValidate command(Path dir) {
        ServiceValidate cmd = new ServiceValidate();
        new CommandLine(cmd).parseArgs("--path", dir.toString());
        cmd.catalogService = catalogService;
        cmd.templateService = templateService;
        return cmd;
    }

    private Path catalogDir() throws Exception {
        Path dir = tempDir.resolve("weather");
        Files.createDirectories(dir.resolve("weather"));
        Files.writeString(dir.resolve("index.properties"), "catalog.name=weather\n");
        Files.writeString(dir.resolve("weather/weather.camel.yaml"), "- route: {}\n");
        return dir;
    }

    private static Set<String> zipEntries(DataStore dataStore) throws Exception {
        Set<String> names = new HashSet<>();
        byte[] zip = Base64.getDecoder().decode(dataStore.getData().getBytes(StandardCharsets.UTF_8));
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
