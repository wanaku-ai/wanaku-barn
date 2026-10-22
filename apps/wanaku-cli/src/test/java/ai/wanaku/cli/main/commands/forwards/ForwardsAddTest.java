package ai.wanaku.cli.main.commands.forwards;

import java.util.Map;
import ai.wanaku.cli.main.support.WanakuPrinter;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ForwardsAddTest {

    private final WanakuPrinter printer = mock(WanakuPrinter.class);

    @Test
    void printsDiscoveredCounts() {
        Map<String, Object> result = Map.of(
                "forward", Map.of("name", "everything", "available", true),
                "tools_discovered", 13,
                "resources_discovered", 7,
                "prompts_discovered", 2);

        ForwardsAdd.printSummary(printer, "everything", result);

        verify(printer)
                .printSuccessMessage(
                        "Successfully added forward 'everything': discovered 13 tools, 7 resources and 2 prompts");
    }

    @Test
    void warnsWhenForwardIsUnavailable() {
        Map<String, Object> result = Map.of(
                "forward",
                Map.of("name", "broken", "available", false, "statusMessage", "connection refused"),
                "tools_discovered",
                0);

        ForwardsAdd.printSummary(printer, "broken", result);

        verify(printer).printWarningMessage("Forward 'broken' was added, but it is unavailable: connection refused");
    }

    @Test
    void printsSuccessWhenServerReturnsNoData() {
        ForwardsAdd.printSummary(printer, "legacy", null);

        verify(printer).printSuccessMessage("Successfully added forward 'legacy'");
    }
}
