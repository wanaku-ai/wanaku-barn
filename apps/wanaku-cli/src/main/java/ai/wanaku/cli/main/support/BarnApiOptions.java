package ai.wanaku.cli.main.support;

import picocli.CommandLine.Option;

/**
 * Shared Picocli options for the commands that call the Barn API.
 * <p>
 * By default, the commands reach Barn through the Wanaku plugin proxy at {@code --host}.
 * With {@code --direct-access}, they call Barn at {@code --host} without the plugin path.
 * </p>
 */
public class BarnApiOptions {

    /** The path where Wanaku proxies the Barn API. */
    public static final String PLUGIN_PATH = "/api/plugins/wanaku-barn/wanaku-barn-api";

    @Option(
            names = {"--host"},
            description = "The Wanaku host. The command reaches Barn through the Wanaku plugin proxy",
            defaultValue = "http://localhost:8080",
            arity = "0..1")
    String host;

    @Option(
            names = {"--direct-access"},
            description = "Call Barn directly at --host, without the Wanaku plugin proxy")
    boolean directAccess;

    /**
     * Returns the base URI of the Barn API.
     *
     * @return the Wanaku plugin proxy URI, or the host itself with direct access
     */
    public String baseUri() {
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        return directAccess ? base : base + PLUGIN_PATH;
    }
}
