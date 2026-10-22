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
import ai.wanaku.core.util.VersionHelper;
import picocli.CommandLine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(result.subcommand().subcommand().commandSpec().userObject() instanceof SkillInstall);
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
                String expected = SkillResourceRenderer.render(
                        Files.readString(resources.resolve(resource)), VersionHelper.VERSION, Path.of("../.."));
                assertEquals(expected, new String(input.readAllBytes(), StandardCharsets.UTF_8));
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

    @ParameterizedTest
    @CsvSource({
        "0.3.0-SNAPSHOT, codex", "0.3.0-SNAPSHOT, claude", "0.3.0-SNAPSHOT, custom",
        "0.3.0-RC1, codex", "0.3.0-RC1, claude", "0.3.0-RC1, custom",
        "0.2.0, codex", "0.2.0, claude", "0.2.0, custom"
    })
    void installsVersionedJarResourcesForEveryDestination(String version, String agent) throws Exception {
        Path jar = tempDir.resolve("versioned-skills.jar");
        Path source = Path.of("src/main/resources/skills");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addResource(output, "skills/index.txt");
            for (String skill : SKILLS) {
                String resource = skill + "/SKILL.md";
                String rendered = SkillResourceRenderer.render(
                        Files.readString(source.resolve(resource)), version, Path.of("../.."));
                output.putNextEntry(new JarEntry("skills/" + resource));
                output.write(rendered.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        Path destination = agent.equals("custom")
                ? tempDir.resolve("nested/custom/skills")
                : tempDir.resolve("." + agent + "/skills");
        try (URLClassLoader loader =
                new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(0, agent.equals("custom") ? install("--to", destination.toString()) : install("--" + agent));
            assertVersionedLinks(destination, version);
            assertEquals(1, install("--to", destination.toString()));
            assertVersionedLinks(destination, version);
        }
    }

    @Test
    void emptyIndexFailsWithoutCreatingDestination() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[0], null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals("skills/index.txt") ? new java.io.ByteArrayInputStream(new byte[0]) : null;
            }
        }) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(1, install("--codex"));
        }
        assertFalse(Files.exists(tempDir.resolve(".codex")));
        verify(printer).printErrorMessage(argThat(message -> message.contains("No bundled skills found")));
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
        assertVersionedLinks(destination);
        assertFalse(Files.exists(destination.resolve("index.txt")));
    }

    private void assertVersionedLinks(Path destination) throws IOException {
        assertVersionedLinks(destination, VersionHelper.VERSION);
    }

    private void assertVersionedLinks(Path destination, String buildVersion) throws IOException {
        String version = buildVersion.strip();
        String ref = version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ? "wanaku-" + version : "main";
        String docs = "https://github.com/wanaku-ai/wanaku-barn/blob/" + ref + "/docs/";
        String basics = Files.readString(destination.resolve("wanaku-mcp-basics/SKILL.md"));
        String operator = Files.readString(destination.resolve("wanaku-operator/SKILL.md"));
        String catalogs = Files.readString(destination.resolve("wanaku-service-catalogs/SKILL.md"));
        assertTrue(basics.contains("[Usage guide](" + docs + "usage.md)"));
        assertTrue(operator.contains("[Operator guide](" + docs + "operator.md)"));
        assertTrue(operator.contains("[Usage guide](" + docs + "usage.md)"));
        assertTrue(catalogs.contains("[Service catalogs guide](" + docs + "service-catalogs.md)"));
        assertTrue(catalogs.contains("[Usage guide](" + docs + "usage.md)"));
        assertTrue(catalogs.contains("[Operator guide](" + docs + "operator.md)"));
        assertTrue(operator.contains("[router CRD schema](https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/"
                + ref + "/apps/wanaku-operator/deploy/helm/wanaku-operator/crds/wanakurouters.wanaku.ai-v1.yml)"));
        for (String skill : SKILLS) {
            String text = Files.readString(destination.resolve(skill + "/SKILL.md"));
            assertFalse(text.contains("wanaku:"));
            assertFalse(text.contains("${"));
            assertFalse(text.contains("../../docs/"));
            assertFalse(text.contains("../../apps/"));
            var links =
                    java.util.regex.Pattern.compile("\\]\\((\\.\\./[^)]+)\\)").matcher(text);
            while (links.find()) {
                assertTrue(Files.isRegularFile(destination.resolve(skill).resolve(links.group(1))), links.group(1));
            }
        }
        assertTrue(basics.contains("(../wanaku-service-catalogs/SKILL.md)"));
        assertTrue(basics.contains("(../wanaku-operator/SKILL.md)"));
        assertTrue(operator.contains("(../wanaku-service-catalogs/SKILL.md)"));
        assertTrue(catalogs.contains("(../wanaku-operator/SKILL.md)"));
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
