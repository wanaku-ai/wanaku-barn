import { newId } from "../id.ts";
export interface Tool {
  name: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
}
export interface ToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}
export interface Message {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
}
export type Sender = (
  spec: import("../client").RequestSpec,
  inspect?: boolean,
  strict?: boolean,
) => Promise<unknown>;
export function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("Expected a JSON object");
  return value as Record<string, unknown>;
}
export function list(value: unknown, key: string): Record<string, unknown>[] {
  const root = object(value);
  const data = root.result ?? root.data ?? root;
  const values = Array.isArray(data) ? data : object(data)[key];
  return Array.isArray(values) ? values.map(object) : [];
}
export interface ConversationOptions {
  send: Sender;
  inference: string;
  mcp: string;
  namespace: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  parameters: Record<string, unknown>;
  tools: Tool[];
  signal: AbortSignal;
  update: (messages: Message[]) => void;
}
/** Continue until the model finishes, feeding every tool result into the next completion. */
export async function converse(
  history: Message[],
  options: ConversationOptions,
): Promise<Message[]> {
  const messages = [...history];
  const { send, signal, namespace } = options;
  for (let round = 0; round < 32; round++) {
    signal.throwIfAborted();
    const payload = object(
      await send(
        {
          method: "POST",
          url: `${options.inference.replace(/\/$/, "")}/v1/chat/completions`,
          token: options.apiKey,
          signal,
          namespace,
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ...options.parameters,
            model: options.model,
            stream: false,
            messages: [
              ...(options.systemPrompt
                ? [{ role: "system", content: options.systemPrompt }]
                : []),
              ...messages,
            ],
            ...(options.tools.length
              ? {
                  tools: options.tools.map((tool) => ({
                    type: "function",
                    function: {
                      name: tool.name,
                      description: tool.description,
                      parameters: tool.inputSchema ?? {
                        type: "object",
                        properties: {},
                      },
                    },
                  })),
                }
              : {}),
          }),
        },
        false,
        true,
      ),
    );
    signal.throwIfAborted();
    const choice = object(
      Array.isArray(payload.choices) ? payload.choices[0] : undefined,
    );
    const message = object(choice.message);
    const calls: ToolCall[] = Array.isArray(message.tool_calls)
      ? message.tool_calls.map((value) => {
          const call = object(value),
            fn = object(call.function);
          if (
            typeof call.id !== "string" ||
            typeof fn.name !== "string" ||
            typeof fn.arguments !== "string"
          )
            throw new Error("Invalid model tool call");
          return {
            id: call.id,
            type: "function",
            function: { name: fn.name, arguments: fn.arguments },
          };
        })
      : [];
    messages.push({
      role: "assistant",
      content: typeof message.content === "string" ? message.content : null,
      ...(calls.length ? { tool_calls: calls } : {}),
    });
    options.update([...messages]);
    if (!calls.length) return messages;
    for (const call of calls) {
      signal.throwIfAborted();
      if (!options.tools.some((tool) => tool.name === call.function.name))
        throw new Error(
          `Model requested unselected tool: ${call.function.name}`,
        );
      const args = object(JSON.parse(call.function.arguments || "{}"));
      const result = object(
        await send(
          {
            method: "POST",
            url: `${options.mcp.replace(/\/$/, "")}/${encodeURIComponent(namespace)}/mcp`,
            namespace,
            target: call.function.name,
            signal,
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              jsonrpc: "2.0",
              id: newId(),
              method: "tools/call",
              params: { name: call.function.name, arguments: args },
            }),
          },
          false,
          true,
        ),
      );
      signal.throwIfAborted();
      if (result.error) throw new Error(JSON.stringify(result.error));
      messages.push({
        role: "tool",
        name: call.function.name,
        tool_call_id: call.id,
        content: JSON.stringify(result.result ?? result),
      });
      options.update([...messages]);
    }
  }
  throw new Error(
    "Conversation reached 32 tool rounds. Send another message to continue.",
  );
}
