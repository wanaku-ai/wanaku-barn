import { useEffect, useRef, useState } from "react";
import {
  Accordion,
  AccordionItem,
  Button,
  Checkbox,
  ComboBox,
  InlineLoading,
  InlineNotification,
  TextArea,
  TextInput,
  Tile,
} from "@carbon/react";
import ReactMarkdown from "react-markdown";
import { JsonCode } from "./JsonCode";
import { newId } from "./id";
import { NamespaceSelector, type PanelProps } from "./panels";
import {
  converse,
  list,
  object,
  type Message,
  type Sender,
  type Tool,
} from "./chat/conversation";
import {
  configKey,
  loadConfig,
  persistConfig,
  rememberApiKey,
  rememberKey,
  type ChatConfig,
} from "./chat/config";
import { validateExtraLlmParameters } from "./chat/parameters";

interface Props extends Omit<PanelProps, "send"> {
  mcp: string;
  send: Sender;
}
export function LlmChatPanel({
  base,
  mcp,
  namespace,
  namespaces,
  onNamespace,
  send,
}: Props) {
  const [config, setConfig] = useState(() => loadConfig(localStorage));
  const [remember, setRemember] = useState(
    () => localStorage.getItem(rememberKey) === "true",
  );
  const [rememberApi, setRememberApi] = useState(
    () =>
      localStorage.getItem(rememberKey) === "true" &&
      localStorage.getItem(rememberApiKey) === "true",
  );
  const [models, setModels] = useState<string[]>([]);
  const [tools, setTools] = useState<Tool[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const selectionKey = `wanaku-debugger-chat-tools:${mcp}:${namespace}`;
  const [messages, setMessages] = useState<Message[]>([]);
  const [prompt, setPrompt] = useState("");
  const [running, setRunning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const controller = useRef<AbortController | null>(null);
  const discovery = useRef<AbortController | null>(null);
  const sender = useRef(send);
  useEffect(() => {
    sender.current = send;
  }, [send]);
  const initialNamespace = useRef(false);
  useEffect(() => {
    if (initialNamespace.current) return;
    initialNamespace.current = true;
    const saved = localStorage.getItem("wanaku-debugger-chat-namespace");
    if (saved && localStorage.getItem(rememberKey) === "true")
      onNamespace(saved);
  }, [onNamespace]);
  useEffect(() => {
    const reset = () => {
      controller.current?.abort();
      discovery.current?.abort();
      setMessages([]);
      setModels([]);
      setTools([]);
      setSelected([]);
    };
    reset();
    if (localStorage.getItem(rememberKey) === "true") {
      try {
        const names: unknown = JSON.parse(
          localStorage.getItem(selectionKey) ?? "[]",
        );
        if (Array.isArray(names))
          setSelected(
            names.filter((name): name is string => typeof name === "string"),
          );
      } catch {
        /* Ignore invalid stored selection. */
      }
    }
    return () => {
      controller.current?.abort();
      discovery.current?.abort();
    };
  }, [namespace, mcp, base, selectionKey]);
  useEffect(() => {
    const sync = (event: StorageEvent) => {
      if (
        event.key &&
        ![rememberKey, rememberApiKey, configKey].includes(event.key)
      )
        return;
      setRemember(localStorage.getItem(rememberKey) === "true");
      setRememberApi(
        localStorage.getItem(rememberKey) === "true" &&
          localStorage.getItem(rememberApiKey) === "true",
      );
    };
    window.addEventListener("storage", sync);
    return () => window.removeEventListener("storage", sync);
  }, []);
  useEffect(() => {
    if (!remember) {
      localStorage.removeItem("wanaku-debugger-chat-namespace");
    }
    if (!remember)
      for (const key of Object.keys(localStorage))
        if (key.startsWith("wanaku-debugger-chat-tools:"))
          localStorage.removeItem(key);
  }, [remember]);
  function change(key: keyof ChatConfig, value: string) {
    const next = { ...config, [key]: value };
    setConfig(next);
    persistConfig(localStorage, next);
  }
  async function discover() {
    discovery.current?.abort();
    const abort = new AbortController();
    discovery.current = abort;
    setLoading(true);
    setError("");
    try {
      const catalog = await sender.current(
        {
          method: "GET",
          url: `${base.replace(/\/$/, "")}/v1/models`,
          token: config.apiKey,
          namespace,
          signal: abort.signal,
        },
        false,
        true,
      );
      abort.signal.throwIfAborted();
      setModels(
        list(catalog, "models").flatMap((entry) =>
          typeof entry.id === "string" ? [entry.id] : [],
        ),
      );
      const result = await sender.current(
        {
          method: "POST",
          url: `${mcp.replace(/\/$/, "")}/${encodeURIComponent(namespace)}/mcp`,
          namespace,
          signal: abort.signal,
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            jsonrpc: "2.0",
            id: newId(),
            method: "tools/list",
            params: {},
          }),
        },
        false,
        true,
      );
      abort.signal.throwIfAborted();
      setTools(
        list(result, "tools").flatMap((entry) =>
          typeof entry.name === "string"
            ? [
                {
                  name: entry.name,
                  description:
                    typeof entry.description === "string"
                      ? entry.description
                      : undefined,
                  inputSchema: entry.inputSchema
                    ? object(entry.inputSchema)
                    : undefined,
                },
              ]
            : [],
        ),
      );
    } catch (cause) {
      if (!abort.signal.aborted)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      if (discovery.current === abort) setLoading(false);
    }
  }
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    try {
      validateExtraLlmParameters(config.parameters);
      const parameters = config.parameters.trim()
        ? object(JSON.parse(config.parameters))
        : {};
      if (parameters.stream === true)
        throw new Error("Streaming is unsupported. Set stream to false.");
      const abort = new AbortController();
      controller.current = abort;
      const history: Message[] = [
        ...messages,
        { role: "user", content: prompt },
      ];
      setMessages(history);
      setPrompt("");
      setRunning(true);
      await converse(history, {
        send: sender.current,
        inference: base,
        mcp,
        namespace,
        apiKey: config.apiKey,
        model: config.model,
        systemPrompt: config.systemPrompt,
        parameters,
        tools: tools.filter((tool) => selected.includes(tool.name)),
        signal: abort.signal,
        update: setMessages,
      });
    } catch (cause) {
      if (!controller.current?.signal.aborted)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setRunning(false);
    }
  }
  return (
    <section aria-label="LLM Chat debugger">
      <p>
        Chat with an inference model and execute its selected MCP tools. Inspect
        each exchange in run history.
      </p>
      <Accordion>
        <AccordionItem title="LLM settings">
          <TextInput
            id="chat-api-key"
            type="password"
            autoComplete="off"
            labelText="Inference API key"
            value={config.apiKey}
            disabled={running}
            onChange={(event) => change("apiKey", event.target.value)}
            helperText="Separate from the connection bearer token. Leave empty for a local provider without authentication."
          />
          <Checkbox
            id="chat-remember"
            labelText="Remember LLM settings in this browser"
            checked={remember}
            onChange={(_, { checked }) => {
              setRemember(checked);
              localStorage.setItem(rememberKey, String(checked));
              if (checked) {
                localStorage.setItem(selectionKey, JSON.stringify(selected));
                localStorage.setItem(
                  "wanaku-debugger-chat-namespace",
                  namespace,
                );
              }
              if (!checked) {
                setRememberApi(false);
                localStorage.removeItem(rememberApiKey);
              }
              persistConfig(localStorage, config);
            }}
          />
          <Checkbox
            id="chat-remember-api"
            labelText="Also remember the inference API key"
            disabled={!remember}
            checked={rememberApi}
            onChange={(_, { checked }) => {
              setRememberApi(checked);
              localStorage.setItem(rememberApiKey, String(checked));
              persistConfig(localStorage, config);
            }}
          />
          {rememberApi && (
            <InlineNotification
              kind="warning"
              title="API key stored in browser"
              subtitle="Scripts and extensions on this device can read the saved key."
              hideCloseButton
              lowContrast
            />
          )}
          <TextArea
            id="chat-system"
            labelText="System prompt"
            value={config.systemPrompt}
            disabled={running}
            onChange={(event) => change("systemPrompt", event.target.value)}
          />
          <TextArea
            id="chat-parameters"
            labelText="Extra LLM parameters JSON"
            placeholder='{"temperature":0.7,"max_tokens":400}'
            value={config.parameters}
            disabled={running}
            onChange={(event) => change("parameters", event.target.value)}
          />
        </AccordionItem>
      </Accordion>
      <NamespaceSelector
        protocol="LLM Chat"
        namespace={namespace}
        namespaces={namespaces}
        onNamespace={(value) => {
          onNamespace(value);
          if (localStorage.getItem(rememberKey) === "true")
            localStorage.setItem("wanaku-debugger-chat-namespace", value);
        }}
      />
      <Button
        kind="secondary"
        disabled={loading || running}
        onClick={() => void discover()}
      >
        Load models and tools
      </Button>
      {loading && <InlineLoading description="Loading models and tools…" />}
      <ComboBox
        id="chat-model"
        titleText="LLM model"
        items={models}
        allowCustomValue
        selectedItem={config.model}
        disabled={running}
        onChange={(event) =>
          change("model", event.selectedItem ?? event.inputValue ?? "")
        }
      />
      <fieldset className="debugger-chat-tools" disabled={running}>
        <legend>MCP tools available to the model</legend>
        {!tools.length && (
          <p>
            No tools loaded. Load models and tools for this namespace, or chat
            without tools.
          </p>
        )}
        {tools.map((tool) => (
          <Checkbox
            key={tool.name}
            id={`chat-tool-${tool.name}`}
            labelText={
              tool.description
                ? `${tool.name} — ${tool.description}`
                : tool.name
            }
            checked={selected.includes(tool.name)}
            onChange={(_, { checked }) => {
              const next = checked
                ? [...selected, tool.name]
                : selected.filter((name) => name !== tool.name);
              setSelected(next);
              if (localStorage.getItem(rememberKey) === "true")
                localStorage.setItem(selectionKey, JSON.stringify(next));
            }}
          />
        ))}
      </fieldset>
      <div
        className="debugger-chat-transcript"
        role="log"
        aria-label="Chat messages"
        aria-live="polite"
      >
        {!messages.length && (
          <p>No messages yet. Choose a model and send a prompt to begin.</p>
        )}
        {messages.map((message, index) => (
          <Tile key={index}>
            <h3>
              {message.role === "tool"
                ? `Tool: ${message.name}`
                : message.role === "user"
                  ? "You"
                  : "Assistant"}
            </h3>
            {message.content &&
              (message.role === "tool" ? (
                <JsonCode
                  value={message.content}
                  label={`Tool result: ${message.name}`}
                />
              ) : message.role === "assistant" ? (
                <div className="debugger-chat-markdown">
                  <ReactMarkdown>{message.content}</ReactMarkdown>
                </div>
              ) : (
                <p>{message.content}</p>
              ))}
            {message.tool_calls?.map((call) => (
              <div key={call.id}>
                <p>Calling {call.function.name}</p>
                <JsonCode
                  value={call.function.arguments}
                  label={`Tool arguments: ${call.function.name}`}
                />
              </div>
            ))}
          </Tile>
        ))}
      </div>
      {error && (
        <InlineNotification
          kind="error"
          title="Chat could not continue"
          subtitle={error}
          hideCloseButton
          lowContrast
        />
      )}
      <form onSubmit={(event) => void submit(event)}>
        <TextArea
          id="chat-prompt"
          labelText="Message"
          rows={3}
          value={prompt}
          disabled={running}
          onChange={(event) => setPrompt(event.target.value)}
        />
        <div className="debugger-actions">
          <Button
            type="submit"
            disabled={running || !prompt.trim() || !config.model.trim()}
          >
            Send message
          </Button>
          <Button
            kind="secondary"
            disabled={!running}
            onClick={() => controller.current?.abort()}
          >
            Stop generation
          </Button>
          <Button
            kind="ghost"
            disabled={running || !messages.length}
            onClick={() => {
              setMessages([]);
              setError("");
            }}
          >
            Clear conversation
          </Button>
        </div>
      </form>
      {running && <InlineLoading description="Waiting for model and tools…" />}
    </section>
  );
}
