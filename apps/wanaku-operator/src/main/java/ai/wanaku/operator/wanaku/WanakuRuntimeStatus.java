package ai.wanaku.operator.wanaku;

import java.util.List;
import io.fabric8.kubernetes.api.model.Condition;

/** Status of a runtime that registers itself with a router as an MCP forward. */
public class WanakuRuntimeStatus {
    private Long observedGeneration;
    private String forwardName;
    private String mcpAddress;
    private String wanakuEndpoint;
    private List<Condition> conditions;

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    /** The forward name registered with the router. */
    public String getForwardName() {
        return forwardName;
    }

    public void setForwardName(String forwardName) {
        this.forwardName = forwardName;
    }

    /** The in-cluster MCP address that the router calls. */
    public String getMcpAddress() {
        return mcpAddress;
    }

    public void setMcpAddress(String mcpAddress) {
        this.mcpAddress = mcpAddress;
    }

    /** The router MCP endpoint that serves the registered tools. */
    public String getWanakuEndpoint() {
        return wanakuEndpoint;
    }

    public void setWanakuEndpoint(String wanakuEndpoint) {
        this.wanakuEndpoint = wanakuEndpoint;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
