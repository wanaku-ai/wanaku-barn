package ai.wanaku.cli.main.commands.skill;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jline.terminal.Terminal;
import ai.wanaku.cli.main.CliMain;
import ai.wanaku.cli.main.support.WanakuPrinter;
import picocli.CommandLine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SkillInstallTest {
    private static final List<String> SKILLS =
            List.of("wanaku-mcp-basics", "wanaku-operator", "wanaku-service-catalogs");

    @TempDir
    Path tempDir;

    private String originalHome;
    private ClassLoader originalLoader;
    private final Terminal terminal = mock(Terminal.class);
    // Resource-isolation tests replace the TCCL, which Quarkus' default Mockito answer also uses.
    private final WanakuPrinter printer = mock(WanakuPrinter.class, invocation -> null);

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        originalLoader = Thread.currentThread().getContextClassLoader();
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
        Thread.currentThread().setContextClassLoader(originalLoader);
    }

    @Test
    void registersInstallUnderSkill() {
        CommandLine root = new CommandLine(new CliMain());
        CommandLine.ParseResult result = root.parseArgs("skill", "install", "--codex");
        assertInstanceOf(SkillInstall.class, result.subcommand().subcommand().commandSpec().userObject());
    }

    @Test
    void indexDiscoversEveryPackagedSkillFile() throws Exception {
        List<String> indexed;
        try (InputStream index = originalLoader.getResourceAsStream("skills/index.txt")) {
            assertNotNull(index);
            indexed = new String(index.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .sorted()
                    .toList();
        }
        Path resources = Path.of("src/main/resources/skills");
        try (var files = Files.walk(resources)) {
            List<String> actual = files.filter(Files::isRegularFile)
                    .map(resources::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> !path.equals("index.txt"))
                    .sorted()
                    .toList();
            assertEquals(actual, indexed);
        }
        assertEquals(SKILLS.stream().map(skill -> skill + "/SKILL.md").toList(), indexed);
        for (String resource : indexed) {
            try (InputStream input = originalLoader.getResourceAsStream("skills/" + resource)) {
                assertNotNull(input, resource);
                assertArrayEquals(Files.readAllBytes(resources.resolve(resource)), input.readAllBytes());
            }
        }
    }

    @Test
    void installsCodexSkills() throws Exception {
        assertEquals(0, install("--codex"));
        assertInstalled(tempDir.resolve(".codex/skills"));
        assertFalse(Files.exists(tempDir.resolve(".claude")));
    }

    @Test
    void installsClaudeSkills() throws Exception {
        assertEquals(0, install("--claude"));
        assertInstalled(tempDir.resolve(".claude/skills"));
        assertFalse(Files.exists(tempDir.resolve(".codex")));
    }

    @Test
    void installsCustomDestinationWithoutAgent() throws Exception {
        Path custom = tempDir.resolve("nested/custom/skills");
        assertEquals(0, install("--to", custom.toString()));
        assertInstalled(custom);
    }

    @Test
    void customDestinationOverridesEitherAndBothAgentFlags() throws Exception {
        for (String[] agents :
                List.of(new String[] {"--codex"}, new String[] {"--claude"}, new String[] {"--codex", "--claude"})) {
            Path custom = Files.createTempDirectory(tempDir, "custom");
            String[] args = new String[agents.length + 2];
            System.arraycopy(agents, 0, args, 0, agents.length);
            args[agents.length] = "--to";
            args[agents.length + 1] = custom.toString();
            assertEquals(0, install(args));
            assertInstalled(custom);
        }
        assertFalse(Files.exists(tempDir.resolve(".codex")));
        assertFalse(Files.exists(tempDir.resolve(".claude")));
    }

    @Test
    void rejectsMissingOrAmbiguousAgentWithoutDestination() {
        assertEquals(1, install());
        assertEquals(1, install("--codex", "--claude"));
        assertFalse(Files.exists(tempDir.resolve(".codex")));
        assertFalse(Files.exists(tempDir.resolve(".claude")));
        verify(printer, times(2)).printErrorMessage("Specify --to or exactly one of --codex and --claude");
    }

    @Test
    void refusesExistingSkillBeforeCopyingAndPreservesContents() throws Exception {
        Path existing = tempDir.resolve("wanaku-service-catalogs/SKILL.md");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "local changes");
        assertEquals(1, install("--to", tempDir.toString()));
        assertEquals("local changes", Files.readString(existing));
        assertFalse(Files.exists(tempDir.resolve("wanaku-mcp-basics")));
        assertFalse(Files.exists(tempDir.resolve("wanaku-operator")));
        verify(printer)
                .printErrorMessage(argThat(
                        message -> message.contains(existing.getParent().toString()) && message.contains("--to")));
    }

    @Test
    void preservesUnrelatedSkillsAndRefusesReinstallation() throws Exception {
        Path unrelated = tempDir.resolve("other/SKILL.md");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, "other skill");
        assertEquals(0, install("--to", tempDir.toString()));
        assertEquals("other skill", Files.readString(unrelated));
        assertEquals(1, install("--to", tempDir.toString()));
        assertInstalled(tempDir);
    }

    @Test
    void reportsInvalidDestination() throws Exception {
        Path file = tempDir.resolve("file");
        Files.writeString(file, "existing file");
        assertEquals(1, install("--to", file.toString()));
        assertEquals("existing file", Files.readString(file));
        verify(printer)
                .printErrorMessage(argThat(message -> message.startsWith("Failed to install skills:")
                        && message.contains("Check the destination path and permissions")));
    }

    @Test
    void installsFromJarResources() throws Exception {
        Path jar = tempDir.resolve("skills.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addResource(output, "skills/index.txt");
            for (String skill : SKILLS) {
                addResource(output, "skills/" + skill + "/SKILL.md");
            }
        }
        try (URLClassLoader loader =
                new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(0, install("--codex"));
        }
        assertInstalled(tempDir.resolve(".codex/skills"));
    }

    @Test
    void missingBundledFileFailsBeforeCopying() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[0], null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals("skills/index.txt") ? originalLoader.getResourceAsStream(name) : null;
            }
        }) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(1, install("--codex"));
        }
        assertFalse(Files.exists(tempDir.resolve(".codex")));
    }

    @Test
    void missingIndexFailsWithoutCreatingDestination() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[0], null)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(1, install("--claude"));
        }
        assertFalse(Files.exists(tempDir.resolve(".claude")));
    }

    private int install(String... args) {
        SkillInstall command = new SkillInstall();
        new CommandLine(command).parseArgs(args);
        return command.doCall(terminal, printer);
    }

    private void assertInstalled(Path destination) throws IOException {
        for (String skill : SKILLS) {
            String resource = "skills/" + skill + "/SKILL.md";
            try (InputStream input = originalLoader.getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(destination.resolve(skill + "/SKILL.md")));
            }
        }
        assertFalse(Files.exists(destination.resolve("index.txt")));
    }

    private void addResource(JarOutputStream output, String resource) throws IOException {
        output.putNextEntry(new JarEntry(resource));
        try (InputStream input = originalLoader.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            input.transferTo(output);
        }
        output.closeEntry();
    }
}
