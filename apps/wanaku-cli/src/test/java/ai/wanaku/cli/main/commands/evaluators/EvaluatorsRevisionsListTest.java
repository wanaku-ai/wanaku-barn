package ai.wanaku.cli.main.commands.evaluators;

import java.util.List;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.EvaluatorRevisionMetadata;
import ai.wanaku.core.services.api.EvaluatorsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class EvaluatorsRevisionsListTest {

    @Mock
    private EvaluatorsService evaluatorsService;

    private EvaluatorsRevisionsList command;

    @BeforeEach
    void setUp() {
        command = new EvaluatorsRevisionsList();
        command.evaluatorsService = evaluatorsService;
        command.host = "http://localhost:8080";
    }

    @Test
    @DisplayName("Should print the revisions as a table")
    void shouldPrintRevisions() throws Exception {
        List<EvaluatorRevisionMetadata> revisions = List.of(
                EvaluatorsTestSupport.revision(2).getRevision(),
                EvaluatorsTestSupport.revision(1).getRevision());
        when(evaluatorsService.listRevisions()).thenReturn(new WanakuResponse<>(revisions));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(0, result);
        verify(printer).printTable(revisions, EvaluatorRevisionPrinter.METADATA_COLUMNS);
    }

    @Test
    @DisplayName("Should report when there are no revisions")
    void shouldReportEmptyHistory() throws Exception {
        when(evaluatorsService.listRevisions()).thenReturn(new WanakuResponse<>(List.of()));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(0, result);
        verify(printer).printInfoMessage("No evaluator revisions found.");
        verify(printer, never()).printTable(anyList(), any(String[].class));
    }
}
