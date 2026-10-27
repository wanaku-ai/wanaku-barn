package ai.wanaku.cli.main.commands.evaluators;

import java.util.List;
import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevision;
import ai.wanaku.core.services.api.EvaluatorsService;

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
public class EvaluatorsRevisionsActiveTest {

    @Mock
    private EvaluatorsService evaluatorsService;

    private EvaluatorsRevisionsActive command;

    @BeforeEach
    void setUp() {
        command = new EvaluatorsRevisionsActive();
        command.evaluatorsService = evaluatorsService;
        command.host = "http://localhost:8080";
    }

    @Test
    @DisplayName("Should print the active revision and a summary of its evaluators")
    void shouldPrintActiveRevision() throws Exception {
        EvaluatorRevision revision = EvaluatorsTestSupport.revision(5);
        when(evaluatorsService.activeRevision()).thenReturn(new WanakuResponse<>(revision));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(0, result);
        verify(printer).printAsMap(revision.getRevision(), EvaluatorRevisionPrinter.DETAIL_COLUMNS);
        Map<String, Object> row = Map.of(
                "name", "safety-gate",
                "method", "tools/call",
                "namespace", "production",
                "engine", "passthrough");
        verify(printer).printTable(List.of(row), "name", "method", "namespace", "engine");
    }
}
