# Wanaku Core

## Overview

Core library modules providing fundamental functionality for the Wanaku ecosystem.

## Purpose

This directory contains essential libraries and infrastructure used throughout Wanaku:

- MCP protocol client implementation
- Service API interfaces
- Common utilities

## Sub-Modules

### core-mcp-client

MCP protocol client implementation for communicating with downstream MCP servers.

### core-services-api

Service API interfaces that define the contracts for tools, resources, namespaces, MCP servers, and other domain services.

### core-util

Common utilities, constants, and helper classes used across modules.

## Usage

These modules are library dependencies consumed by other Wanaku components. They are not deployed standalone.

## Related Documentation

- [Architecture Overview](../docs/architecture.md)
- [Wanaku Router Internals](../docs/wanaku-router-internals.md)
- [Contributing Guide](../CONTRIBUTING.md)
