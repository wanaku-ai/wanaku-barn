---
layout: home
title: "Wanaku Barn: tools to manage, deploy, and extend Wanaku"
description: Wanaku Barn provides the Wanaku CLI, the Wanaku Operator, the Keycloak tools, and the Barn backend for service catalogs and Admin UI extensions.
hero:
  name: Wanaku Barn
  text: Tools to manage, deploy, and extend Wanaku
  tagline: The Wanaku CLI, the Wanaku Operator, the Keycloak tools, and the Barn backend for the Wanaku Admin UI.
  actions:
    - theme: brand
      text: Install the CLI
      link: ./cli/install
    - theme: alt
      text: Architecture
      link: ./get-started/architecture
features:
  - title: Get started
    details: Learn what each Barn component does and how it connects to Wanaku.
    link: ./get-started/
  - title: Use the CLI
    details: Install the Wanaku CLI, sign in, and manage forwards, namespaces, and evaluators.
    link: ./cli/
  - title: Deploy and operate
    details: Install the Barn backend, deploy with the Wanaku Operator, and set up Keycloak.
    link: ./operate/
  - title: Manage integrations and data
    details: Deploy service catalogs and templates, store Kamelets and data, and build semantic routers.
    link: ./manage/
  - title: Reference
    details: Look up configuration properties, the service catalog API, and the operator resources.
    link: ./reference/
  - title: Contribute
    details: Build Barn from source, run the tests, and prepare a release.
    link: ./contributing/
---

# Wanaku Barn: tools to manage, deploy, and extend Wanaku

[Wanaku](https://wanaku.ai/docs/next/) is a governed action proxy for AI agents. Wanaku Barn adds the tools around it: a CLI to manage it, an operator to deploy it on Kubernetes and OpenShift, tools to administer its Keycloak credentials, and a backend that stores service catalogs and adds pages to the Wanaku Admin UI.

## Choose your path

| You want to | Start with | Then read |
|---|---|---|
| Manage Wanaku from the command line | [Install the Wanaku CLI](cli/install.md) | [Authenticate](cli/authenticate.md), [Forward MCP servers](cli/forwards.md) |
| Add the Barn pages to the Admin UI | [Install the Barn backend](operate/install.md) | [Service catalogs](manage/service-catalogs.md) |
| Deploy on Kubernetes or OpenShift | [Deploy with the Wanaku Operator](operate/operator.md) | [Operator custom resources](reference/operator-crds.md) |
| Serve Camel routes as MCP tools | [Build and deploy a service catalog](manage/build-a-service-catalog.md) | [Service templates](manage/service-templates.md) |
| Set up sign-in | [Set up Keycloak for Wanaku](operate/keycloak.md) | [Secure a deployment](operate/secure-a-deployment.md) |
| Fix a problem | [Troubleshooting](operate/troubleshooting.md) | [Configuration](reference/configuration.md) |
| Contribute code or docs | [Contributing](contributing/index.md) | [Build from source](contributing/building.md) |

## Next steps

- [Get started with Wanaku Barn](get-started/index.md): the Barn components and how they connect to Wanaku.
- [Wanaku documentation](https://wanaku.ai/docs/next/): install and run Wanaku Server.
