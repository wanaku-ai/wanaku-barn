# Wanaku Barn - A Collection of Enterprise Utilities for Wanaku

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/wanaku-ai/wanaku-barn/main-build.yml?branch=main)](https://github.com/wanaku-ai/wanaku-barn/actions)
[![Release](https://img.shields.io/github/v/release/wanaku-ai/wanaku-barn)](https://github.com/wanaku-ai/wanaku-barn/releases)

Wanaku Barn is a collection of utilities for [Wanaku](https://github.com/wanaku-ai/wanaku), the governed action proxy
for AI agents. It provides the Wanaku CLI, the Wanaku Operator for Kubernetes and OpenShift, tools that administer
Keycloak credentials, and the Barn backend, which stores service catalogs and adds pages to the Wanaku Admin UI.

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

## Documentation

The Barn documentation is in the [`docs`](docs/index.md) directory:

- [Get started](docs/get-started/index.md): the Barn components and how they connect to Wanaku.
- [Use the CLI](docs/cli/index.md): install the Wanaku CLI and manage Wanaku from the command line.
- [Deploy and operate](docs/operate/index.md): install the Barn backend, deploy with the Wanaku Operator, and set up Keycloak.
- [Manage integrations and data](docs/manage/index.md): service catalogs, templates, Kamelets, data stores, and semantic routers.
- [Reference](docs/reference/index.md): configuration, the service catalog API, and the operator resources.
- [Contribute](docs/contributing/index.md): build from source, run the tests, and prepare a release.

For Wanaku itself, see the [Wanaku documentation](https://wanaku.ai/docs/next/). The [guided tutorial](https://wanaku.ai/docs/demos/) is the easiest way to learn Wanaku.

## Community

- [GitHub Issues](https://github.com/wanaku-ai/wanaku/issues) - Bug reports and feature requests
- [Discussions](https://github.com/wanaku-ai/wanaku/discussions) - Ask questions and share ideas
- [Contributing](CONTRIBUTING.md) - Contribution guidelines
- [Security](SECURITY.md) - Security policy

## License

This project is licensed under the Apache 2.0 License - see the [LICENSE](LICENSE) file for details.
