# Wanaku - A MCP Router that connects everything

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/wanaku-ai/wanaku/main-build.yml?branch=main)](https://github.com/wanaku-ai/wanaku/actions)
[![Release](https://img.shields.io/github/v/release/wanaku-ai/wanaku)](https://github.com/wanaku-ai/wanaku/releases)

Wanaku Barn is a collection of utilities for the [Wanaku Governed Execution Proxy](https://github.com/wanaku-ai/wanaku) (formerly known as
Wanaku MCP Router). It provides the project with the OpenShift/Kubernetes operator for simplified deployment on the
cloud, a CLI that helps manage the project and tools for simplifying the administration of credentials when using
Keycloak.

## Quick Start

The quickest way to run the Classic Wanaku backend locally, without Keycloak, is the no-auth Docker Compose file:

```shell
docker compose -f deploy/docker-compose/docker-compose-noauth.yml up
```

Then download the CLI from the **[releases page](https://github.com/wanaku-ai/wanaku/releases)** and unpack it to
manage the running instance.

Access <http://localhost:8080> to enter the dashboard:

![Wanaku Dashboard](docs/imgs/wanaku-dashboard.png)

### Learn Wanaku

The easiest way to learn Wanaku is by following the **[guided tutorial](https://wanaku.ai/docs/demos/)**.

### Basic Usage

The reference documentation, including the complete installation and configuration instructions, is available on the [usage guide](https://wanaku.ai/docs/version/).

## Documentation

The **[Wanaku Documentation](https://wanaku.ai/docs/)** website contains additional documentation, covering several of
components that are part of the project - some of which are hosted in different repositories (i.e.: such as the
[Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability/),
the [Java SDK](https://github.com/wanaku-ai/wanaku-capabilities-java-sdk/), etc.).

## Community

- [GitHub Issues](https://github.com/wanaku-ai/wanaku/issues) - Bug reports and feature requests
- [Discussions](https://github.com/wanaku-ai/wanaku/discussions) - Ask questions and share ideas

Contributors working on the project may want to refer to the [development version of the documentation](/docs) including

- [Pre-release Usage Guide](docs/usage.md) - Pre-release usage guide
- [Architecture](docs/architecture.md) - System architecture and components
- [Building](docs/building.md) - Build and package the project
- [Contributing](CONTRIBUTING.md) - Contribution guidelines
- [Security](SECURITY.md) - Security policy and best practices

## License

This project is licensed under the Apache 2.0 License - see the [LICENSE](LICENSE) file for details.
