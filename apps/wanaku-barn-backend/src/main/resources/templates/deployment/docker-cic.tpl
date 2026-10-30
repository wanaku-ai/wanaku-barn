docker run -d \
  -e REGISTRATION_URL=<registration-url> \
  -e SERVICE_NAME={{systemName}} \
  -e SERVICE_CATALOG={{catalogName}} \
  -e SERVICE_CATALOG_SYSTEM={{systemName}} \
  -p <mcp-port>:9090 \
  quay.io/wanaku/camel-integration-capability:latest

wanaku forwards add --name {{systemName}} --service http://localhost:<mcp-port>/mcp --namespace default
