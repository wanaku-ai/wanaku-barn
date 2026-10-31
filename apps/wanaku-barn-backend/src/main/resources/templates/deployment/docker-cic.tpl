docker run -d \
  -e REGISTRATION_URL=<registration-url> \
  -e SERVICE_NAME={{systemName}} \
  -e SERVICE_CATALOG={{catalogName}} \
  -e SERVICE_CATALOG_SYSTEM={{systemName}} \
  -e WANAKU_REGISTER=true \
  -e WANAKU_URL=<wanaku-url> \
  -e WANAKU_NAMESPACE=default \
  -e MCP_ADDRESS=http://localhost:<mcp-port>/mcp \
  -p <mcp-port>:9090 \
  quay.io/wanaku/camel-integration-capability:latest
