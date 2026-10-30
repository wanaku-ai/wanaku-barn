# Wanaku Operator on OperatorHub (OLM bundle)

The Wanaku Operator is packaged as an [Operator Lifecycle Manager (OLM)](https://olm.operatorframework.io/)
bundle so that it can be listed on [OperatorHub.io](https://operatorhub.io) and in the embedded OperatorHub of
OpenShift/OKD. It declares the **Basic Install** capability level.

The bundle is generated from the operator source by the
[`quarkus-operator-sdk-bundle-generator`](https://github.com/quarkiverse/quarkus-operator-sdk/blob/main/docs/modules/ROOT/pages/deploy-with-olm.adoc)
extension. Nothing in `target/bundle` is committed.

## Where the metadata comes from

| Content | Source |
|---------|--------|
| ClusterServiceVersion (name, description, icon, links, maintainers, install modes, keywords, `alm-examples`) | [`WanakuBundleMetadata`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-operator/src/main/java/ai/wanaku/operator/wanaku/WanakuBundleMetadata.java) |
| Display name and description of each CRD | `@CSVMetadata` on each reconciler |
| Deployment, RBAC | Generated from the Kubernetes and `@RBACRule` configuration, like the Helm chart |
| Icon | `apps/wanaku-operator/src/main/kubernetes/icon.png` |
| Channels | `quarkus.operator-sdk.bundle.channels` in `application.properties` (`alpha`) |
| `createdAt` | `application.properties` via the `olm.createdAt` property (defaults to a placeholder). Pass `CREATED_AT=<yyyy-mm-ddThh:mm:ssZ>` to `make olm-bundle` to override it. The release workflow sets it automatically |
| `containerImage` | `WanakuBundleMetadata`. `olm/Makefile` overrides it with the versioned operator image |
| `replaces`, `olm.skipRange` | `REPLACES` and `SKIP_RANGE` make variables, passed to Maven as `quarkus.operator-sdk.bundle.*` properties |
| `relatedImages`, supported OpenShift versions, scorecard | [`finish-bundle.sh`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-operator/olm/scripts/finish-bundle.sh), because the generator cannot express them |
| Operand images listed in `relatedImages` | [`related-images.txt`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-operator/olm/related-images.txt) |

The operator watches the namespace it is installed in, so the bundle supports the `OwnNamespace` and
`SingleNamespace` install modes. `MultiNamespace` and `AllNamespaces` are not supported.

> [!NOTE]
> When you change the defaults of an operand image in the operator code, update
> [`related-images.txt`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-operator/olm/related-images.txt). Disconnected OpenShift clusters mirror
> exactly the images listed there.

## Requirements

`mvn`, `podman` (or `docker`, with `CONTAINER_TOOL=docker`) and
[`operator-sdk`](https://sdk.operatorframework.io/docs/installation/) 1.42+.
Local cluster tests also need `kubectl`, `docker` and `minikube`, or `oc`, `podman` and `crc`. The commands below run
from the repository root and delegate to [`apps/wanaku-operator/olm/Makefile`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-operator/olm/Makefile).

## Build and validate

```shell
make olm-bundle VERSION=0.3.0      # writes apps/wanaku-operator/target/bundle/wanaku-operator
make olm-validate                  # operatorframework and good-practices suites
make olm-bundle-image VERSION=0.3.0
```

Useful variables: `REGISTRY`, `GROUP` (the operator image is `$REGISTRY/$GROUP/wanaku-operator:$VERSION`),
`CHANNEL`, `REPLACES` (for example `wanaku-operator.v0.2.0`), `SKIP_RANGE` (for example `>=0.2.0 <0.3.0`),
`OPERAND_TAG` (replaces `:latest` in the `quay.io/wanaku` operand images), `CREATED_AT` and `OPENSHIFT_VERSIONS`.

`VERSION` defaults to the project version without `-SNAPSHOT`. It must be valid semver, because it becomes the CSV
version.

## Test locally

Both flows build the operator image and the bundle, publish them to a registry that the cluster can reach, and run
the bundle with `operator-sdk run bundle` in the `OwnNamespace` install mode.

### minikube

The scripts point docker at the minikube daemon (`eval "$(minikube docker-env)"`), so the operator image is built
straight into the node. OLM and the registry addon must already be installed.

```shell
minikube start --container-runtime=docker
minikube addons enable registry    # and either "minikube addons enable olm" or "operator-sdk olm install"
make olm-minikube-up
make olm-minikube-smoke            # creates a WanakuRouter and waits for Ready
make olm-scorecard                 # runs the scorecard against the cluster
make olm-minikube-down
```

### OpenShift Local (CRC)

OLM is part of OpenShift, so it is not installed. The scripts start CRC if needed and use the internal image registry.

```shell
make olm-crc-up
make olm-crc-smoke
make olm-scorecard
make olm-crc-down
```

Set `NAMESPACE` to use a namespace other than `wanaku-olm-test` (for example `make olm-minikube-up NAMESPACE=my-test`).
The detailed procedure and assertions are in
[`tests/plans/operator-olm-install.md`](https://github.com/wanaku-ai/wanaku-barn/blob/main/tests/plans/operator-olm-install.md).

## Continuous integration

| Workflow | When | What |
|----------|------|------|
| [`olm-bundle.yml`](https://github.com/wanaku-ai/wanaku-barn/blob/main/.github/workflows/olm-bundle.yml) | Pull requests that touch `apps/wanaku-operator/**` | Builds the bundle, runs `operator-sdk bundle validate`, lints the scripts and uploads the bundle |
| [`release-olm-bundle.yml`](https://github.com/wanaku-ai/wanaku-barn/blob/main/.github/workflows/release-olm-bundle.yml) | Manually, after `release-artifacts` | Builds and validates the release bundle, pushes `quay.io/wanaku/wanaku-operator-bundle:<version>`, attaches the ZIP to the GitHub release and opens the community-operators pull requests |

The release workflow needs these repository settings:

| Name | Type | Purpose |
|------|------|---------|
| `QUAY_USERNAME`, `QUAY_PASSWORD` | Secrets (already used by `release-artifacts`) | Push the bundle image |
| `COMMUNITY_OPERATORS_TOKEN` | Secret | Token that can push to the forks and open pull requests on the target repositories |
| `COMMUNITY_OPERATORS_FORK_OWNER` | Variable | User or organization that owns the forks |
| `COMMUNITY_OPERATORS_GIT_NAME`, `COMMUNITY_OPERATORS_GIT_EMAIL` | Variables | Identity for the DCO sign-off of the submission commit |
| `COMMUNITY_OPERATORS_REVIEWERS` | Variable (optional) | Comma-separated GitHub users written to `ci.yaml` on the first submission |

## Release and submission

1. Release Wanaku and run `release-artifacts`, so that `quay.io/wanaku/wanaku-operator:<version>` exists.
2. Run the `release-olm-bundle` workflow:

   ```shell
   gh workflow run release-olm-bundle \
     -f currentDevelopmentVersion=0.3.0 \
     -f previousVersion=0.2.0 \
     -f operandTag=0.3.0
   ```

   Leave `previousVersion` empty for the first release. When set, the CSV gets `replaces` and
   `olm.skipRange`, which OperatorHub needs to build the upgrade graph.
3. The workflow opens one pull request on each repository. Both pull requests need a DCO sign-off, which the
   script adds, and pass the repository's own CI:

   | Catalog | Repository | Directory |
   |---------|------------|-----------|
   | OperatorHub.io (Kubernetes) | [`k8s-operatorhub/community-operators`](https://github.com/k8s-operatorhub/community-operators) | `operators/wanaku-operator/<version>/` |
   | OpenShift/OKD community catalog | [`redhat-openshift-ecosystem/community-operators-prod`](https://github.com/redhat-openshift-ecosystem/community-operators-prod) | `operators/wanaku-operator/<version>/` |

4. After the pull requests are merged, the pipeline publishes the bundle and the catalog by itself.

The first submission also creates `operators/wanaku-operator/ci.yaml` with `updateGraph: replaces-mode`. Once you
switch a package to `semver-mode` you cannot go back.

To prepare a submission without pushing, run the script with `DRY_RUN=true`. See
[`submit-community-operators.sh`](https://github.com/wanaku-ai/wanaku-barn/blob/main/.github/scripts/submit-community-operators.sh) for the variables.

## Certified operators

Publishing to the Red Hat certified catalog is a separate process through the Red Hat Partner Connect portal. It
requires a partner account, UBI-based images pinned by digest and `preflight` checks. This is not automated yet.

## Conventions

- **Package name**: `wanaku-operator`. The CSV is `wanaku-operator.v<version>`.
- **Channels**: `alpha` while the API is `v1alpha1`. Add `stable` when a CRD reaches `v1beta1` or `v1`.
- **Image tags**: bundles reference the operator image by version tag. Never use `latest` in a released bundle.
- **Categories**: `AI/Machine Learning` and `Integration & Delivery` come from the
  [OperatorHub list](https://github.com/k8s-operatorhub/community-operators/blob/main/categories.json).
- **Icon**: the bundle uses `artwork/icon.png` (48x48). OperatorHub recommends a 1:2 height-to-width ratio and at
  most 80x40 pixels, so consider a wide variant for a better tile.
