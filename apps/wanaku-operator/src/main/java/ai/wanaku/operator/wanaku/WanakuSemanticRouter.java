package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/** One Wanaku Semantic Router (WSR) runtime: a semantic route served as one MCP tool through a router. */
@Version("v1alpha1")
@Group("wanaku.ai")
public class WanakuSemanticRouter extends CustomResource<WanakuSemanticRouterSpec, WanakuRuntimeStatus>
        implements Namespaced {}
