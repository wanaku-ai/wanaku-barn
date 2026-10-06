# Agent Skills

Wanaku ships a set of [agent skills](../skills/) that teach coding agents how to use Wanaku.
Each skill is a self-contained guide written for coding agents, following the common
`SKILL.md` format: a directory with a `SKILL.md` file whose YAML frontmatter declares the
skill `name` and a `description` of when to use it.

## Available skills

| Skill | Description |
|-------|-------------|
| [`wanaku-mcp-basics`](../skills/wanaku-mcp-basics/SKILL.md) | Connecting agents to Wanaku, discovering tools, resources and prompts, forwarding external MCP servers, data stores and namespaces |
| [`wanaku-service-catalogs`](../skills/wanaku-service-catalogs/SKILL.md) | Creating, packaging and deploying service catalogs; instantiating service templates |
| [`wanaku-operator`](../skills/wanaku-operator/SKILL.md) | Installing the operator and running Wanaku on Kubernetes or OpenShift |

## Using the skills

### Claude Code

Copy (or symlink) a skill directory into your skills folder, either in your home directory to
make it available everywhere:

```shell
mkdir -p ~/.claude/skills
cp -r skills/wanaku-mcp-basics ~/.claude/skills/
```

or into `.claude/skills/` of a specific project. Claude Code discovers the `SKILL.md` files
automatically and loads them when their description matches the task.

Alternatively, the CLI can print ready-to-run registration commands for your MCP client:

```shell
wanaku configure claude-code
```

### Other agents

Any coding agent that can read markdown instructions can use these skills directly: point the
agent at the `SKILL.md` file that matches the task, or paste its contents into the agent's
instructions. Each skill is self-contained and references the relevant pages under `docs/`
for further detail.

## Contributing a new skill

1. Create a directory under `skills/` named after the skill (lowercase, hyphen-separated).
2. Add a `SKILL.md` with YAML frontmatter declaring at least `name` and `description` (the
   description should state when the skill applies, so agents can select it).
3. Keep the body concise and actionable, and ground every command in the CLI or the
   documentation under `docs/`.
4. Add the new skill to the table above and link related skills from the body.
