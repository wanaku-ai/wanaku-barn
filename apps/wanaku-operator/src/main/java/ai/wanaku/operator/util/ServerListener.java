package ai.wanaku.operator.util;

/**
 * The Wanaku Server listeners that can be exposed outside the cluster, with the Server port and the
 * oauth2-proxy port that protects each one.
 */
public enum ServerListener {
    MANAGEMENT("management", 8080, 4181),
    MCP("mcp", 8081, 4180),
    INFERENCE("inference", 8083, 4183),
    A2A("a2a", 8084, 4184);

    private final String id;
    private final int serverPort;
    private final int proxyPort;

    ServerListener(String id, int serverPort, int proxyPort) {
        this.id = id;
        this.serverPort = serverPort;
        this.proxyPort = proxyPort;
    }

    /** The listener name used in the spec, status endpoints and generated resource names. */
    public String id() {
        return id;
    }

    public int serverPort() {
        return serverPort;
    }

    public int proxyPort() {
        return proxyPort;
    }
}
