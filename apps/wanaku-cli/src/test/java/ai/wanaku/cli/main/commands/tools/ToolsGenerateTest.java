package ai.wanaku.cli.main.commands.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.jboss.logging.Logger;
import org.skyscreamer.jsonassert.JSONAssert;
import ai.wanaku.cli.main.CliMain;
import com.sun.net.httpserver.HttpServer;
import picocli.CommandLine;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 60)
public class ToolsGenerateTest {

    private static final Logger LOG = Logger.getLogger(ToolsGenerate.class);

    private static final int EXIT_CODE_SUCCESS = 0;

    private static final String EXPECTED_TOOLS_FILE = "tools/expectedPetStoreTools.json";

    private static final String YAHOO_FINANCE_EXPECTED_TOOLS_FILE = "tools/expectedPetStoreTools.json";

    private static final Pattern STACK_FRAME = Pattern.compile("\\sat\\s+\\S+\\.java:\\d+");

    @TempDir
    Path tempDir;

    /**
     *  issue292  https://github.com/wanaku-ai/wanaku/issues/292
     */
    @Test
    public void toolGenerateYahooFinanceTest() throws Exception {
        CliMain main = new CliMain();
        CommandLine cmd = new CommandLine(main);

        // expectations:
        URL resourceUrl = getClass().getClassLoader().getResource("tools/expected-yahoo-finance-tools.json");

        String tmpDirPathString = tempDir.toString();

        Path resourceFilePath = Path.of(resourceUrl.toURI());
        String expectedToolsDefinition = Files.readString(resourceFilePath);

        String yahooFinanceOutputPathString = tmpDirPathString + "/yahooFinanceJsonTool.json";

        String yahooFinanceInputPathString = copyFromResourceToTempDir("openapi", "yahoo-finance.yml");

        Path yahooFinanceInputPath = Paths.get(yahooFinanceInputPathString);

        int exitCodeYahooJson = cmd.execute(
                "tools",
                "generate",
                "-o",
                yahooFinanceOutputPathString,
                yahooFinanceInputPath.toAbsolutePath().toString());

        assertEquals(EXIT_CODE_SUCCESS, exitCodeYahooJson);
        Path jsonPath = Paths.get(yahooFinanceOutputPathString);

        String content = Files.readString(jsonPath);

        JSONAssert.assertEquals(content, expectedToolsDefinition, true);
    }

    @Test
    public void toolGenerateTest() throws Exception {

        CliMain main = new CliMain();
        CommandLine cmd = new CommandLine(main);
        String tmpDirPathString = tempDir.toString();

        // expectation:
        String petStoreJsonOutputPath = tmpDirPathString + "/petStoreJsonTool.json";
        String petStoreYamlOutputPath = tmpDirPathString + "/petStoreYamlTool.json";

        String petStoreJsonPathString = copyFromResourceToTempDir("openapi", "pet-store.json");
        String petStoreYamlPathString = copyFromResourceToTempDir("openapi", "pet-store.yaml");

        int exitCodePetStoreJson = cmd.execute(
                "tools",
                "generate",
                "-u",
                "https://petstore3.swagger.io/api/v3",
                "-o",
                petStoreJsonOutputPath,
                petStoreJsonPathString);
        int exitCodePetStoreYaml = cmd.execute(
                "tools",
                "generate",
                "-u",
                "https://petstore3.swagger.io/api/v3",
                "-o",
                petStoreYamlOutputPath,
                petStoreYamlPathString);

        assertEquals(EXIT_CODE_SUCCESS, exitCodePetStoreJson);
        assertEquals(EXIT_CODE_SUCCESS, exitCodePetStoreYaml);

        Path jsonPath = Paths.get(petStoreJsonOutputPath);
        Path yamlPath = Paths.get(petStoreYamlOutputPath);

        Assertions.assertTrue(Files.exists(jsonPath));
        Assertions.assertTrue(Files.exists(yamlPath));

        String jsonFileContent = Files.readString(jsonPath);
        String yamlFileContent = Files.readString(yamlPath);

        JSONAssert.assertEquals(jsonFileContent, yamlFileContent, true);

        InputStream is = getClass().getClassLoader().getResourceAsStream(EXPECTED_TOOLS_FILE);
        StringWriter writer = new StringWriter();
        IOUtils.copy(is, writer, "UTF-8");
        String expectedPetstoreToolsJsonContent = writer.toString();
        JSONAssert.assertEquals(jsonFileContent, expectedPetstoreToolsJsonContent, true);
    }

    @Test
    public void missingToolNameTest() throws Exception {
        CliMain main = new CliMain();
        CommandLine cmd = new CommandLine(main);
        String tmpDirPathString = tempDir.toString();

        // Test with open-meteo-api.yaml which has operations without operationId
        String openMeteoOutputPath = tmpDirPathString + "/openMeteoTool.json";
        String openMeteoInputPathString = copyFromResourceToTempDir("openapi", "open-meteo-api.yaml");

        Path openMeteoInputPath = Paths.get(openMeteoInputPathString);

        int exitCode = cmd.execute(
                "tools",
                "generate",
                "-o",
                openMeteoOutputPath,
                openMeteoInputPath.toAbsolutePath().toString());

        assertEquals(EXIT_CODE_SUCCESS, exitCode);

        Path outputPath = Paths.get(openMeteoOutputPath);
        Assertions.assertTrue(Files.exists(outputPath));

        String content = Files.readString(outputPath);

        // Verify that a fallback name was generated (should be "get_v1_forecast")
        Assertions.assertTrue(
                content.contains("\"name\" : \"get_v1_forecast\""),
                "Generated tool should have fallback name 'get_v1_forecast'");
    }

    @Test
    public void nonExistentSpecFileTest() {
        Path missing = tempDir.resolve("nonexistent-openapi-spec.yaml");
        Path output = tempDir.resolve("out.json");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = executeCapturingStderr(stderr, "tools", "generate", "-o", output.toString(), missing.toString());
        String err = stderr.toString(StandardCharsets.UTF_8);

        assertEquals(1, exitCode);
        Assertions.assertTrue(
                err.contains("File not found: " + missing), "Expected a 'File not found' message but got: " + err);
        Assertions.assertFalse(STACK_FRAME.matcher(err).find(), "Stack trace found in stderr: " + err);
        Assertions.assertFalse(Files.exists(output));
    }

    @Test
    public void nonExistentSpecFileVerboseTest() {
        Path missing = tempDir.resolve("nonexistent-openapi-spec.yaml");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = executeCapturingStderr(stderr, "tools", "generate", "--verbose", missing.toString());
        String err = stderr.toString(StandardCharsets.UTF_8);

        assertEquals(1, exitCode);
        Assertions.assertTrue(err.contains("File not found: " + missing));
        Assertions.assertFalse(STACK_FRAME.matcher(err).find(), "Stack trace found in stderr: " + err);
    }

    @Test
    public void httpUrlNotFoundTest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec.yaml", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path output = tempDir.resolve("out.json");

            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exitCode = executeCapturingStderr(
                    stderr, "tools", "generate", "-o", output.toString(), "http://127.0.0.1:" + port + "/spec.yaml");
            String err = stderr.toString(StandardCharsets.UTF_8);

            assertEquals(1, exitCode);
            Assertions.assertTrue(
                    err.contains("File not found:"), "Expected a 'File not found' message but got: " + err);
            Assertions.assertFalse(STACK_FRAME.matcher(err).find(), "Stack trace found in stderr: " + err);
            Assertions.assertFalse(Files.exists(output));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void httpUrlFoundTest() throws Exception {
        byte[] specBytes;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("openapi/pet-store.yaml")) {
            specBytes = is.readAllBytes();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec.yaml", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, specBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(specBytes);
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path output = tempDir.resolve("out.json");

            int exitCode = new CommandLine(new CliMain())
                    .execute(
                            "tools",
                            "generate",
                            "-u",
                            "https://petstore3.swagger.io/api/v3",
                            "-o",
                            output.toString(),
                            "http://127.0.0.1:" + port + "/spec.yaml");

            assertEquals(EXIT_CODE_SUCCESS, exitCode);
            Assertions.assertTrue(Files.exists(output));
        } finally {
            server.stop(0);
        }
    }

    private int executeCapturingStderr(ByteArrayOutputStream captured, String... args) {
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            return new CommandLine(new CliMain()).execute(args);
        } finally {
            System.setErr(originalErr);
        }
    }

    private String copyFromResourceToTempDir(String resourcePath, String resource) throws IOException {
        InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath + "/" + resource);
        Path tempDirDestinationPath = Paths.get(tempDir.toString(), resource);
        Files.copy(resourceStream, tempDirDestinationPath, StandardCopyOption.REPLACE_EXISTING);
        return tempDirDestinationPath.toString();
    }
}
