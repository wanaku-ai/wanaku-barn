import { useEffect, useRef, useState } from "react";
import {
  Button,
  Checkbox,
  Select,
  SelectItem,
  TextArea,
  TextInput,
  InlineNotification,
} from "@carbon/react";
import { validateSchema, type RequestSpec } from "./client";
import { JsonCode } from "./JsonCode";
import { newId } from "./id";

export type JsonObject = Record<string, unknown>;
export interface PanelProps {
  base: string;
  management: string;
  headers: string;
  onHeaders: (value: string) => void;
  namespace: string;
  namespaces: string[];
  onNamespace: (namespace: string) => void;
  send: (request: RequestSpec, inspect?: boolean) => Promise<unknown>;
}
export function NamespaceSelector({ protocol, namespace, namespaces, onNamespace }: {
  protocol: string;
  namespace: string;
  namespaces: string[];
  onNamespace: (namespace: string) => void;
}) {
  const [custom, setCustom] = useState("");
  return <>
    <Select id={`${protocol.toLowerCase()}-namespace`} labelText={`${protocol} namespace`} value={namespace}
      onChange={(event) => onNamespace(event.target.value)}>
      {Array.from(new Set([namespace, ...namespaces])).map((name) => <SelectItem key={name} value={name} text={name} />)}
    </Select>
    <div className="debugger-actions">
      <TextInput id={`${protocol.toLowerCase()}-custom-namespace`} labelText="Custom namespace"
        helperText="Use a namespace that is not listed."
        value={custom} onChange={(event) => setCustom(event.target.value)} />
      <Button kind="tertiary" disabled={!custom.trim()} onClick={() => onNamespace(custom.trim())}>Use namespace</Button>
    </div>
  </>;
}
function entries(value: unknown, key: string): JsonObject[] {
  if (typeof value !== "object" || value === null) return [];
  const obj = value as JsonObject;
  const data = obj.result ?? obj.data ?? obj;
  if (Array.isArray(data))
    return data.filter(
      (v): v is JsonObject => typeof v === "object" && v !== null,
    );
  if (typeof data !== "object" || data === null) return [];
  const document = (data as JsonObject).policy ?? data;
  const list = typeof document === "object" && document !== null
    ? (document as JsonObject)[key] : undefined;
  return Array.isArray(list)
    ? list.filter((v): v is JsonObject => typeof v === "object" && v !== null)
    : [];
}
function rpc(
  url: string,
  method: string,
  params: unknown,
  namespace: string,
  target?: string,
): RequestSpec {
  return {
    method: "POST",
    url,
    namespace,
    target,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: newId(),
      method,
      params,
    }),
  };
}
function schemaValues(
  properties: Record<string, JsonObject>,
  fields: Record<string, string>,
): JsonObject {
  const values: JsonObject = {};
  for (const [name, field] of Object.entries(properties)) {
    const value = fields[name];
    if (value === undefined || value === "") continue;
    switch (field.type) {
      case "number":
      case "integer":
        values[name] = Number(value);
        break;
      case "boolean":
        if (value !== "true" && value !== "false")
          throw new Error(`${name} must be true or false`);
        values[name] = value === "true";
        break;
      case "object":
      case "array":
        values[name] = JSON.parse(value);
        break;
      default:
        values[name] = value;
    }
  }
  return values;
}
export function McpPanel({ base, management, namespace, namespaces, onNamespace, send }: PanelProps) {
  const [kind, setKind] = useState("tools");
  const [items, setItems] = useState<JsonObject[]>([]);
  const [registry, setRegistry] = useState<JsonObject[]>([]);
  const [selected, setSelected] = useState("");
  const [args, setArgs] = useState("{}");
  const [rawMode, setRawMode] = useState(false);
  const [policies, setPolicies] = useState<JsonObject[] | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [headers, setHeaders] = useState("{}");
  const url = `${base.replace(/\/$/, "")}/${encodeURIComponent(namespace)}/mcp`;
  const item = items.find(
    (entry) => String(entry.name ?? entry.uri) === selected,
  );
  const schema = item?.inputSchema as JsonObject | undefined;
  const properties = schema?.properties as
    | Record<string, JsonObject>
    | undefined;
  async function discover() {
    const result = await send(rpc(url, `${kind}/list`, {}, namespace), false);
    setItems(entries(result, kind));
    setSelected("");
  }
  async function invoke() {
    try {
      let parameters: unknown = JSON.parse(args);
      if (
        !rawMode &&
        kind === "tools" &&
        properties &&
        Object.keys(properties).length
      ) {
        parameters = schemaValues(properties, fields);
      }
      const validation =
        kind === "tools" && schema ? validateSchema(schema, parameters) : [];
      if (validation.length) {
        setError(validation.join("; "));
        return;
      }
      setError("");
      const method =
        kind === "tools"
          ? "tools/call"
          : kind === "resources"
            ? "resources/read"
            : "prompts/get";
      const params =
        kind === "resources"
          ? { uri: selected }
          : { name: selected, arguments: parameters };
      const parsedHeaders: unknown = JSON.parse(headers);
      if (
        !parsedHeaders ||
        typeof parsedHeaders !== "object" ||
        Array.isArray(parsedHeaders) ||
        Object.values(parsedHeaders).some((value) => typeof value !== "string")
      )
        throw new Error("MCP headers must be string values");
      const request = rpc(url, method, params, namespace, selected);
      await send({
        ...request,
        headers: {
          ...request.headers,
          ...(parsedHeaders as Record<string, string>),
        },
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }
  return (
    <section aria-label="MCP debugger">
      <NamespaceSelector protocol="MCP" namespace={namespace} namespaces={namespaces} onNamespace={onNamespace} />
      <Select
        id="mcp-kind"
        labelText="MCP operation"
        value={kind}
        onChange={(e) => {
          setKind(e.target.value);
          setItems([]);
          setSelected("");
        }}
      >
        <SelectItem value="tools" text="Tools" />
        <SelectItem value="resources" text="Resources" />
        <SelectItem value="prompts" text="Prompts" />
      </Select>
      <div className="debugger-actions">
        <Button onClick={() => void discover()}>Discover {kind}</Button>
        <Button
          kind="secondary"
          onClick={async () => {
            setRegistry(
              entries(
                await send({
                  method: "GET",
                  url: `${management}/api/v1/${kind}?namespace=${encodeURIComponent(namespace)}`,
                  namespace,
                }, false),
                kind,
              ).filter((entry) => (entry.namespace ?? "default") === namespace),
            );
          }}
        >
          Load registry
        </Button>
      </div>
      <p>
        {items.length} protocol entries; {registry.length} registry entries.
      </p>
      {registry.map((entry) => {
        const name = String(entry.name ?? entry.uri);
        return (
          <p key={name}>
            {name}:{" "}
            {entry.enabled === false
              ? "disabled"
              : items.some(
                    (candidate) =>
                      String(candidate.name ?? candidate.uri) === name,
                  )
                ? "advertised"
                : "not advertised"}
          </p>
        );
      })}
      <Select
        id="mcp-target"
        labelText="MCP target"
        value={selected}
        onChange={(e) => {
          setSelected(e.target.value);
          setFields({});
        }}
      >
        <SelectItem value="" text="Select a discovered entry" />
        {items.map((entry) => {
          const name = String(entry.name ?? entry.uri);
          return <SelectItem key={name} value={name} text={name} />;
        })}
      </Select>
      {item?.description ? <p>{String(item.description)}</p> : null}
      <Checkbox
        id="raw-arguments"
        labelText="Use raw arguments JSON"
        checked={rawMode}
        onChange={(_, { checked }) => setRawMode(checked)}
      />
      {!rawMode && kind === "tools" && properties ? (
        Object.entries(properties).map(([name, field]) => (
          <TextInput
            key={name}
            id={`argument-${name}`}
            labelText={`${name} (${String(field.type ?? "JSON")})${Array.isArray(schema?.required) && schema.required.includes(name) ? " required" : ""}`}
            helperText={[
              field.description,
              field["x-mcp-header"]
                ? `Header mapping: ${String(field["x-mcp-header"])}`
                : undefined,
            ]
              .filter(Boolean)
              .join(" · ")}
            value={fields[name] ?? ""}
            onChange={(e) => setFields({ ...fields, [name]: e.target.value })}
          />
        ))
      ) : (
        <TextArea
          id="mcp-arguments"
          labelText="Arguments JSON"
          value={args}
          onChange={(e) => setArgs(e.target.value)}
        />
      )}
      <TextArea
        id="mcp-headers"
        labelText="MCP headers JSON (x-mcp-*)"
        helperText="Header injection can affect dispatch and policy. Values must be strings."
        value={headers}
        onChange={(e) => setHeaders(e.target.value)}
      />
      {error && (
        <InlineNotification
          kind="error"
          title="Invalid arguments"
          subtitle={error}
          lowContrast
          hideCloseButton
        />
      )}
      <Button disabled={!selected} onClick={() => void invoke()}>
        Run MCP operation
      </Button>
      <Button
        kind="secondary"
        onClick={async () => {
          const rules = entries(
            await send({
              method: "GET",
              url: `${management}/api/v1/action-policies`,
              namespace,
              target: selected,
            }, false),
            "rules",
          );
          setPolicies(
            rules.filter((rule) => {
              const selectors = (rule.selectors ?? {}) as JsonObject;
              const target = selectors.target_name as JsonObject | undefined;
              const operation =
                kind === "tools"
                  ? "tools/call"
                  : kind === "resources"
                    ? "resources/read"
                    : "prompts/get";
              if (selectors.namespace && selectors.namespace !== namespace)
                return false;
              if (selectors.operation && selectors.operation !== operation)
                return false;
              if (
                selectors.target_type &&
                selectors.target_type !== kind.slice(0, -1)
              )
                return false;
              if (!target) return true;
              const pattern = String(target.value);
              if (target.matcher === "exact") return selected === pattern;
              if (target.matcher === "prefix")
                return selected.startsWith(pattern);
              if (target.matcher === "glob") {
                const escaped = pattern
                  .replace(/[.+^${}()|[\]\\]/g, "\\$&")
                  .replace(/\*/g, ".*")
                  .replace(/\?/g, ".");
                return new RegExp(`^${escaped}$`).test(selected);
              }
              return false;
            }),
          );
        }}
      >
        Inspect matching policies
      </Button>
      {policies !== null && (
        <div aria-label="Matching policy candidates">
          <p>
            {policies.length
              ? `${policies.length} policy candidates; predicates and label selectors are evaluated by Wanaku.`
              : "No policy candidates: governed calls may be denied with no_match."}
          </p>
          <JsonCode value={policies} label="Matching policy JSON" />
        </div>
      )}
      <p>
        The default policy posture is no_match: deny. Configure an explicit
        allow rule before invoking a target. Inspect the run audit for policy
        and evaluator denial reasons.
      </p>
    </section>
  );
}
function AgentCatalogStatus({ loading, error, count, namespace }: {
  loading: boolean;
  error: string;
  count: number;
  namespace: string;
}) {
  if (loading) return <p role="status">Loading registered agents…</p>;
  if (error) return <InlineNotification title="Unable to load agents" subtitle={error} kind="error" hideCloseButton />;
  if (count === 0) return <p role="status">No registered agents in namespace {namespace}. Choose another namespace or enter an agent name.</p>;
  return null;
}

// Catalog loading stays independent of inspector selection and ignores stale responses.
function useRegisteredAgents(management: string, namespace: string, send: PanelProps["send"]) {
  const [agents, setAgents] = useState<JsonObject[]>([]);
  const [agentsLoading, setAgentsLoading] = useState(true);
  const [agentsError, setAgentsError] = useState("");
  const [reload, setReload] = useState(0);
  const sendRef = useRef(send);
  useEffect(() => { sendRef.current = send; }, [send]);
  useEffect(() => {
    let active = true;
    setAgentsLoading(true);
    setAgentsError("");
    setAgents([]);
    void (async () => {
      try {
        const response = await sendRef.current({
          method: "GET",
          url: `${management.replace(/\/$/, "")}/api/v1/agents?namespace=${encodeURIComponent(namespace)}`,
          namespace,
        }, false);
        if (!response || typeof response !== "object") throw new Error("Unable to load registered agents. Check the management endpoint and run history.");
        const failure = (response as JsonObject).error;
        if (failure) throw new Error(typeof failure === "string" ? failure : JSON.stringify(failure));
        if (active) setAgents(entries(response, "agents").filter((entry) => (entry.namespace ?? "default") === namespace));
      } catch (cause) {
        if (active) setAgentsError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        if (active) setAgentsLoading(false);
      }
    })();
    return () => { active = false; };
  }, [management, namespace, reload]);
  return { agents, agentsLoading, agentsError, setReload };
}
function responseTaskId(response: unknown): string | undefined {
  if (!response || typeof response !== "object") return undefined;
  const result = (response as JsonObject).result;
  if (!result || typeof result !== "object") return undefined;
  const task = (result as JsonObject).task ?? result;
  if (!task || typeof task !== "object") return undefined;
  const id = (task as JsonObject).id;
  return typeof id === "string" ? id : undefined;
}

export function A2aPanel({
  base,
  management,
  namespace,
  namespaces,
  onNamespace,
  headers,
  onHeaders,
  send,
}: PanelProps) {
  const [agent, setAgent] = useState("");
  const [task, setTask] = useState("");
  const [message, setMessage] = useState("");
  const [raw, setRaw] = useState("");
  const [card, setCard] = useState<unknown>(null);
  const [error, setError] = useState("");
  const [proxy, setProxy] = useState("");
  const { agents, agentsLoading, agentsError, setReload } = useRegisteredAgents(management, namespace, send);
  const url =
    proxy ||
    `${base.replace(/\/$/, "")}/${encodeURIComponent(namespace)}/a2a/${encodeURIComponent(agent)}`;
  async function action(method: string, params: unknown) {
    let selectedParams = params;
    try {
      if (raw && method === "SendMessage")
        selectedParams = JSON.parse(raw) as unknown;
      setError("");
    } catch {
      setError("Message params must be valid JSON");
      return;
    }
    const request = rpc(url, method, selectedParams, namespace, agent);
    const response = await send(request);
    const taskId = responseTaskId(response);
    if (taskId) setTask(taskId);
  }
  return (
    <section aria-label="A2A debugger">
      <NamespaceSelector protocol="A2A" namespace={namespace} namespaces={namespaces} onNamespace={onNamespace} />
      <Button kind="secondary" disabled={agentsLoading} onClick={() => setReload((value) => value + 1)}>
        Load agents
      </Button>
      <AgentCatalogStatus loading={agentsLoading} error={agentsError} count={agents.length} namespace={namespace} />
      <Select
        id="registered-agent"
        labelText="Registered agent"
        disabled={agentsLoading || agents.length === 0}
        value={agent}
        onChange={(e) => {
          setAgent(e.target.value);
          setTask("");
          setCard(null);
          const found = agents.find((entry) => entry.name === e.target.value);
          setProxy(typeof found?.proxyUrl === "string" ? found.proxyUrl : "");
        }}
      >
        <SelectItem value="" text="Select registered agent" />
        {agents.map((entry) => (
          <SelectItem
            key={String(entry.name)}
            value={String(entry.name)}
            text={String(entry.name)}
          />
        ))}
      </Select>
      <TextInput
        id="agent-proxy"
        labelText="Agent proxy URL override"
        value={proxy}
        onChange={(e) => setProxy(e.target.value)}
      />
      <TextInput
        id="a2a-agent"
        labelText="Agent name"
        value={agent}
        onChange={(e) => {
          setAgent(e.target.value);
          setProxy("");
          setTask("");
        }}
      />
      <Button
        disabled={!agent}
        onClick={async () =>
          setCard(
            await send({
              method: "GET",
              url: `${url}/.well-known/agent-card.json`,
              namespace,
              target: agent,
            }),
          )
        }
      >
        Discover agent card
      </Button>
      <TextArea
        id="a2a-headers"
        labelText="A2A headers JSON"
        value={headers}
        onChange={(e) => onHeaders(e.target.value)}
      />
      <JsonCode value={card ?? "No agent card loaded"} label="Agent card capabilities" />
      <TextArea
        id="a2a-message"
        labelText="Message"
        value={message}
        onChange={(e) => setMessage(e.target.value)}
      />
      <Button
        disabled={!agent || (!message && !raw)}
        onClick={() =>
          void action("SendMessage", {
            message: {
              messageId: newId(),
              role: "user",
              parts: [{ kind: "text", text: message }],
            },
          })
        }
      >
        SendMessage
      </Button>
      <TextArea
        id="a2a-raw"
        labelText="SendMessage params JSON override (optional)"
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
      />
      {error && (
        <InlineNotification
          title="Invalid message"
          subtitle={error}
          kind="error"
          hideCloseButton
        />
      )}
      <TextInput
        id="a2a-task"
        labelText="Task ID"
        value={task}
        onChange={(e) => setTask(e.target.value)}
      />
      <div className="debugger-actions">
        {[
          ["GetTask", "GetTask"],
          ["CancelTask", "CancelTask"],
        ].map(([method, label]) => (
          <Button
            kind="secondary"
            key={method}
            disabled={!agent || !task}
            onClick={() => void action(method, { id: task })}
          >
            {label}
          </Button>
        ))}
      </div>
      <p>Wanaku forwards SendMessage, GetTask, and CancelTask JSON-RPC operations. Streaming and push notifications are unsupported.</p>
    </section>
  );
}
export function InferencePanel({
  base,
  namespace,
  headers,
  onHeaders,
  send,
}: PanelProps) {
  const [body, setBody] = useState(
    '{"model":"","messages":[{"role":"user","content":"Hello"}],"stream":false}',
  );
  const [error, setError] = useState("");
  const [intercept, setIntercept] = useState("");
  const url = base.replace(/\/$/, "");
  return (
    <section aria-label="Inference debugger">
      <Button
        onClick={() =>
          void send({ method: "GET", url: `${url}/v1/models`, namespace })
        }
      >
        List models
      </Button>
      <TextArea
        id="inference-headers"
        labelText="Inference headers JSON"
        value={headers}
        onChange={(e) => onHeaders(e.target.value)}
      />
      <TextArea
        id="inference-body"
        labelText="Chat completion JSON (messages and tools)"
        rows={10}
        value={body}
        onChange={(e) => setBody(e.target.value)}
      />
      <TextInput
        id="intercept-id"
        labelText="Request ID (optional)"
        helperText="Optional x-request-id correlation. Inspect response tool_calls arguments for the wk-… ID injected by wanaku_intercept."
        value={intercept}
        onChange={(e) => setIntercept(e.target.value)}
      />
      {error && (
        <InlineNotification
          kind="error"
          title="Invalid completion"
          subtitle={error}
          hideCloseButton
        />
      )}
      <Button
        onClick={() => {
          try {
            const parsed: unknown = JSON.parse(body);
            if (
              typeof parsed !== "object" ||
              parsed === null ||
              Array.isArray(parsed)
            )
              throw new Error("Expected a JSON object");
            if ((parsed as JsonObject).stream === true)
              throw new Error("Set stream to false for response inspection");
            setError("");
            void send({
              method: "POST",
              url: `${url}/v1/chat/completions`,
              namespace,
              body,
              headers: {
                "Content-Type": "application/json",
                ...(intercept ? { "x-request-id": intercept } : {}),
              },
            });
          } catch (cause) {
            setError(String(cause));
          }
        }}
      >
        Create completion
      </Button>
    </section>
  );
}
