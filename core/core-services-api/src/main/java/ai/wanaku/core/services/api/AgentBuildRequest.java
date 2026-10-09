package ai.wanaku.core.services.api;

import java.util.List;

/**
 * Request payload for building an agent via {@link AgentBuilderService#build(AgentBuildRequest)}.
 * Describes the agent identity, the LLM provider configuration, the tools and resources
 * the agent may use, its conversational memory settings and deployment options.
 */
public class AgentBuildRequest {

    /** Default memory kind used when memory is enabled and no kind is specified. */
    public static final String DEFAULT_MEMORY_KIND = "message-window";

    /** Default maximum number of messages retained in memory. */
    public static final int DEFAULT_MEMORY_MAX_MESSAGES = 10;

    /** Default port on which the generated agent exposes its REST endpoint. */
    public static final int DEFAULT_REST_PORT = 8081;

    private String agentName;
    private String description;
    private String systemPrompt;
    private String modelKind;
    private String modelName;
    private String baseUrl;
    private String apiKeyRef;
    private List<String> toolNames;
    private List<String> resourceNames;
    private boolean enableMemory;
    private String memoryKind = DEFAULT_MEMORY_KIND;
    private int memoryMaxMessages = DEFAULT_MEMORY_MAX_MESSAGES;
    private boolean deployAsTemplate;
    private int restPort = DEFAULT_REST_PORT;

    /**
     * @return the agent name, also used as the generated catalog name
     */
    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    /**
     * @return a human-readable description of the agent
     */
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return the system prompt that defines the agent behavior
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /**
     * @return the LLM provider identifier (e.g., {@code ollama}, {@code openai}, {@code anthropic})
     */
    public String getModelKind() {
        return modelKind;
    }

    public void setModelKind(String modelKind) {
        this.modelKind = modelKind;
    }

    /**
     * @return the model name within the provider (e.g., {@code granite4:3b})
     */
    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /**
     * @return the provider base URL, or {@code null} to use the provider default
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * @return a reference (such as an environment variable or secret name) to the provider API key
     */
    public String getApiKeyRef() {
        return apiKeyRef;
    }

    public void setApiKeyRef(String apiKeyRef) {
        this.apiKeyRef = apiKeyRef;
    }

    /**
     * @return the names of the tools the agent may invoke
     */
    public List<String> getToolNames() {
        return toolNames;
    }

    public void setToolNames(List<String> toolNames) {
        this.toolNames = toolNames;
    }

    /**
     * @return the names of the resources the agent may read
     */
    public List<String> getResourceNames() {
        return resourceNames;
    }

    public void setResourceNames(List<String> resourceNames) {
        this.resourceNames = resourceNames;
    }

    /**
     * @return whether conversational memory is enabled for the agent
     */
    public boolean isEnableMemory() {
        return enableMemory;
    }

    public void setEnableMemory(boolean enableMemory) {
        this.enableMemory = enableMemory;
    }

    /**
     * @return the memory implementation kind (defaults to {@value #DEFAULT_MEMORY_KIND})
     */
    public String getMemoryKind() {
        return memoryKind;
    }

    public void setMemoryKind(String memoryKind) {
        this.memoryKind = memoryKind;
    }

    /**
     * @return the maximum number of messages retained in memory (defaults to {@value #DEFAULT_MEMORY_MAX_MESSAGES})
     */
    public int getMemoryMaxMessages() {
        return memoryMaxMessages;
    }

    public void setMemoryMaxMessages(int memoryMaxMessages) {
        this.memoryMaxMessages = memoryMaxMessages;
    }

    /**
     * @return whether the generated package should be deployed as a service template instead of a service catalog
     */
    public boolean isDeployAsTemplate() {
        return deployAsTemplate;
    }

    public void setDeployAsTemplate(boolean deployAsTemplate) {
        this.deployAsTemplate = deployAsTemplate;
    }

    /**
     * @return the port on which the generated agent exposes its REST endpoint (defaults to {@value #DEFAULT_REST_PORT})
     */
    public int getRestPort() {
        return restPort;
    }

    public void setRestPort(int restPort) {
        this.restPort = restPort;
    }
}
