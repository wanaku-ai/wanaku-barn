package ai.wanaku.cli.main.commands.datastores;

import java.nio.file.Files;
import java.nio.file.Path;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.DataStoreRecord;
import ai.wanaku.core.services.api.DataStoresService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataStoresAddTest {

    @Mock
    private DataStoresService dataStoresService;

    @TempDir
    Path tempDir;

    private DataStoresAdd command;

    @BeforeEach
    void setUp() {
        command = new DataStoresAdd();
        command.dataStoresService = dataStoresService;
    }

    @Test
    @DisplayName("Should add a data store successfully")
    void shouldAddDataStore() throws Exception {
        Path file = Files.writeString(tempDir.resolve("test.txt"), "hello world");

        DataStoreRecord record = new DataStoreRecord();
        record.setName("test.txt");
        record.setId("ds-123");
        when(dataStoresService.add(any())).thenReturn(new WanakuResponse<>(record));

        new picocli.CommandLine(command).parseArgs("--read-from-file", file.toString());
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);
        verify(printer).printSuccessMessage("Successfully added data store 'test.txt' with ID: ds-123");
    }

    @Test
    @DisplayName("Should return error when response data is null")
    void shouldHandleNullResponseData() throws Exception {
        Path file = Files.writeString(tempDir.resolve("test.txt"), "hello world");

        when(dataStoresService.add(any())).thenReturn(new WanakuResponse<>(null));

        new picocli.CommandLine(command).parseArgs("--read-from-file", file.toString());
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_ERROR, result);
        verify(printer).printWarningMessage("Data store was not created.");
    }
}
