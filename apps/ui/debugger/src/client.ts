import Ajv from 'ajv';
import { newId } from './id.ts';

export interface RequestSpec {
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  namespace?: string;
  target?: string;
  token?: string;
  signal?: AbortSignal;
}
export interface Run {
  id: string;
  startedAt: string;
  durationMs: number;
  request: RequestSpec;
  status: number | null;
  responseHeaders: Record<string, string>;
  response: string;
  outcome: 'success' | 'error';
  summary: string;
  error?: string;
  audit: unknown[];
  auditUrl?: string;
  auditError?: string;
}

const sensitive = /authorization|cookie|token|secret|password|credential|api[-_]?key/i;
const mask = '[REDACTED]';
const credentialKey = (key: string) => sensitive.test(key)
  && !/^(?:(?:prompt|completion|input|output|total|cached)_tokens|token_usage|token_count|tokenUsage|tokenCount)$/i.test(key);

/** Redact named credentials recursively, including JSON embedded in strings. */
export function redact<T>(value: T, secrets: string[] = []): T {
  if (Array.isArray(value)) return value.map(item => redact(item, secrets)) as T;
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) =>
      [key, credentialKey(key) ? mask : redact(item, secrets)])) as T;
  }
  if (typeof value !== 'string') return value;
  let result: string = value;
  try {
    const parsed: unknown = JSON.parse(result);
    if (parsed && typeof parsed === 'object') result = JSON.stringify(redact(parsed, secrets));
  } catch { /* Raw text and URLs are also supported. */ }
  result = result.replace(/(Bearer\s+)[^\s"'<>]+/gi, `$1${mask}`)
    .replace(/([?&](?:[^=&]*(?:token|secret|password|credential|api[-_]?key)[^=&]*)=)[^&#\s]*/gi, `$1${mask}`)
    .replace(/(https?:\/\/)[^/@\s]+:[^/@\s]+@/gi, `$1${mask}@`);
  for (const secret of secrets.filter(Boolean)) {
    result = result.split(secret).join(mask).split(encodeURIComponent(secret)).join(mask);
  }
  return result as T;
}

function requestSecrets(spec: RequestSpec, token?: string): string[] {
  const found = token ? [token] : [];
  const visit = (value: unknown): void => {
    if (value && typeof value === 'object') {
      for (const [key, item] of Object.entries(value)) {
        if (credentialKey(key) && typeof item === 'string') found.push(item, item.replace(/^(?:Bearer|Basic)\s+/i, ''));
        else visit(item);
      }
    }
  };
  visit(spec.headers);
  try { visit(JSON.parse(spec.body || '')); } catch { /* Only structured body keys can be classified. */ }
  try {
    const url = new URL(spec.url, 'http://localhost');
    for (const [key, value] of url.searchParams) if (credentialKey(key)) found.push(value);
    if (url.password) found.push(decodeURIComponent(url.password));
  } catch { /* Fetch will report invalid URLs. */ }
  return found;
}

export function defaultEndpoints(base: string = window.location.href) {
  const endpoint = (port: string) => {
    const url = new URL(base);
    url.port = port;
    return url.origin;
  };
  return {
    mcp: endpoint('8081'), inference: endpoint('8083'), a2a: endpoint('8084'),
    namespaces: '/api/v1/namespaces', tools: '/api/v1/tools', resources: '/api/v1/resources',
    prompts: '/api/v1/prompts', agents: '/api/v1/agents', policies: '/api/v1/action-policies',
    audit: '/api/v1/audit/events',
  };
}

export async function managementFetch(url: string, token?: string, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {}, signal });
  if (!response.ok) throw new Error(`Management API: HTTP ${response.status}`);
  const payload = await response.json();
  if (payload?.error) throw new Error(typeof payload.error === 'string' ? payload.error : JSON.stringify(payload.error));
  return payload && typeof payload === 'object' && 'data' in payload ? payload.data : payload;
}

function summarise(status: number, body: string): { outcome: Run['outcome']; summary: string } {
  try {
    const payload = JSON.parse(body);
    const error = payload.error;
    const reason = error?.data?.reason_code || error?.reason_code || payload.reason_code;
    if (reason) return { outcome: 'error', summary: `Governance: ${reason} — ${error?.data?.safe_message || error?.safe_message || payload.safe_message || error?.message || payload.message || 'Request denied'}` };
    if (error) return { outcome: 'error', summary: `JSON-RPC ${error.code ?? ''}: ${error.message || JSON.stringify(error)}` };
  } catch { /* HTTP responses need not be JSON. */ }
  return status >= 200 && status < 300
    ? { outcome: 'success', summary: 'Success' }
    : { outcome: 'error', summary: `Upstream HTTP ${status}` };
}

export async function executeRunResponse(spec: RequestSpec, token?: string, managementBase = ''): Promise<{ run: Run; response: string }> {
  const managementToken = token;
  token = spec.token ?? token;
  let rawResponse = '';
  const started = Date.now();
  const headers = new Headers(spec.headers);
  if (spec.token !== undefined) headers.delete('Authorization');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const safeSpec = { ...spec };
  delete safeSpec.signal;
  delete safeSpec.token;
  const actual = { ...safeSpec, headers: Object.fromEntries(headers.entries()) };
  const secrets = [...requestSecrets(actual, token), ...(managementToken ? [managementToken] : [])];
  const run: Run = {
    id: newId(), startedAt: new Date(started).toISOString(), durationMs: 0,
    request: redact(actual, secrets), status: null, responseHeaders: {}, response: '',
    outcome: 'error', summary: '', audit: [],
  };
  try {
    const response = await fetch(spec.url, { method: spec.method, headers, body: spec.body, signal: spec.signal });
    run.status = response.status;
    run.responseHeaders = redact(Object.fromEntries(response.headers.entries()), secrets);
    rawResponse = await response.text();
    run.response = redact(rawResponse, secrets);
    Object.assign(run, summarise(response.status, run.response));
  } catch (error) {
    run.error = redact(error instanceof Error ? error.message : String(error), secrets);
    run.summary = `Transport error: ${run.error}. Check listener URL and CORS configuration.`;
  }
  run.durationMs = Date.now() - started;
  const query = new URLSearchParams({
    from: new Date(started - 1000).toISOString(), to: new Date(Date.now() + 1000).toISOString(), limit: '100',
  });
  if (spec.namespace) query.set('namespace', spec.namespace);
  if (spec.target) query.set('target', spec.target);
  run.auditUrl = `${managementBase.replace(/\/$/, '')}/api/v1/audit/events?${query}`;
  try {
    const page = await managementFetch(run.auditUrl, managementToken, spec.signal) as { events?: unknown[] };
    run.audit = redact(page?.events || [], secrets);
  } catch (error) {
    run.auditError = redact(error instanceof Error ? error.message : String(error), secrets);
  }
  return { run, response: rawResponse };
}

export async function executeRun(spec: RequestSpec, token?: string, managementBase = ''): Promise<Run> {
  return (await executeRunResponse(spec, token, managementBase)).run;
}

const quote = (value: string) => `'${value.replace(/'/g, `'"'"'`)}'`;
export function toCurl(spec: RequestSpec): string {
  const safe = redact(spec, requestSecrets(spec));
  const parts = ['curl', '-X', quote(safe.method), quote(safe.url)];
  for (const [key, value] of Object.entries(safe.headers || {})) parts.push('-H', quote(`${key}: ${value}`));
  if (safe.body !== undefined) parts.push('--data-raw', quote(safe.body));
  return parts.join(' ');
}
export function exportRun(run: Run): string {
  return JSON.stringify(redact(run), null, 2);
}

const ajv = new Ajv({ allErrors: true, strict: false });
export function validateSchema(schema: unknown, value: unknown): string[] {
  try {
    const validate = ajv.compile(schema as object);
    if (validate(value)) return [];
    return (validate.errors || []).map(error => `${error.instancePath || '/'} ${error.message}`);
  } catch (error) {
    return [`Schema cannot be validated: ${error instanceof Error ? error.message : String(error)}`];
  }
}
