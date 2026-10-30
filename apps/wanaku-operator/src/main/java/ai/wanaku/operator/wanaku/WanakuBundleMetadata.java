package ai.wanaku.operator.wanaku;

import io.quarkiverse.operatorsdk.annotations.CSVMetadata;
import io.quarkiverse.operatorsdk.annotations.SharedCSVMetadata;

/**
 * Metadata shared by all Wanaku reconcilers in the single OLM bundle ({@code wanaku-operator}).
 * <p>
 * Reconcilers join the bundle with {@code @CSVMetadata(bundleName = WanakuBundleMetadata.BUNDLE_NAME)}.
 * Values set on those annotations are ignored in favor of the ones below.
 */
@CSVMetadata(
        bundleName = WanakuBundleMetadata.BUNDLE_NAME,
        displayName = "Wanaku Operator",
        description =
                """
                ## Overview

                The Wanaku Operator manages the lifecycle of the Wanaku AI stack on Kubernetes and OpenShift.
                It deploys and operates the following resources:

                - **WanakuRouter** deploys the Wanaku Server (MCP routing engine), an optional Wanaku Barn \
                backend for service-catalog persistence, and an optional oauth2-proxy for OIDC authentication.
                - **WanakuServiceCatalog** uploads service catalogs to a running WanakuRouter and removes them \
                when the resource is deleted.
                - **WanakuSemanticRouter** runs a Wanaku Semantic Router runtime and registers it with a router.
                - **WanakuIntegration** runs a Camel Integration Capability runtime and registers its MCP tools \
                with a router.

                ## Features

                - Single-namespace install (OwnNamespace and SingleNamespace modes)
                - Creates Deployments, Services, PVCs, and Ingress or OpenShift Routes
                - Optional OIDC authentication via oauth2-proxy
                - Service catalog lifecycle (deploy, restore on conflict, remove on delete)
                - No cluster-wide permissions required

                ## Prerequisites

                - Kubernetes 1.27+ or OpenShift 4.12+
                - A StorageClass that can satisfy a ReadWriteOnce PVC
                - An OIDC provider (for example Keycloak) when `spec.auth.enabled` is `true`

                ## Documentation

                - [Operator guide](https://github.com/wanaku-ai/wanaku-barn/blob/main/docs/operate/operator.md)
                - [Wanaku documentation](https://wanaku.ai/docs)
                """,
        annotations =
                @CSVMetadata.Annotations(
                        repository = "https://github.com/wanaku-ai/wanaku-barn",
                        capabilities = "Basic Install",
                        containerImage = "quay.io/wanaku/wanaku-operator:0.0.0-dev",
                        almExamples =
                                """
                                [
                                  {
                                    "apiVersion": "wanaku.ai/v1alpha1",
                                    "kind": "WanakuRouter",
                                    "metadata": {"name": "wanaku-dev"},
                                    "spec": {"server": {}}
                                  },
                                  {
                                    "apiVersion": "wanaku.ai/v1alpha1",
                                    "kind": "WanakuServiceCatalog",
                                    "metadata": {"name": "wanaku-service-catalogs"},
                                    "spec": {
                                      "routerRef": "wanaku-dev",
                                      "catalogs": [{"name": "finance-catalog", "configMapRef": "finance-catalog-data"}]
                                    }
                                  },
                                  {
                                    "apiVersion": "wanaku.ai/v1alpha1",
                                    "kind": "WanakuSemanticRouter",
                                    "metadata": {"name": "support"},
                                    "spec": {
                                      "routerRef": "wanaku-dev",
                                      "source": {"semanticRoute": {"name": "support"}},
                                      "forward": {"namespace": "default"}
                                    }
                                  },
                                  {
                                    "apiVersion": "wanaku.ai/v1alpha1",
                                    "kind": "WanakuIntegration",
                                    "metadata": {"name": "orders"},
                                    "spec": {
                                      "routerRef": "wanaku-dev",
                                      "routes": {"configMapRef": {"name": "orders-routes", "main": "orders.camel.yaml"}},
                                      "mcp": {"port": 9090},
                                      "registration": {"enabled": true}
                                    }
                                  }
                                ]
                                """,
                        categories = "AI/Machine Learning,Integration & Delivery",
                        others = {
                            @CSVMetadata.Annotations.Annotation(name = "support", value = "Wanaku AI"),
                            @CSVMetadata.Annotations.Annotation(
                                    name = "description",
                                    value = "Deploys and manages the Wanaku MCP router and its capabilities")
                        }),
        icon = @CSVMetadata.Icon(fileName = "icon.png", mediatype = "image/png"),
        keywords = {"wanaku", "mcp", "ai", "llm", "camel", "router"},
        maturity = "alpha",
        minKubeVersion = "1.27.0",
        maintainers = @CSVMetadata.Maintainer(name = "Wanaku AI Team", email = "contact@wanaku.ai"),
        provider = @CSVMetadata.Provider(name = "Wanaku AI", url = "https://wanaku.ai"),
        links = {
            @CSVMetadata.Link(name = "Documentation", url = "https://wanaku.ai/docs"),
            @CSVMetadata.Link(name = "Source Code", url = "https://github.com/wanaku-ai/wanaku-barn"),
            @CSVMetadata.Link(name = "Issue Tracker", url = "https://github.com/wanaku-ai/wanaku-barn/issues")
        },
        installModes = {
            @CSVMetadata.InstallMode(type = "OwnNamespace", supported = true),
            @CSVMetadata.InstallMode(type = "SingleNamespace", supported = true),
            @CSVMetadata.InstallMode(type = "MultiNamespace", supported = false),
            @CSVMetadata.InstallMode(type = "AllNamespaces", supported = false)
        })
public class WanakuBundleMetadata implements SharedCSVMetadata {

    /** Name of the OLM bundle (and of the OLM package) that contains all Wanaku reconcilers. */
    public static final String BUNDLE_NAME = "wanaku-operator";

    private WanakuBundleMetadata() {}
}
