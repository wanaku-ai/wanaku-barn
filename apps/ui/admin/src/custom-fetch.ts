import {getPluginHost, getBackendUrl, isPluginMode, SERVICE_ID} from "./plugin-host";

  const getBody = <T>(c: Response | Request): Promise<T> => {
    const contentType = c.headers.get('content-type');

    if (contentType && contentType.includes('application/json')) {
      return c.json();
    }

    return c.text() as Promise<T>;
  };

  export const getUrl = (contextUrl: string): string => {
    const base = getBackendUrl() || VITE_API_URL || window.location.origin;
    const url = new URL(contextUrl, base);
    const pathname = url.pathname;
    const search = url.search;

    const requestUrl = new URL(`${base}${pathname}${search}`);

    return requestUrl.toString();
  };

  const getHeaders = (headers?: HeadersInit): HeadersInit => {
    return {
      ...headers
    };
  };

  const REDIRECT_TS_KEY = 'wanaku_auth_redirect_ts';
  const REDIRECT_LOOP_MS = 10_000;

  const getErrorMessage = (error: unknown, fallback: string): string => {
    if (typeof error === 'string') return error;
    if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string') {
      return error.message;
    }
    return fallback;
  };

  async function pluginFetch<T>(url: string, options: RequestInit): Promise<T> {
    const host = getPluginHost();
    if (!host) {
      throw new Error('Plugin host is not available');
    }

    const parsed = new URL(url, 'http://localhost');
    const path = parsed.pathname + parsed.search;
    const method = (options.method || 'GET').toUpperCase();

    let body: unknown = undefined;
    if (options.body && typeof options.body === 'string') {
      try {
        body = JSON.parse(options.body);
      } catch {
        body = options.body;
      }
    }

    let data: unknown;
    switch (method) {
      case 'POST':
        data = await host.http.post(SERVICE_ID, path, body);
        break;
      case 'PUT':
        data = await host.http.put(SERVICE_ID, path, body);
        break;
      case 'DELETE':
        data = await host.http.delete(SERVICE_ID, path);
        break;
      default:
        data = await host.http.get(SERVICE_ID, path);
        break;
    }

    const errorData = data as Record<string, unknown> | null;
    if (errorData && typeof errorData === 'object' && 'error' in errorData && errorData.error) {
      throw new Error(getErrorMessage(errorData.error, 'Backend request failed'));
    }

    // Hosts can return an unwrapped payload; callers expect the Barn response envelope.
    const responseData = errorData && typeof errorData === 'object' && 'data' in errorData ? data : { data };
    return { status: 200, data: responseData, headers: new Headers() } as T;
  }

  export const customFetch = async <T>(
    url: string,
    options: RequestInit,
  ): Promise<T> => {
    if (isPluginMode()) {
      return pluginFetch<T>(url, options);
    }

    const requestUrl = getUrl(url);
    const requestHeaders = getHeaders(options.headers);

    const requestInit: RequestInit = {
      ...options,
      headers: requestHeaders,
      redirect: 'manual',
    };

    const request = new Request(requestUrl, requestInit);
    const response = await fetch(request);

    if (response.type === 'opaqueredirect' || response.status === 401) {
      const lastRedirect = Number(sessionStorage.getItem(REDIRECT_TS_KEY) || '0');
      if (Date.now() - lastRedirect < REDIRECT_LOOP_MS) {
        throw new Error('Authentication redirect loop detected — check OIDC configuration');
      }
      sessionStorage.setItem(REDIRECT_TS_KEY, String(Date.now()));
      window.location.reload();
      throw new Error('Redirecting to login');
    }

    if (response.ok) {
      sessionStorage.removeItem(REDIRECT_TS_KEY);
    }

    const data = await getBody<T>(response);

    if (!response.ok) {
      const errorData = data as Record<string, unknown> | null;
      const message = getErrorMessage(errorData?.error, `Request failed with status ${response.status}`);
      throw new Error(message);
    }

    return { status: response.status, data, headers: response.headers } as T;
  };
