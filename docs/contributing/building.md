---
title: Build Wanaku Barn from source
description: Build the Wanaku CLI, the Barn backend, the Wanaku Operator, and the Admin UI plugins from source with Maven, including native CLI builds.
---

# Build Wanaku Barn from source

## Overview

This page shows how to build the Barn components from source: the Wanaku CLI, the Barn backend, the Wanaku Operator, and the Admin UI plugins.

## Prerequisites

- Java 21 or later
- [Apache Maven](https://maven.apache.org) 3.x is required to build and package the project.
- For (experimental) native CLI builds, please follow the [Quarkus instructions on how to set up your environment](https://quarkus.io/guides/building-native-image).

### Building the Project

To build all the Barn components, run:

```shell
mvn verify
```

This command will compile, test, and verify the project.

To package it into a distributable format (i.e.: tarballs), run:

```shell
mvn -Pdist clean package
```

## Building with Native CLI Support

To build the project with native CLI support, run:

```shell
mvn -Pnative clean package
```

## Building with Native Backend Support

The Wanaku backend also supports native mode, but this feature has not been fully tested.

To enable native mode for the backend, run:

```shell
mvn -Pdist -Dnative -Dnative-router clean package
```

> [!IMPORTANT]
> Native support is experimental

By following these steps, you should be able to successfully build the Wanaku project and prepare it for deployment.

## Native build tips

The project comes with rudimentary automation for native builds for development purposes. For instance, you can install the CLI
into your `$HOME/bin/` directory by running:

```shell
eval $(make prepare)
make cli-native
make install
```

See [PATH Configuration](../cli/install.md#path-configuration) if `$HOME/bin` is not on your default `PATH`.

## Building with the containers

```shell
mvn -Pdist -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true clean package
```
