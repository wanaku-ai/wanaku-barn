# Agent Skills

Wanaku ships a set of [agent skills](https://github.com/wanaku-ai/wanaku-barn/tree/main/apps/wanaku-cli/src/main/resources/skills/) that teach coding agents how to use Wanaku.
Each skill is a self-contained guide written for coding agents, following the common
`SKILL.md` format: a directory with a `SKILL.md` file whose YAML frontmatter declares the
skill `name` and a `description` of when to use it.

## Available skills

| Skill | Description |
|-------|-------------|
| [`wanaku-mcp-basics`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-cli/src/main/resources/skills/wanaku-mcp-basics/SKILL.md) | Connecting agents to Wanaku, discovering tools, resources and prompts, forwarding external MCP servers, data stores and namespaces |
| [`wanaku-service-catalogs`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-cli/src/main/resources/skills/wanaku-service-catalogs/SKILL.md) | Creating, packaging and deploying service catalogs; instantiating service templates |
| [`wanaku-operator`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-cli/src/main/resources/skills/wanaku-operator/SKILL.md) | Installing the operator and running Wanaku on Kubernetes or OpenShift |

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
5. Add the new skill to the table above and link related skills from the body.
