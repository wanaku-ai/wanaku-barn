package ai.wanaku.operator.wanaku;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

@CSVMetadata(
        displayName = "Wanaku Service Catalog",
        description = "A Wanaku Service Catalog, a packaged set of Camel routes deployed to a router")
@Version("v1alpha1")
@Group("wanaku.ai")
public class WanakuServiceCatalog extends CustomResource<WanakuServiceCatalogSpec, WanakuServiceCatalogStatus>
        implements Namespaced {}
