package ai.wanaku.cli.main.commands.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillResourceRendererTest {
    private static final Path REPOSITORY = Path.of("../..");
    private static final String SCHEMA =
            "apps/wanaku-operator/deploy/helm/wanaku-operator/crds/wanakurouters.wanaku.ai-v1.yml";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({
        "0.3.0-SNAPSHOT, main",
        "0.3.0-alpha1, main",
        "0.3.0-beta.2, main",
        "0.3.0-RC1, main",
        "0.3.0-early-access, main",
        "0.2.0, wanaku-0.2.0",
        "1.12.3, wanaku-1.12.3"
    })
    void selectsExactDocumentationAndRawSchemaUrls(String version, String ref) throws Exception {
        String template = "[Usage](../../docs/usage.md#installation)<!-- wanaku:versioned -->\n"
                + "[Schema](../../" + SCHEMA + ")<!-- wanaku:versioned -->\n"
                + "[Operator](../wanaku-operator/SKILL.md)\n"
                + "[External](https://example.org/guide)";
        String expected = "[Usage](https://github.com/wanaku-ai/wanaku-barn/blob/" + ref
                + "/docs/usage.md#installation)\n"
                + "[Schema](https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/" + ref + "/" + SCHEMA + ")\n"
                + "[Operator](../wanaku-operator/SKILL.md)\n"
                + "[External](https://example.org/guide)";
        assertEquals(expected, SkillResourceRenderer.render(template, version, REPOSITORY));
    }

    @Test
    void sourceTemplatesKeepClickableMarkdownLinks() throws Exception {
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        Path source = Path.of("src/main/resources/skills");
        for (String resource : Files.readAllLines(source.resolve("index.txt"))) {
            String template = Files.readString(source.resolve(resource));
            String original = template.replace("<!-- wanaku:versioned -->", "");
            String html = renderer.render(parser.parse(template));
            assertEquals(renderer.render(parser.parse(original)), html.replace("<!-- wanaku:versioned -->", ""));
            assertTrue(html.contains("href=\"../../docs/usage.md\""));
        }
    }

    @Test
    void stripsVersionFileNewline() throws Exception {
        assertEquals("wanaku-0.2.0", SkillResourceRenderer.reference("0.2.0\n"));
        assertEquals("main", SkillResourceRenderer.reference("0.3.0-SNAPSHOT\n"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"${project.version}", "unknown", "v0.2.0", "0.2", "0.2.0/../main", "0.2.0+build", "01.2.3"})
    void rejectsMissingOrUnsupportedVersion(String version) {
        assertThrows(IOException.class, () -> SkillResourceRenderer.reference(version));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[Missing](../../docs/does-not-exist.md)<!-- wanaku:versioned -->",
                "[Wrong](../../README.md)<!-- wanaku:versioned -->",
                "[Traversal](../../docs/../docs/usage.md)<!-- wanaku:versioned -->",
                "[Missing schema](../../apps/wanaku-operator/deploy/helm/wanaku-operator/crds/missing.yml)<!-- wanaku:versioned -->",
                "[Wrong schema](../../apps/wanaku-cli/pom.xml)<!-- wanaku:versioned -->",
                "[Bad](https://example.org/docs/usage.md)<!-- wanaku:versioned -->",
                "<!-- wanaku:versioned -->[Bad](../../docs/usage.md",
                "<!-- wanaku:typo -->[Bad](../../docs/usage.md)",
                "[Unmarked](../../docs/usage.md)",
                "[Unmarked schema](../../apps/wanaku-operator/deploy/helm/wanaku-operator/crds/wanakurouters.wanaku.ai-v1.yml)",
                "[Unreplaced](${wanaku.docs}/usage.md)"
            })
    void rejectsMissingWrongOrUnreplacedReferences(String template) {
        assertThrows(IOException.class, () -> SkillResourceRenderer.render(template, "0.3.0-SNAPSHOT", REPOSITORY));
    }

    @Test
    void rendersEveryIndexedResourceWithoutChangingSourceTemplates() throws Exception {
        Path source = Path.of("src/main/resources/skills");
        Path output = tempDir.resolve("rendered");
        String original = Files.readString(source.resolve("wanaku-operator/SKILL.md"));
        SkillResourceRenderer.renderResources("0.2.0", source, output, REPOSITORY);
        assertEquals(original, Files.readString(source.resolve("wanaku-operator/SKILL.md")));
        assertTrue(original.contains("[Operator guide](../../docs/operator.md)"));
        String rendered = Files.readString(output.resolve("wanaku-operator/SKILL.md"));
        assertTrue(rendered.contains(
                "[Operator guide](https://github.com/wanaku-ai/wanaku-barn/blob/wanaku-0.2.0/docs/operator.md)"));
        assertTrue(rendered.contains(
                "[router CRD schema](https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/wanaku-0.2.0/" + SCHEMA
                        + ")"));
        assertFalse(rendered.contains("wanaku:"));
        for (String entry : Files.readAllLines(source.resolve("index.txt"))) {
            assertTrue(Files.isRegularFile(output.resolve(entry)), entry);
        }
    }

    @Test
    void invalidLaterTemplateFailsBeforeWritingAnyResource() throws Exception {
        Path source = tempDir.resolve("source");
        Path output = tempDir.resolve("output");
        Files.createDirectories(source.resolve("first"));
        Files.createDirectories(source.resolve("second"));
        Files.writeString(source.resolve("index.txt"), "first/SKILL.md\nsecond/SKILL.md\n");
        Files.writeString(source.resolve("first/SKILL.md"), "[Usage](../../docs/usage.md)<!-- wanaku:versioned -->");
        Files.writeString(source.resolve("second/SKILL.md"), "${unreplaced}");
        assertThrows(
                IOException.class, () -> SkillResourceRenderer.renderResources("0.2.0", source, output, REPOSITORY));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "missing/SKILL.md",
                "../docs/usage.md",
                "/tmp/SKILL.md",
                "index.txt",
                "first/SKILL.md\nfirst/SKILL.md"
            })
    void rejectsEmptyMissingUnsafeOrDuplicateIndexEntries(String index) throws Exception {
        Path source = tempDir.resolve("source");
        Path output = tempDir.resolve("output");
        Files.createDirectories(source.resolve("first"));
        Files.writeString(source.resolve("first/SKILL.md"), "plain skill");
        Files.writeString(source.resolve("index.txt"), index);
        assertThrows(
                IOException.class, () -> SkillResourceRenderer.renderResources("0.2.0", source, output, REPOSITORY));
        assertFalse(Files.exists(output));
    }

    @Test
    void missingIndexFailsBeforeWriting() {
        assertThrows(
                IOException.class,
                () -> SkillResourceRenderer.renderResources("0.2.0", tempDir, tempDir.resolve("output"), REPOSITORY));
        assertFalse(Files.exists(tempDir.resolve("output")));
    }
}
