package ai.wanaku.cli.main.commands.evaluators;

import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.support.WanakuPrinter;
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
public class EvaluatorsRevisionsActivateTest {

    @Mock
    private EvaluatorsService evaluatorsService;

    private EvaluatorsRevisionsActivate command;

    @BeforeEach
    void setUp() {
        command = new EvaluatorsRevisionsActivate();
        command.evaluatorsService = evaluatorsService;
        command.host = "http://localhost:8080";
    }

    @Test
    @DisplayName("Should activate a prior revision")
    void shouldActivateRevision() throws Exception {
        when(evaluatorsService.activateRevision(2, Map.of()))
                .thenReturn(new WanakuResponse<>(EvaluatorsTestSupport.revision(6)));

        command.id = 2;
        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(0, result);
        verify(printer).printSuccessMessage("Activated the configuration of revision 2 as revision 6");
    }

    @Test
    @DisplayName("Should send the expected revision when given")
    void shouldSendExpectedRevision() throws Exception {
        when(evaluatorsService.activateRevision(2, Map.of("expected_revision", 5L)))
                .thenReturn(new WanakuResponse<>(EvaluatorsTestSupport.revision(6)));

        command.id = 2;
        command.expectedRevision = 5L;
        Integer result = command.doCall(null, mock(WanakuPrinter.class));

        assertEquals(0, result);
        verify(evaluatorsService).activateRevision(2, Map.of("expected_revision", 5L));
    }
}
