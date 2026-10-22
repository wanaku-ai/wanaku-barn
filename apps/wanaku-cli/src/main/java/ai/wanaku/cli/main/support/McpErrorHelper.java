package ai.wanaku.cli.main.support;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;

/**
 * Translates MCP client exceptions into user-friendly error messages.
 *
 * <p>MCP client operations often throw wrapped exceptions (e.g.,
 * {@code ExecutionException} wrapping {@code ConnectException}) whose default
 * messages expose raw Java class names. This helper unwraps the cause chain
 * and produces concise, actionable messages consistent with the rest of
 * the Wanaku CLI.</p>
 */
public final class McpErrorHelper {

    // The message the LangChain4j Streamable HTTP transport uses for a non-2xx response
    private static final Pattern REDIRECT_STATUS = Pattern.compile("Unexpected status code: (30[12378])");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private McpErrorHelper() {}

    /**
     * Produces a user-friendly error message for a tool-call failure.
     *
     * <p>Handles MCP-specific exceptions that carry structured error information
     * (error codes, tool names) and produces actionable messages.</p>
     *
     * @param ex the tool execution exception (either {@code ToolExecutionException}
     *           or {@code ToolArgumentsException})
     * @param toolName the name of the tool that was being called (may be null)
     * @return a user-friendly error message
     */
    public static String friendlyToolCallMessage(ToolExecutionException ex, String toolName) {
        return buildToolCallMessage(ex.getMessage(), ex.errorCode(), toolName);
    }

    /**
     * Produces a user-friendly error message for a tool-arguments failure.
     *
     * @param ex the tool arguments exception
     * @param toolName the name of the tool that was being called (may be null)
     * @return a user-friendly error message
     */
    public static String friendlyToolCallMessage(ToolArgumentsException ex, String toolName) {
        return buildToolCallMessage(ex.getMessage(), ex.errorCode(), toolName);
    }

    private static String buildToolCallMessage(String message, Integer errorCode, String toolName) {
        StringBuilder sb = new StringBuilder();

        if (message != null && !message.isBlank()) {
            sb.append(message);
        } else if (toolName != null) {
            sb.append("Tool call failed: ").append(toolName);
        } else {
            sb.append("Tool call failed");
        }

        if (errorCode != null && errorCode == -32602) {
            sb.append("\n\nPossible causes:\n");
            sb.append("  - Tool name is not registered on the MCP server\n");
            sb.append("  - Invalid tool arguments");
        }

        return sb.toString();
    }

    /**
     * Produces a user-friendly error message for the given exception and
     * MCP server URI.
     *
     * @param ex the exception thrown during MCP client operations
     * @param uri the MCP server URI the command was targeting (may be null)
     * @return a user-friendly error message
     */
    public static String friendlyMessage(Exception ex, String uri) {
        return friendlyMessage(ex, uri, null);
    }

    /**
     * Produces a user-friendly error message for the given exception and
     * MCP server URI.
     *
     * <p>The MCP transport does not follow redirects and reports them only as
     * {@code Unexpected status code: 302}. In that case, this method sends a
     * {@code HEAD} request to the same URI, without following redirects, to read
     * the {@code Location} header and explain what the user must do.</p>
     *
     * @param ex the exception thrown during MCP client operations
     * @param uri the MCP server URI the command was targeting (may be null)
     * @param token the bearer token the command used (may be null)
     * @return a user-friendly error message
     */
    public static String friendlyMessage(Exception ex, String uri, String token) {
        Throwable root = rootCause(ex);

        Integer redirectStatus = redirectStatus(root);
        if (redirectStatus != null) {
            return redirectMessage(uri, redirectStatus, probeLocation(uri, token));
        }

        if (root instanceof ConnectException) {
            return connectionRefusedMessage(uri);
        }

        if (root instanceof UnknownHostException) {
            return unknownHostMessage(root);
        }

        if (root instanceof SocketTimeoutException) {
            return timeoutMessage(uri);
        }

        if (root instanceof IllegalArgumentException) {
            String msg = root.getMessage();
            if (msg != null && (msg.contains("URI") || msg.contains("scheme") || msg.contains("url"))) {
                return invalidUriMessage(uri);
            }
        }

        // For any other exception, return the root cause message without class name prefixes
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            return "An unexpected error occurred. Run with --verbose for more details";
        }
        return message;
    }

    private static String connectionRefusedMessage(String uri) {
        StringBuilder sb = new StringBuilder();
        sb.append("Connection refused");
        if (uri != null) {
            sb.append(": could not connect to ").append(uri);
        }
        sb.append("\n\nPossible causes:\n");
        sb.append("  - MCP server is not running\n");
        sb.append("  - Incorrect --uri value\n");
        sb.append("  - Network connectivity issues");
        return sb.toString();
    }

    private static String unknownHostMessage(Throwable root) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unable to resolve host: ").append(root.getMessage());
        sb.append("\n\nPossible causes:\n");
        sb.append("  - Invalid hostname in --uri option\n");
        sb.append("  - DNS resolution failure\n");
        sb.append("  - Network connectivity issues");
        return sb.toString();
    }

    private static String timeoutMessage(String uri) {
        StringBuilder sb = new StringBuilder();
        sb.append("Request timed out");
        if (uri != null) {
            sb.append(" connecting to ").append(uri);
        }
        sb.append("\n\nPossible causes:\n");
        sb.append("  - MCP server is not responding\n");
        sb.append("  - Network latency issues\n");
        sb.append("  - Firewall blocking the connection");
        return sb.toString();
    }

    private static String invalidUriMessage(String uri) {
        StringBuilder sb = new StringBuilder();
        sb.append("Invalid URI");
        if (uri != null) {
            sb.append(": '").append(uri).append("' is not a valid MCP server URI");
        }
        sb.append("\n\nExpected format: http://<host>:<port>/path or https://<host>:<port>/path");
        return sb.toString();
    }

    private static Integer redirectStatus(Throwable root) {
        String message = root.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = REDIRECT_STATUS.matcher(message.trim());
        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * Reads the redirect target of the URI. Returns null if the server does not
     * redirect the probe or cannot be reached.
     */
    private static URI probeLocation(String uri, String token) {
        if (uri == null) {
            return null;
        }
        try {
            URI target = URI.create(uri.trim());
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(PROBE_TIMEOUT);
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token.trim());
            }
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(PROBE_TIMEOUT)
                    .build();
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 3) {
                return null;
            }
            return response.headers()
                    .firstValue("Location")
                    .map(target::resolve)
                    .orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String redirectMessage(String uri, int status, URI location) {
        StringBuilder sb = new StringBuilder();
        sb.append("The MCP server responded with a redirect (HTTP ")
                .append(status)
                .append(")");
        if (location == null) {
            sb.append("\n\nThe CLI does not follow redirects for MCP requests. Possible causes:\n");
            sb.append("  - The server forces HTTPS: use --uri https://...\n");
            sb.append("  - The server asks for a browser login: check that the token is valid");
            return sb.toString();
        }
        sb.append(" to ").append(location);

        URI original = URI.create(uri.trim());
        if (isHttpsUpgrade(original, location)) {
            sb.append("\n\nThe server redirects to HTTPS. Use --uri ").append(location);
        } else if (isLoginRedirect(original, location)) {
            sb.append("\n\nThe server asks for a browser login, which the CLI cannot do. Possible causes:\n");
            sb.append("  - The token is missing (pass it with --token)\n");
            sb.append("  - The token is expired\n");
            sb.append("  - The token audience is not accepted by the server");
        } else {
            sb.append("\n\nThe CLI does not follow redirects for MCP requests. Try --uri ")
                    .append(location);
        }
        return sb.toString();
    }

    private static boolean isHttpsUpgrade(URI original, URI location) {
        return "http".equalsIgnoreCase(original.getScheme())
                && "https".equalsIgnoreCase(location.getScheme())
                && sameHost(original, location)
                && stripTrailingSlash(original.getPath()).equals(stripTrailingSlash(location.getPath()));
    }

    private static boolean isLoginRedirect(URI original, URI location) {
        String path = location.getPath() != null ? location.getPath() : "";
        return path.contains("/oauth2/start") || path.contains("/oauth2/sign_in") || !sameHost(original, location);
    }

    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
    }

    private static String stripTrailingSlash(String path) {
        if (path == null) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
