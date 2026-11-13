package ai.wanaku.cli.main.commands.prompts;

import java.util.List;
import ai.wanaku.capabilities.sdk.api.types.PromptReference;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.PromptsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptsListTest {

    @Mock
    private PromptsService promptsService;

    private PromptsList command;

    @BeforeEach
    void setUp() {
        command = new PromptsList();
        command.promptsService = promptsService;
        command.host = "http://localhost:8080";
    }

    @Test
    @DisplayName("Should list prompts from backend")
    void shouldListPrompts() throws Exception {
        PromptReference prompt = new PromptReference();
        prompt.setName("test-prompt");
        prompt.setDescription("A test prompt");

        when(promptsService.list()).thenReturn(new WanakuResponse<>(List.of(prompt)));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);
        assertEquals("default", prompt.getNamespace());
        verify(printer).printTable(List.of(prompt), "name", "namespace", "description");
    }

    @Test
    @DisplayName("Should return OK and print info when response data is null")
    void shouldHandleNullResponseData() throws Exception {
        when(promptsService.list()).thenReturn(new WanakuResponse<>(null));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);
        verify(printer).printInfoMessage("No prompts found.");
    }

    @Test
    @DisplayName("Should return OK and print info when response data is empty")
    void shouldHandleEmptyResponseData() throws Exception {
        when(promptsService.list()).thenReturn(new WanakuResponse<>(List.of()));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);
        verify(printer).printInfoMessage("No prompts found.");
    }
}
