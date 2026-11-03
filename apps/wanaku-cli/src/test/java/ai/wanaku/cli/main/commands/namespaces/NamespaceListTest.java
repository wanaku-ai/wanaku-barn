package ai.wanaku.cli.main.commands.namespaces;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.Namespace;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.core.services.api.NamespacesService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamespaceListTest {

    @Mock
    private NamespacesService namespacesService;

    private NamespaceList command;

    @BeforeEach
    void setUp() {
        command = new NamespaceList();
        command.namespacesService = namespacesService;
        command.host = "http://localhost:8080";
        command.mcpUrl = "http://localhost:8081";
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should list namespaces from backend")
    void shouldListNamespaces() throws Exception {
        Namespace ns = new Namespace();
        ns.setName("my-ns");

        when(namespacesService.list(isNull())).thenReturn(new WanakuResponse<>(List.of(ns)));

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(printer).printTable(captor.capture(), eq("name"), eq("address"), eq("labels"));
        List<?> printed = captor.getValue();
        assertEquals(1, printed.size());
        assertEquals("http://localhost:8081/my-ns/mcp", ((NamespaceList.NamespaceRow) printed.get(0)).getAddress());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should build the address from the MCP URL option")
    void shouldBuildAddressFromMcpUrl() throws Exception {
        Namespace ns = new Namespace();
        ns.setName("my-ns");

        when(namespacesService.list(isNull())).thenReturn(new WanakuResponse<>(List.of(ns)));

        new picocli.CommandLine(command).parseArgs("--mcp-url", "https://mcp.example.com/");

        WanakuPrinter printer = mock(WanakuPrinter.class);
        command.doCall(null, printer);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(printer).printTable(captor.capture(), eq("name"), eq("address"), eq("labels"));
        assertEquals(
                "https://mcp.example.com/my-ns/mcp",
                ((NamespaceList.NamespaceRow) captor.getValue().get(0)).getAddress());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should filter namespaces by label expression")
    void shouldFilterByLabelExpression() throws Exception {
        Namespace ns = new Namespace();
        ns.setName("my-ns");
        ns.setLabels(Map.of("tier", "frontend"));

        when(namespacesService.list(eq("tier=frontend"))).thenReturn(new WanakuResponse<>(List.of(ns)));

        picocli.CommandLine cli = new picocli.CommandLine(command);
        cli.parseArgs("-e", "tier=frontend");

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(printer).printTable(captor.capture(), eq("name"), eq("address"), eq("labels"));
        List<?> printed = captor.getValue();
        assertEquals(1, printed.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should return empty list when label filter matches nothing")
    void shouldReturnEmptyWhenFilterMatchesNothing() throws Exception {
        when(namespacesService.list(eq("env=staging"))).thenReturn(new WanakuResponse<>(Collections.emptyList()));

        picocli.CommandLine cli = new picocli.CommandLine(command);
        cli.parseArgs("-e", "env=staging");

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(printer).printTable(captor.capture(), eq("name"), eq("address"), eq("labels"));
        List<?> printed = captor.getValue();
        assertTrue(printed.isEmpty());
    }

    @Test
    @DisplayName("Should list namespaces when label expression is blank")
    @SuppressWarnings("unchecked")
    void shouldListNamespacesWhenLabelExpressionBlank() throws Exception {
        Namespace ns = new Namespace();
        ns.setName("team");

        when(namespacesService.list("   ")).thenReturn(new WanakuResponse<>(List.of(ns)));

        command.labelExpression = "   ";

        WanakuPrinter printer = mock(WanakuPrinter.class);
        Integer result = command.doCall(null, printer);

        assertEquals(BaseCommand.EXIT_OK, result);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(printer).printTable(captor.capture(), eq("name"), eq("address"), eq("labels"));

        List<?> printed = captor.getValue();
        assertEquals(1, printed.size());
    }
}
