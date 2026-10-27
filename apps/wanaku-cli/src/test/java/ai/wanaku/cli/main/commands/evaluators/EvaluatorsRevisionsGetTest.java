package ai.wanaku.cli.main.commands.evaluators;

import java.util.List;
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
public class EvaluatorsRevisionsGetTest {

    @Mock
    private EvaluatorsService evaluatorsService;

    private EvaluatorsRevisionsGet command;

    @BeforeEach
    void setUp() {
        command = new EvaluatorsRevisionsGet();
        command.evaluatorsService = evaluatorsService;
        command.host = "http://localhost:8080";
    }

    @Test
    @DisplayName("Should print the requested revision")
    void shouldPrintRevision() throws Exception {
        EvaluatorRevision revision = EvaluatorsTestSupport.revision(2);
        when(evaluatorsService.getRevision(2)).thenReturn(new WanakuResponse<>(revision));

        command.id = 2;
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(0, result);
        verify(printer).printAsMap(revision.getRevision(), EvaluatorRevisionPrinter.DETAIL_COLUMNS);
    }

    @Test
    @DisplayName("Should report a revision without evaluators")
    void shouldReportEmptyRevision() throws Exception {
        EvaluatorRevision revision = EvaluatorsTestSupport.revision(1);
        revision.setEvaluators(List.of());
        when(evaluatorsService.getRevision(1)).thenReturn(new WanakuResponse<>(revision));

        command.id = 1;
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(0, result);
        verify(printer).printInfoMessage("No evaluators configured.");
    }
}
