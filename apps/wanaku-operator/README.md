# Wanaku Operator

This module contains the Wanaku Operator implementation.

The operator currently manages:

- `WanakuRouter`
- `WanakuServiceCatalog`

Capability services (such as the Camel Integration Capability) are deployed as regular
`Deployment` resources and registered with the router using `wanaku forwards add`.

Sample manifests are available under `samples/`.

The operator is also packaged as an OLM bundle for OperatorHub.io and the OpenShift catalog. The bundle metadata,
scripts and local test targets are described in [docs/operate/operator-olm.md](../../docs/operate/operator-olm.md); the scripts live
in `olm/`.

## Running the operator

The operator uses the [Java Operator SDK](https://javaoperatorsdk.io/). This SDK will resolve the
Kubernetes cluster by inspecting the local configuration for the `kubectl` command. This process is
described in more detail in the [Getting Started section of the documentation](https://javaoperatorsdk.io/docs/getting-started/).

**NOTE**: Podman Desktop can manage the Kubernetes contexts for you. You can set it using the tray icon or the Dashboard.

With the Kind cluster up and running, the `kubectl` command installed and the Kubernetes context set. Then you can launch the
operator. At this moment, the recommended way is by running the `main` method on `WanakuOperator` class
directly via the IDE or by running `mvn quarkus:dev`.
