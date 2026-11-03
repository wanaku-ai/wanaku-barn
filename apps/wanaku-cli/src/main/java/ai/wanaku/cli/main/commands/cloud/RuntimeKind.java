package ai.wanaku.cli.main.commands.cloud;

import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

/** The runtimes that the cloud commands run, with the custom resource the operator reconciles for each. */
public enum RuntimeKind {
    INTEGRATION("integration", "WanakuIntegration", "wanakuintegrations"),
    SEMANTIC("semantic", "WanakuSemanticRouter", "wanakusemanticrouters");

    private final String id;
    private final String kind;
    private final String plural;

    RuntimeKind(String id, String kind, String plural) {
        this.id = id;
        this.kind = kind;
        this.plural = plural;
    }

    public String id() {
        return id;
    }

    public String kind() {
        return kind;
    }

    ResourceDefinitionContext context() {
        return CloudRunSupport.wanakuContext(kind, plural);
    }

    @Override
    public String toString() {
        return id;
    }
}
