package ai.wanaku.cli.main.commands.evaluators;

import java.nio.file.Files;
import java.nio.file.Path;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorsService;
import ai.wanaku.core.services.api.EvaluatorsUpdateRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class EvaluatorsUpdateTest {

    @Mock
    private EvaluatorsService evaluatorsService;

    @TempDir
    Path tempDir;

    private EvaluatorsUpdate command;

    @BeforeEach
    void setUp() {
        command = new EvaluatorsUpdate();
        command.evaluatorsService = evaluatorsService;
        command.host = "http://localhost:8080";
    }

    @Test
    @DisplayName("Should send the evaluators of a wanaku.yaml file with the expected revision")
    void shouldUpdateFromYamlConfig() throws Exception {
        Path file = Files.writeString(
                tempDir.resolve("wanaku.yaml"),
                """
                llm_connections:
                  - name: local
                evaluators:
                  - name: safety-gate
                    trigger:
                      method: tools/call
                    engine:
                      type: passthrough
                    processor:
                      path: /wasm/safety-gate.wasm
                """);
        when(evaluatorsService.update(any())).thenReturn(new WanakuResponse<>(EvaluatorsTestSupport.revision(4)));

        command.file = file;
        command.expectedRevision = 3L;
        Integer result = command.doCall(null, mock(WanakuPrinter.class));

        assertEquals(0, result);
        ArgumentCaptor<EvaluatorsUpdateRequest> captor = ArgumentCaptor.forClass(EvaluatorsUpdateRequest.class);
        verify(evaluatorsService).update(captor.capture());
        EvaluatorsUpdateRequest request = captor.getValue();
        assertEquals(3L, request.getExpectedRevision());
        assertEquals(1, request.getEvaluators().size());
        assertEquals("safety-gate", request.getEvaluators().get(0).get("name"));
    }

    @Test
    @DisplayName("Should send a JSON list of evaluators without an expected revision")
    void shouldUpdateFromJsonList() throws Exception {
        Path file = Files.writeString(
                tempDir.resolve("evaluators.json"),
                """
                [{"name": "a", "trigger": {"method": "tools/list"}, "engine": {"type": "passthrough"},
                  "processor": {"path": "/wasm/a.wasm"}},
                 {"name": "b", "trigger": {"method": "tools/call"}, "engine": {"type": "passthrough"},
                  "processor": {"path": "/wasm/b.wasm"}}]
                """);
        when(evaluatorsService.update(any())).thenReturn(new WanakuResponse<>(EvaluatorsTestSupport.revision(1)));

        command.file = file;
        Integer result = command.doCall(null, mock(WanakuPrinter.class));

        assertEquals(0, result);
        ArgumentCaptor<EvaluatorsUpdateRequest> captor = ArgumentCaptor.forClass(EvaluatorsUpdateRequest.class);
        verify(evaluatorsService).update(captor.capture());
        assertNull(captor.getValue().getExpectedRevision());
        assertEquals(2, captor.getValue().getEvaluators().size());
    }

    @Test
    @DisplayName("Should reject a file without an evaluators list")
    void shouldRejectFileWithoutEvaluators() throws Exception {
        command.file = Files.writeString(tempDir.resolve("other.yaml"), "evalutors: []\n");

        Integer result = command.doCall(null, mock(WanakuPrinter.class));

        assertEquals(1, result);
        verifyNoInteractions(evaluatorsService);
    }

    @Test
    @DisplayName("Should fail when the file does not exist")
    void shouldFailOnMissingFile() throws Exception {
        command.file = tempDir.resolve("missing.yaml");

        Integer result = command.doCall(null, mock(WanakuPrinter.class));

        assertEquals(1, result);
        verifyNoInteractions(evaluatorsService);
    }
}
