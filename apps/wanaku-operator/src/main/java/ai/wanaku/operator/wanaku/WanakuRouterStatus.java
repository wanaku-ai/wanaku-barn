package ai.wanaku.operator.wanaku;

import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.Condition;

public class WanakuRouterStatus {
    private Long observedGeneration;
    private Map<String, String> endpoints;
    private String host;
    private String sseEndpoint;
    private String streamableEndpoint;
    private List<Condition> conditions;

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    /** Public URL of each exposed listener, keyed by listener name (management, mcp, inference, a2a). */
    public Map<String, String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, String> endpoints) {
        this.endpoints = endpoints;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getSseEndpoint() {
        return sseEndpoint;
    }

    public void setSseEndpoint(String sseEndpoint) {
        this.sseEndpoint = sseEndpoint;
    }

    public String getStreamableEndpoint() {
        return streamableEndpoint;
    }

    public void setStreamableEndpoint(String streamableEndpoint) {
        this.streamableEndpoint = streamableEndpoint;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
