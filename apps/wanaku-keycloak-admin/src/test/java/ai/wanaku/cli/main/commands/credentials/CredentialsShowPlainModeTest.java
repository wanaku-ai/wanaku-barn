package ai.wanaku.cli.main.commands.credentials;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.support.WanakuPrinter;
import ai.wanaku.cli.main.support.keycloak.KeycloakAdminClient;
import picocli.CommandLine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialsShowPlainModeTest {

    @ParameterizedTest
    @CsvSource(
            value = {"raw-secret,0", "NULL,1"},
            nullValues = "NULL")
    void printsOnlyTheSecretOrFailsEmpty(String secret, int expectedResult) throws Exception {
        Terminal terminal = mock(Terminal.class);
        WanakuPrinter printer = mock(WanakuPrinter.class);
        KeycloakAdminClient adminClient = mock(KeycloakAdminClient.class);
        when(adminClient.listClients(any()))
                .thenReturn(List.of(Map.of("clientId", "my-service", "description", "test", "enabled", true)));
        when(adminClient.getClientSecret(any(), any())).thenReturn(secret);

        CredentialsShow cmd = new CredentialsShow(adminClient);
        new CommandLine(cmd).parseArgs("--client-id", "my-service", "--show-secret", "--plain");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int result;
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            result = cmd.doCall(terminal, printer);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(expectedResult, result);
        assertEquals(secret == null ? "" : secret + System.lineSeparator(), stdout.toString(StandardCharsets.UTF_8));
        verify(printer, never()).printTable(any(List.class), any(String[].class));
        if (secret == null) {
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("No secret found"));
        } else {
            assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        }
    }
}
