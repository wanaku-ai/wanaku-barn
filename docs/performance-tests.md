# Performance Tests

The standalone k6 scripts under `tests/load/` exercise an MCP router over SSE. Run them against a separately deployed Wanaku instance and downstream MCP server. Barn supplies management and persistence APIs and does not run the MCP request path.

Install k6 with the `k6/x/mcp` extension. The scripts currently target `http://localhost:8080/public/mcp/sse`; adjust that URL and the tool/resource names to match your test deployment before running:

```shell
k6 run --vus 10 --duration 30s tests/load/mcp-tools-invoke-sse.js
k6 run --vus 10 --duration 30s tests/load/mcp-resources-read-sse.js
```

#### Mock MCP Server Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.service.performance.delay` | `100` | Artificial delay in ms added to each tool/resource response. Set to `0` for pure throughput tests. |
| `wanaku.mcp.service.namespace` | `test` | Namespace the forward registers under. Set to `public` to expose tools/resources on the unauthenticated `/public/mcp/sse` endpoint. |
| `wanaku.service.registration.uri` | `http://localhost:8080` | Router URL for forward registration. |
| `wanaku.service.registration.mcp-forward-address` | `http://localhost:8181/mcp/sse` | SSE endpoint the router will use to reach this server. |

All properties can be overridden via `-D` flags on the command line.

**Important:** The `namespace` determines which SSE endpoint exposes the tools. If set to `test`, tools appear under an authenticated namespace (e.g., `/ns-9/mcp/sse`). Set to `public` for unauthenticated access at `/public/mcp/sse`, which is what the k6 scripts target.

#### Data Store

The router persists forwards in `~/.wanaku/barn/`. If you see stale data between runs, clear it:

```bash
rm -rf ~/.wanaku/barn/forward/{data,index}/*
rm -rf ~/.wanaku/barn/namespace/{data,index}/*
rm -rf ~/.wanaku/barn/tool/{data,index}/*
rm -rf ~/.wanaku/barn/resource/{data,index}/*
```

### 2. Full Baseline vs Patched Evaluation

For comparing main vs a feature branch through the MCP bridge, build and test each branch separately:

```bash
# 1. Build baseline from main
git checkout main
mvn package -pl apps/wanaku-barn-backend,tests/mcp-servers/wanaku-performance-test-mock-mcp -am -DskipTests -T1C -q
# Copy baseline jars to a safe location
cp -r apps/wanaku-barn-backend/target/quarkus-app /tmp/baseline-router
cp -r tests/mcp-servers/wanaku-performance-test-mock-mcp/target/quarkus-app /tmp/baseline-mock

# 2. Build patched from feature branch
git checkout my-feature-branch
mvn package -pl apps/wanaku-barn-backend,tests/mcp-servers/wanaku-performance-test-mock-mcp -am -DskipTests -T1C -q

# 3. Run baseline tests, then patched tests (same VU levels, same duration)
#    Save results to: $EVAL_DIR/baseline/tools-invoke-sse/test-summary-vus-*.json
#                     $EVAL_DIR/patched/tools-invoke-sse/test-summary-vus-*.json
#    (and similarly for resources-read-sse)

# 4. Generate comparison report
python3 tests/load/generate-perf-report.py --eval-dir $EVAL_DIR --test-scope all
```

## Report Generation

`generate-perf-report.py` produces a Markdown comparison report from k6 JSON summary files.

### Expected Directory Structure

```text
$EVAL_DIR/
├── baseline/
│   ├── tools-invoke-sse/
│   │   ├── test-summary-vus-1.json
│   │   ├── test-summary-vus-10.json
│   │   ├── vmstat-baseline-tools-invoke-sse.log    # optional
│   │   └── java-procs-baseline-tools-invoke-sse.log # optional
│   └── resources-read-sse/
│       └── test-summary-vus-*.json
└── patched/
    ├── tools-invoke-sse/
    │   └── test-summary-vus-*.json
    └── resources-read-sse/
        └── test-summary-vus-*.json
```

### Usage

```bash
python3 tests/load/generate-perf-report.py \
  --eval-dir /path/to/eval-dir \
  --test-scope all \
  --output /path/to/report.md
```

Options:

- `--eval-dir` (required): directory containing `baseline/` and `patched/` subdirectories
- `--test-scope`: `all` (default), `tools`, or `resources`
- `--output`: output file path (defaults to `$EVAL_DIR/perf-report.md`)

### Key Metrics

The report tracks these metrics per VU level:

| Metric | Direction | Description |
|--------|-----------|-------------|
| `mcp_request_duration` (avg, med, p90, p95, max) | Lower is better | End-to-end MCP request latency |
| `mcp_request_count` (rate, count) | Higher is better | Throughput |
| `mcp_request_errors` (rate, count) | Lower is better | Error count |
| `iterations` (rate, count) | Higher is better | Full iteration throughput |
| `iteration_duration` (avg, p95) | Higher is better | Full iteration time (includes all MCP calls per iteration) |
| `data_sent` / `data_received` (rate) | Higher is better | Network throughput |

The report uses indicators: green circle for >5% improvement, red circle for >10% regression.

## Writing Custom k6 Scripts

k6 scripts use the `k6/x/mcp` extension. Each iteration creates an SSE client, performs MCP operations, and the extension tracks `mcp_request_duration`, `mcp_request_count`, and `mcp_request_errors` automatically.

Template:

```javascript
import mcp from 'k6/x/mcp';

export default function () {
    const client = new mcp.SSEClient({
        base_url: 'http://localhost:8080/public/mcp/sse',
        timeout: 5
    });

    client.ping();

    // Your MCP operations here:
    // client.listAllTools()
    // client.callTool({ name: 'toolName', arguments: { key: 'value' } })
    // client.listAllResources()
    // client.readResource({ uri: 'resource-uri' })
}
```

Register custom scripts in `run-perf-test.sh` by adding to the `SUITES` map, or run them ad-hoc:

```bash
tests/load/run-perf-test.sh \
  --router-from /path/to/router.tar.gz \
  --test my-test ./my-script.js
```

## Troubleshooting

**Tools/resources not listed via SSE**: Check the namespace. The k6 scripts target `/public/mcp/sse`. If the forward registered with a non-public namespace, tools will only appear under the corresponding authenticated namespace endpoint (e.g., `/ns-9/mcp/sse`). Override with `-Dwanaku.mcp.service.namespace=public`.

**Stale forward registrations**: The router persists forwards in `~/.wanaku/barn/`. Clear the data store (see [Data Store](#data-store) above) and restart.

**High latency at 500+ VUs**: Expected. The SSE transport creates a new connection per iteration. At high concurrency, connection queuing dominates. The median latency stays low but P95 increases significantly.

**Mock MCP server config parse failure**: Do not pass `-Dwanaku.mcp.service.namespace=""` (empty string). Quarkus cannot parse this. Either omit the flag (uses default from `application.properties`) or set it to an explicit value like `public`.
