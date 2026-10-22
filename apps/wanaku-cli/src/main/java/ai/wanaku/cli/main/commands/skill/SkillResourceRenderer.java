package ai.wanaku.cli.main.commands.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Build-time rendering of versioned references in the browsable skill templates. */
public final class SkillResourceRenderer {
    private static final String REPOSITORY = "https://github.com/wanaku-ai/wanaku-barn/blob/";
    private static final String RAW_REPOSITORY = "https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/";
    private static final String NUMBER = "(?:0|[1-9][0-9]*)";
    private static final Pattern VERSION =
            Pattern.compile("(" + NUMBER + "\\." + NUMBER + "\\." + NUMBER + ")(-[A-Za-z0-9][A-Za-z0-9.-]*)?");
    private static final Pattern LINK =
            Pattern.compile("\\[([^]\\r\\n]+)]\\(\\.\\./\\.\\./([^\\s)]+)\\)<!-- wanaku:versioned -->");
    private static final Pattern UNRENDERED_LINK = Pattern.compile("\\]\\(\\.\\./\\.\\./(?:docs|apps)/");

    private SkillResourceRenderer() {}

    /**
     * Called by Maven after compilation and before tests, JAR packaging and native-image generation.
     * The version argument is the same project.version used to filter VersionHelper's version.txt.
     *
     * @param args Maven version, source skills directory, output skills directory and repository root
     * @throws IOException if an indexed file or a versioned reference is invalid
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IOException("Expected version, source skills, output skills and repository root");
        }
        renderResources(args[0], Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    }

    static String reference(String version) throws IOException {
        if (version == null) {
            throw new IOException("Missing CLI build version");
        }
        Matcher matcher = VERSION.matcher(version.strip());
        if (!matcher.matches()) {
            throw new IOException("Unsupported CLI build version: " + version);
        }
        // Only unqualified Maven release versions map to the release:prepare tag.
        return matcher.group(2) == null ? "wanaku-" + matcher.group(1) : "main";
    }

    static String render(String template, String version, Path repositoryRoot) throws IOException {
        String ref = reference(version);
        Matcher matcher = LINK.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(2);
            String path = target.split("#", 2)[0];
            Path relative = Path.of(path);
            boolean document = path.startsWith("docs/") && path.endsWith(".md");
            boolean schema =
                    path.startsWith("apps/wanaku-operator/deploy/helm/wanaku-operator/crds/") && path.endsWith(".yml");
            if ((!document && !schema)
                    || relative.isAbsolute()
                    || !relative.normalize().equals(relative)
                    || !Files.isRegularFile(repositoryRoot.resolve(relative))) {
                throw new IOException("Invalid versioned skill reference: " + target);
            }
            String url = (document ? REPOSITORY : RAW_REPOSITORY) + ref + "/" + target;
            matcher.appendReplacement(rendered, Matcher.quoteReplacement("[" + matcher.group(1) + "](" + url + ")"));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        if (result.contains("wanaku:")
                || result.contains("${")
                || UNRENDERED_LINK.matcher(result).find()) {
            throw new IOException("Unrendered or malformed versioned skill reference");
        }
        return result;
    }

    static void renderResources(String version, Path source, Path output, Path repositoryRoot) throws IOException {
        reference(version);
        Map<Path, String> rendered = new LinkedHashMap<>();
        for (String entry : Files.readAllLines(source.resolve("index.txt"), StandardCharsets.UTF_8)) {
            if (entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry);
            if (path.isAbsolute() || !path.normalize().equals(path) || path.getNameCount() < 2) {
                throw new IOException("Invalid bundled skill index entry: " + entry);
            }
            String template = Files.readString(source.resolve(path), StandardCharsets.UTF_8);
            if (rendered.put(path, render(template, version, repositoryRoot)) != null) {
                throw new IOException("Duplicate bundled skill index entry: " + entry);
            }
        }
        if (rendered.isEmpty()) {
            throw new IOException("No bundled skills found");
        }
        // Validate all templates before replacing the copied resources.
        for (Map.Entry<Path, String> entry : rendered.entrySet()) {
            Path file = output.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
    }
}
