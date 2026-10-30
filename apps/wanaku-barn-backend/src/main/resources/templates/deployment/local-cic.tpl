java -jar camel-integration-capability-main-*-jar-with-dependencies.jar \
  --registration-url <registration-url> \
  --name {{systemName}} \
  --service-catalog {{catalogName}} \
  --service-catalog-system {{systemName}} \
  --mcp-port <mcp-port> \
  --fail-fast

wanaku forwards add --name {{systemName}} --service http://localhost:<mcp-port>/mcp --namespace default
