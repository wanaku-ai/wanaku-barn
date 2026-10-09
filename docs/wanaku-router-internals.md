# Wanaku Barn Internals

Barn provides persistence, service catalog management, service templates, semantic router definitions, audit history, and administration APIs. It uses Quarkus REST endpoints and Infinispan persistence.

The MCP routing engine is [Wanaku](https://github.com/wanaku-ai/wanaku), a Rust-based router. In hybrid deployments, Wanaku handles MCP requests and proxies Barn management operations to this backend. Downstream MCP endpoints are configured as Wanaku forwards.

## Related Documentation

- [Persistence](internals-persistence.md)
- [Architecture Overview](architecture.md)
- [Configuration Guide](configurations.md)
- [Service Catalogs](service-catalogs.md)
