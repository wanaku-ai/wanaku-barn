---
title: Barn backend internals
description: "Learn how the Barn backend is built: its Quarkus REST endpoints, Infinispan persistence, service catalog logic, and management APIs."
---

# Wanaku Barn Internals

Barn provides persistence, service catalog management, service templates, semantic router definitions, audit history, and administration APIs. It uses Quarkus REST endpoints and Infinispan persistence.

The MCP routing engine is [Wanaku](https://github.com/wanaku-ai/wanaku), a Rust-based router. In hybrid deployments, Wanaku handles MCP requests and proxies Barn management operations to this backend. Downstream MCP endpoints are configured as Wanaku forwards.

## Related Documentation

- [Persistence](persistence-internals.md)
- [Architecture Overview](../get-started/architecture.md)
- [Configuration Guide](../reference/configuration.md)
- [Service Catalogs](../manage/service-catalogs.md)
