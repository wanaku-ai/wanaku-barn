package ai.wanaku.core.services.api;

/**
 * Metadata describing an LLM provider supported by the agent builder.
 * Returned by {@link AgentBuilderService#listProviders()}.
 */
public class LlmProviderInfo {

    private String id;
    private String name;
    private String displayName;
    private String defaultBaseUrl;
    private boolean requiresApiKey;

    public LlmProviderInfo() {}

    public LlmProviderInfo(String id, String name, String displayName, String defaultBaseUrl, boolean requiresApiKey) {
        this.id = id;
        this.name = name;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.requiresApiKey = requiresApiKey;
    }

    /**
     * @return the provider identifier used as {@code modelKind} in {@link AgentBuildRequest} (e.g., {@code ollama})
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return the provider technical name
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the provider name suitable for display in user interfaces
     */
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * @return the base URL used when the request does not specify one
     */
    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public void setDefaultBaseUrl(String defaultBaseUrl) {
        this.defaultBaseUrl = defaultBaseUrl;
    }

    /**
     * @return whether the provider requires an API key to authenticate
     */
    public boolean isRequiresApiKey() {
        return requiresApiKey;
    }

    public void setRequiresApiKey(boolean requiresApiKey) {
        this.requiresApiKey = requiresApiKey;
    }
}
