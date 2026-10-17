# Agent Skills

Wanaku ships a set of [agent skills](../apps/wanaku-cli/src/main/resources/skills/) that teach coding agents how to use Wanaku.
Each skill is a self-contained guide written for coding agents, following the common
`SKILL.md` format: a directory with a `SKILL.md` file whose YAML frontmatter declares the
skill `name` and a `description` of when to use it.

## Available skills

| Skill | Description |
|-------|-------------|
| [`wanaku-mcp-basics`](../apps/wanaku-cli/src/main/resources/skills/wanaku-mcp-basics/SKILL.md) | Connecting agents to Wanaku, discovering tools, resources and prompts, forwarding external MCP servers, data stores and namespaces |
| [`wanaku-service-catalogs`](../apps/wanaku-cli/src/main/resources/skills/wanaku-service-catalogs/SKILL.md) | Creating, packaging and deploying service catalogs; instantiating service templates |
| [`wanaku-operator`](../apps/wanaku-cli/src/main/resources/skills/wanaku-operator/SKILL.md) | Installing the operator and running Wanaku on Kubernetes or OpenShift |

## Using the skills

### Claude Code

Install all bundled skills into your home directory to make them available everywhere:

```shell
wanaku skill install --claude
```

For a specific project, use `wanaku skill install --to .claude/skills`.
Claude Code discovers the `SKILL.md` files
automatically and loads them when their description matches the task.

To connect Claude Code to the local Wanaku MCP endpoint:

```shell
claude mcp add wanaku --transport http http://localhost:8081/default/mcp
```

### Codex and custom destinations

```shell
wanaku skill install --codex                 # ~/.codex/skills/
wanaku skill install --to /path/to/skills     # overrides --codex and --claude
```

Without `--to`, specify exactly one agent flag. Existing skill directories are not
overwritten; installation fails before copying if any bundled skill already exists.
Other skills in the destination are preserved.

### Other agents

Any coding agent that can read markdown instructions can use these skills directly: point the
agent at the `SKILL.md` file that matches the task, or paste its contents into the agent's
instructions. Each skill is self-contained and references the relevant pages under `docs/`
for further detail.

## Contributing a new skill

1. Create a directory under `apps/wanaku-cli/src/main/resources/skills/` named after the skill (lowercase, hyphen-separated).
2. Add a `SKILL.md` with YAML frontmatter declaring at least `name` and `description` (the
   description should state when the skill applies, so agents can select it).
3. Keep the body concise and actionable, and ground every command in the CLI or the
   documentation under `docs/`.
4. Add the skill files to `apps/wanaku-cli/src/main/resources/skills/index.txt` so the CLI
   can discover them from its packaged resources.
5. Mark documentation and CRD schema references as described below, and keep links to
   other skills relative (for example, `../wanaku-operator/SKILL.md`).
6. Add the new skill to the table above and link related skills from the body.

### Version-aware references

The source `SKILL.md` files are browsable templates. Keep the original relative links
for repository and website readers. Immediately follow each documentation or CRD
schema link with the invisible `<!-- wanaku:versioned -->` marker:

```markdown
[Usage guide](../../docs/usage.md)<!-- wanaku:versioned -->
[Router schema](../../apps/wanaku-operator/deploy/helm/wanaku-operator/crds/wanakurouters.wanaku.ai-v1.yml)<!-- wanaku:versioned -->
```

During Maven's `process-classes` phase, the CLI renders these references in
`target/classes/skills/`, before tests, JVM packaging and native-image generation.
The repository files are not modified. The renderer uses `project.version`, the
same Maven version that supplies `VersionHelper` and `wanaku --version`. Wanaku's
filtered version resource lives at `ai/wanaku/core/util/version.txt` to avoid
colliding with dependency resources such as the Capabilities SDK's `/version.txt`:

- Unqualified release versions such as `0.2.0` use `wanaku-0.2.0`, matching the tag
  created by `release:prepare`. Do not use the separate `v0.2.0` tag: it contains
  development-version code.
- Qualified builds such as `0.3.0-SNAPSHOT`, `0.3.0-alpha1` and `0.3.0-RC1` use `main`.
- Missing or unsupported version formats fail the build instead of guessing a tag.

Documentation links use `https://github.com/wanaku-ai/wanaku-barn/blob/<ref>/docs/`.
CRD links use `https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/<ref>/apps/wanaku-operator/deploy/helm/wanaku-operator/crds/`
so agents can retrieve the YAML directly. Documentation fragments are preserved.
These paths exist on both `main` and the verified `wanaku-0.2.0` release tag; future
release tags must retain the referenced files. Rendering checks files in the local
checkout and does not make network requests or require a tag before a release build.

Malformed markers, unresolved placeholders, missing reference targets and unmarked
`../../docs/` or `../../apps/` Markdown links fail the build. Links between skills
and external URLs are copied unchanged. `wanaku skill install` copies the rendered
resources without rewriting links or relying on the installation directory.
