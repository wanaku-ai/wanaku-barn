---
title: Install the Wanaku CLI
description: Install the Wanaku CLI from a release binary, with JBang, or with the get-wanaku.sh script, then add it to your PATH and configure HTTPS trust.
---

# Install the Wanaku CLI

The Wanaku CLI manages Wanaku from the command line. This page shows how to install it and how to make it trust HTTPS endpoints.

## Prerequisites

The native CLI binary needs no other software. The JBang and the Java-based distributions need Java 21 or later. To check your Java version, run:

```shell
java -version
```

## Installing the CLI by downloading binary

The most recommended method for installing the Wanaku CLI is to download the latest version directly from the
[release](https://github.com/wanaku-ai/wanaku/releases) page on GitHub

### Installing the CLI via JBang

To simplify using the Wanaku Command Line Interface (CLI), you can install it via [JBang](https://www.jbang.dev/).

> [!NOTE]
> JBang requires Java 21 or later for running Wanaku CLI.

First, ensure JBang is installed on your system. You can find detailed [download and installation](https://www.jbang.dev/download/) instructions on the official JBang website.

After installing JBang, verify it's working correctly by opening your command shell and running:

```shell
jbang version
```

This command should display the installed version of JBang.

Next, to access the Wanaku CLI, install it using JBang with the following command:

```shell
jbang app install wanaku@wanaku-ai/wanaku-barn
```

This will install Wanaku CLI as the `wanaku` command within JBang, meaning that you can run Wanaku from the command line by just
executing `wanaku`.

> [!NOTE]
> It requires access to the internet, in case of using a proxy, please ensure that the proxy is configured for your system.
> If Wanaku JBang is not working with your current configuration, please look to [Proxy configuration in JBang documentation](https://www.jbang.dev/documentation/jbang/latest/configuration.html#proxy-configuration).

## PATH Configuration

If you installed the Wanaku CLI using `get-wanaku.sh` or the native build (`make install`), it is placed in `$HOME/bin`. Many systems do not include `$HOME/bin` in the default `PATH`, so you may need to add it manually to avoid a "command not found" error when running `wanaku`.

To add `$HOME/bin` to your `PATH` for the current session:

```shell
export PATH="$HOME/bin:$PATH"
```

To persist this across sessions, add the line above to your shell configuration file:

```shell
echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

> [!NOTE]
> If you are using `zsh`, replace `~/.bashrc` with `~/.zshrc`.

## Installing with `get-wanaku.sh`

```shell
curl -fsSL https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/main/get-wanaku.sh | bash
```

> [!NOTE]
> This script installs the Wanaku CLI. The wanaku repository has a different `get-wanaku.sh` script that installs the Wanaku server. Read the [Wanaku getting started guide](https://github.com/wanaku-ai/wanaku/blob/main/docs/get-started/quickstart.md) to install the server.

The script detects your OS and architecture, downloads the release archive, verifies its SHA-256 checksum, and installs the `wanaku` and `wanaku-cli` commands to `$HOME/bin`.

- **Linux x86_64 and macOS arm64:** installs the native binary. Java is not required.
- **Other platforms:** installs the Java-based distribution under `$HOME/bin/wanaku-java`, with small `wanaku`/`wanaku-cli` launchers. Requires Java 21 or later (taken from `JAVA_HOME` if set, otherwise from `PATH`).

Prerequisites: `bash`, `curl`, `unzip`, and `sha256sum` or `shasum`. If the release's checksum file has no entry for the downloaded archive, the script prints a warning and skips verification. If the checksum does not match, it aborts.

You can customize the installation with these environment variables:

| Variable             | Default     | Description                                                                 |
|----------------------|-------------|-----------------------------------------------------------------------------|
| `WANAKU_INSTALL_DIR` | `$HOME/bin` | Installation directory (must be writable by the current user)               |
| `WANAKU_VERSION`     | latest      | Release tag to install (for example, `v0.2.0`). Avoids a GitHub API call.   |
| `WANAKU_FORCE_JAVA`  | `false`     | Set to `true` to install the Java-based distribution on any platform        |

For example:

```shell
curl -fsSL https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/main/get-wanaku.sh | WANAKU_VERSION=v0.2.0 WANAKU_INSTALL_DIR="$HOME/.local/bin" bash
```

Running the script again upgrades or replaces an existing installation in the same directory.

Verify the installation:

```shell
wanaku --version
```

## Handling HTTPS connections

Wanaku CLI may connect to either Keycloak or Wanaku MCP server, if their HTTP endpoints are protected by a server certificate (TLS) you may choose to skip the certificate checks or use the CA (Certificate Authority) that issued them on the wanaku-cli so the client can check the server certificate.

1. In wanaku-cli you can skip the checks by using the `--insecure` parameter, and it's going to print a warning message about it.
2. You can use the CA that signed the Keycloak HTTPS endpoint, by either importing it into the default Java truststore `$JAVA_HOME/lib/security/cacerts` or have a particular keystore file and use the `-Djavax.net.ssl.trustStore` and `-Djavax.net.ssl.trustStorePassword` parameters to refer to this custom truststore.

You can pass these `-D` options to the CLI, as in this example:

```shell
java "-Djavax.net.ssl.trustStore=my-truststore.p12" "-Djavax.net.ssl.trustStorePassword=changeit" -jar quarkus-run.jar  admin credentials show --admin-username=admin --admin-password=admin --keycloak-url=https://keycloak.192.168.49.2.nip.io --show-secret --client-id wanaku-service
```

## Next steps

- [Authenticate the Wanaku CLI](authenticate.md): log in before you use the CLI with a protected server.
- [Inspect tools, resources, and prompts](capabilities.md): list what the connected MCP servers expose.
