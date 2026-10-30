# Wanaku Barn - A Collection of Enterprise Utilities for Wanaku

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/wanaku-ai/wanaku-barn/main-build.yml?branch=main)](https://github.com/wanaku-ai/wanaku-barn/actions)
[![Release](https://img.shields.io/github/v/release/wanaku-ai/wanaku-barn)](https://github.com/wanaku-ai/wanaku-barn/releases)

Wanaku Barn is a collection of utilities for [Wanaku](https://github.com/wanaku-ai/wanaku) (formerly known as
Wanaku MCP Router). It provides the project with the OpenShift/Kubernetes operator for simplified deployment on the
cloud, a CLI that helps manage the project and tools for simplifying the administration of credentials when using
Keycloak.

## Quick Start

The quickest way to run Wanaku Barn is to install it as a plugin in Wanaku. It is already listed in the
default catalog. When asked for the configuration, provide the address on which Wanaku Barn listens to (<http://localhost:8180>).

Once installed, select one of the Wanaku Barn backend deliverables from the [releases page](https://github.com/wanaku-ai/wanaku-barn/releases),
unpack it and launch the process:

```shell
java -jar /path/to/quarkus-run.jar
```

You should see an Extensions menu, with several items. If you select, for instance, Data Stores, you should see:

![Wanaku Barn Data Stores](docs/imgs/wanaku-data-stores.png)

### Learn Wanaku

The easiest way to learn Wanaku is by following the **[guided tutorial](https://wanaku.ai/docs/demos/)**.

### Basic Usage

The reference documentation, including the complete installation and configuration instructions, is available on the [usage guide](https://wanaku.ai/docs/barn/wanaku-barn-current/docs/usage.html).

## Documentation

The **[Wanaku Documentation](https://wanaku.ai/docs/)** website contains additional documentation, covering several of
components that are part of the project - some of which are hosted in different repositories (i.e.: such as the
[Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability/),
the [Java SDK](https://github.com/wanaku-ai/wanaku-capabilities-java-sdk/), etc.).

## Community

- [GitHub Issues](https://github.com/wanaku-ai/wanaku/issues) - Bug reports and feature requests
- [Discussions](https://github.com/wanaku-ai/wanaku/discussions) - Ask questions and share ideas

Contributors working on the project may want to refer to the [development version of the documentation](/docs) including

- [CLI guide](docs/cli/index.md) - Install the CLI and manage Wanaku from the command line
- [Architecture](docs/get-started/architecture.md) - System architecture and components
- [Building](docs/contributing/building.md) - Build and package the project
- [Deployment Debugger](docs/manage/deployment-debugger.md) - Inspect deployed MCP servers from Wanaku
- [Audit Trail](docs/manage/audit-trail.md) - Durable record of changes to Barn-managed resources
- [Backup, Restore and Upgrade](docs/operate/backup-and-upgrade.md) - Export, import and schema migrations
- [Agent Skills](docs/cli/agent-skills.md) - Skills that teach coding agents how to use Wanaku
- [Test Plans](https://github.com/wanaku-ai/wanaku-tests) - Test plans for humans and AI agents
- [Contributing](CONTRIBUTING.md) - Contribution guidelines
- [Security](SECURITY.md) - Security policy and best practices

## License

This project is licensed under the Apache 2.0 License - see the [LICENSE](LICENSE) file for details.
