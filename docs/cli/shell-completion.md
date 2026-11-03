---
title: Shell completion
description: Generate bash and zsh completion scripts for the Wanaku CLI, install them for your shell, and complete commands, subcommands, and options.
---

# Shell completion

The Wanaku CLI can generate completion scripts for bash and zsh. With completion, the shell completes commands, subcommands, and options when you press Tab.

Wanaku provides shell completion support for bash and zsh, enabling tab-completion for all commands, subcommands, and their options. This significantly improves the command-line experience by reducing typing and helping discover available commands and options.

## Generating Completion Scripts

To generate a completion script, use the `wanaku completion generate` command:

```shell
# Generate completion script and output to stdout
wanaku completion generate

# Save completion script to a file
wanaku completion generate --output ~/.wanaku_completion
```

The generated script includes completion support for:

- All parent commands (namespaces, tools, resources, forwards, etc.)
- All subcommands (namespaces list, tools list, etc.)
- All command options (--help, --verbose, --plain, command-specific options)
- Automatic detection of bash vs zsh shell

## Quick Setup for Current Session Only

If you want to enable completion for just your current terminal session without making permanent changes:

```shell
# One-liner for bash or zsh (works on both Linux and macOS)
source <(wanaku completion generate)

# Alternative using eval (also works on both bash and zsh)
eval "$(wanaku completion generate)"
```

This generates and immediately sources the completion script in your current shell. Completion will be active until you close the terminal, without creating any files or modifying your shell configuration files.

This is useful for:

- Testing completion before permanent installation
- Temporary/one-time use
- Environments where you don't want to modify shell configuration

## Installing Completion on Linux

### For Bash

1. Generate the completion script to a standard location:

```shell
wanaku completion generate --output /etc/bash_completion.d/wanaku_completion
```

1. Add the following line to your `~/.bashrc`:

```shell
source /etc/bash_completion.d/wanaku_completion
```

1. Reload your shell:

```shell
source ~/.bashrc
```

Alternatively, for user-specific installation:

```shell
wanaku completion generate --output ~/.wanaku_completion
echo "source ~/.wanaku_completion" >> ~/.bashrc
source ~/.bashrc
```

### For Zsh

1. Generate the completion script:

```shell
mkdir -p ~/.zsh/completions
wanaku completion generate --output ~/.zsh/completions/_wanaku
```

1. Add the following lines to your `~/.zshrc`:

```shell
autoload -U +X bashcompinit && bashcompinit
source ~/.zsh/completions/_wanaku
```

1. Reload your shell:

```shell
source ~/.zshrc
```

## Installing Completion on macOS

### For Zsh (Default on macOS Catalina and later)

1. Generate the completion script:

```shell
mkdir -p ~/.zsh/completions
wanaku completion generate --output ~/.zsh/completions/_wanaku
```

1. Add the following lines to your `~/.zshrc`:

```shell
autoload -U +X bashcompinit && bashcompinit
source ~/.zsh/completions/_wanaku
```

1. Reload your shell:

```shell
source ~/.zshrc
```

### For Bash (If using bash on macOS)

1. Generate the completion script:

```shell
wanaku completion generate --output /usr/local/etc/bash_completion.d/wanaku
```

1. Add the following line to your `~/.bash_profile`:

```shell
source /usr/local/etc/bash_completion.d/wanaku
```

1. Reload your shell:

```shell
source ~/.bash_profile
```

## Using Shell Completion

Once installed, you can use tab-completion with the Wanaku CLI:

```shell
# Tab-complete commands
wanaku <TAB>
# Shows: admin, auth, completion, configure, datastores, evaluators, forwards, man, mcp, namespaces, prompts, resources, service, start, tools

# Tab-complete subcommands
wanaku namespaces <TAB>
# Shows: cleanup, create, delete, list, show

# Tab-complete options
wanaku tools list --<TAB>
# Shows: --help, --host, --plain, --verbose

# Tab-complete after partial input
wanaku name<TAB>
# Completes to: wanaku namespaces
```

## Troubleshooting

If completion doesn't work after installation:

1. **Verify the script was sourced:** Check that your shell configuration file (`.bashrc`, `.zshrc`, or `.bash_profile`) contains the source command and was reloaded.

2. **Check shell detection:** The completion script automatically detects whether you're using bash or zsh. Verify you're using a supported shell:

   ```shell
   echo $BASH_VERSION  # For bash
   echo $ZSH_VERSION   # For zsh
   ```

3. **Manually source the script:** Try sourcing the completion script directly:

   ```shell
   source ~/.wanaku_completion
   ```

4. **Regenerate the script:** If you've updated Wanaku and new commands aren't appearing, regenerate the completion script:

   ```shell
   wanaku completion generate --output ~/.wanaku_completion
   source ~/.wanaku_completion
   ```

## Limitations

- **PowerShell:** Shell completion is not currently supported for PowerShell on Windows. Users on Windows should use WSL (Windows Subsystem for Linux) with bash or zsh for completion support.
- **Fish shell:** Fish shell is not supported by picocli 4.7.7. Only bash and zsh are supported.

## Next steps

- [Install the Wanaku CLI](install.md): install the CLI before you add completion.
- [Use the Wanaku CLI](index.md): find the other CLI pages.
