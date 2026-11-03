package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

/** One Camel Integration Capability (CIC) runtime: Camel routes served as MCP tools through a router. */
@CSVMetadata(
        displayName = "Wanaku Integration",
        description = "A Camel Integration Capability runtime, Camel routes served as MCP tools through a router")
@Version("v1alpha1")
@Group("wanaku.ai")
public class WanakuIntegration extends CustomResource<WanakuIntegrationSpec, WanakuRuntimeStatus>
        implements Namespaced {}
