package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

@CSVMetadata(
        displayName = "Wanaku Router",
        description = "A Wanaku Router, the MCP server that exposes tools and resources to AI agents")
@Version("v1alpha1")
@Group("wanaku.ai")
public class WanakuRouter extends CustomResource<WanakuRouterSpec, WanakuRouterStatus> implements Namespaced {}
