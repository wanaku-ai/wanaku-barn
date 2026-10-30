# Running Integrations in the Cloud

The `wanaku cloud` commands run your Camel routes and semantic routes in a Kubernetes or OpenShift cluster, next to a Wanaku router that the Wanaku operator manages.
You write a route file on your machine; one command packages it, starts it in the cluster, and makes its tools available through the router.

The commands work with any cluster that you can reach with `kubectl`. They do not depend on a cloud provider.

## Prerequisites

- The Wanaku operator is installed in the cluster, at a version that serves the `WanakuIntegration` and `WanakuSemanticRouter` resources. See the [Deploy with the Wanaku Operator](../operate/operator.md).
- A `WanakuRouter` is ready in the namespace that you use.
- Your kubeconfig can reach the cluster. Your account can create ConfigMaps, `WanakuIntegration` and `WanakuSemanticRouter` resources in the namespace.

## Setting the Target

The cloud target is the Kubernetes context, the namespace and the `WanakuRouter` that your runtimes register with.
The CLI stores it in `$WANAKU_HOME/cloud.properties` (by default `~/.wanaku/cloud.properties`).

To store the target, use this command:

```shell
wanaku cloud target set --context my-cluster --namespace wanaku --router wanaku-dev
```

To show the stored target, use this command:

```shell
wanaku cloud target show
```

Each cloud command also accepts `--context`, `--namespace` and `--router`. These options override the stored target for one command.
If you do not set a context or a namespace, the CLI uses the current kubeconfig context and its namespace.

## Running an Integration

The input is a Camel YAML route file. The Camel Integration Capability serves each route that starts from `ai-tool:<name>` and has a matching tag as an MCP tool:

```yaml
- route:
    id: echo-tool
    from:
      uri: ai-tool:echo
      parameters:
        tags: wanaku
        description: Echo the message
        parameter.message: string
      steps:
        - setBody:
            simple: "${header.message}"
```

To run the file, use this command:

```shell
wanaku cloud run integration echo.camel.yaml
```

The command does these steps:

1. It checks the file. The file must be a Camel YAML DSL list, smaller than 900 KiB, with at least one MCP tool route. The tool route must have one of the tags in `--tags` (default `wanaku`).
2. It checks that the target `WanakuRouter` exists and is ready.
3. It writes the file into the `<name>-routes` ConfigMap, and creates or updates the `<name>` `WanakuIntegration` resource. The name comes from the file name (`echo.camel.yaml` gives `echo`). Use `--name` to set another name.
4. It waits until the operator reports `Ready=True`, and prints each change of the `Ready` condition.

When the integration is ready, the command prints the forward name and the router MCP endpoint that serves the tools.

| Option | Description |
|--------|-------------|
| `--name` | Resource name (default: derived from the file name) |
| `--forward-name` | Forward name in the router (default: the resource name) |
| `--forward-namespace` | Wanaku namespace of the forward (default `default`) |
| `--dependencies` | Extra Maven dependencies, comma separated (`group:artifact:version`) |
| `--tags` | Route tags to serve as MCP tools (default `wanaku`) |
| `--image` | Capability image (default: chosen by the operator) |
| `--image-pull-policy` | `Always`, `IfNotPresent` or `Never` (default: chosen by the operator). Use `Always` to get a new build of a moving tag, such as `latest` |
| `--no-wait` | Return after the submission, without waiting |
| `--timeout` | Seconds to wait for the integration (default 300) |

When you run the command again with the same name, it updates the route files and restarts the integration.

The Camel Integration Capability registers itself with the router. This needs a capability image that supports self-registration (wanaku-ai/camel-integration-capability#153). An older image does not register and does not become ready, so the command times out with `0/1 replicas available`. In this case, use `--image` to select a newer image, and `--image-pull-policy Always` if you reuse a tag.

When the integration fails or is not ready before the timeout, the command also prints why its pods are not ready: the waiting reason of each container (for example `ImagePullBackOff`), the reason of the last container stop, and the latest Warning event of the pod (for example `Readiness probe failed: HTTP probe failed with statuscode: 404`). This needs permission to list pods and events in the namespace.

## Running a Semantic Router

The input of `wanaku cloud run semantic` is a semantic route for the Wanaku Semantic Router (WSR). The route needs its `semantic-router.properties` manifest in the same directory, as in the WSR local-files mode. The manifest names the main route, the dependencies file, the configuration file and the Kamelets, relative to the directory that `main` is relative to:

```text
catalog/
  support/
    semantic-router.properties   # main=support/support.camel.yaml, kamelets=support/kamelets, ...
    support.camel.yaml
    support.dependencies.txt
    service.properties
    kamelets/
      classify.kamelet.yaml
```

To run the semantic route, use this command:

```shell
wanaku cloud run semantic catalog/support/support.camel.yaml
```

The command does these steps:

1. It checks the files. The manifest must set `main`, `dependencies`, `configuration`, `kamelets`, `tool.name` and `expert.bean`. `main` must name the route file. The referenced files must exist inside the route tree. All files together must be smaller than 900 KiB.
2. It checks that the target `WanakuRouter` exists and is ready.
3. It writes all files into the `<name>-semantic` ConfigMap, and creates or updates the `<name>` `WanakuSemanticRouter` resource. ConfigMap keys cannot hold `/`, so each key replaces `/` with `--`, and the resource maps each key back to its path.
4. It waits until the operator reports `Ready=True`.

The forward name is the `tool.name` of the manifest. Use `--forward-name` to set another name. The options are the same as for `run integration`, except `--tags`. The default timeout is 600 seconds, because the runtime resolves Maven dependencies at startup.

To check, read the logs of, or delete a semantic router, use `wanaku cloud status semantic <name>`, `kubectl logs deployment/<name>-wsr` and `wanaku cloud delete semantic <name>`. When you delete it, the runtime removes its forward from the router if it still owns the forward.

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | The integration is ready, or it was submitted with `--no-wait`. |
| 1 | The integration failed to start (for example, `CrashLoopBackOff` or a validation error), or it was not ready before the timeout. |
| 2 | The input is not valid: the file is missing, is not Camel YAML, or has no MCP tool route. Nothing was submitted. |
| 3 | The target is not reachable: the CLI cannot reach the cluster, the router is missing or not ready, or the operator does not serve `WanakuIntegration`. |
| 4 | The integration started, but its registration with the router did not complete. |

## Checking and Removing an Integration

To show the conditions and the endpoints of an integration, use this command:

```shell
wanaku cloud status integration echo
```

To read the logs of an integration, use this command:

```shell
kubectl logs deployment/echo-cic
```

An integration runs until you delete it. To delete it, use this command:

```shell
wanaku cloud delete integration echo
```

When you delete an integration, Kubernetes stops its pod and deletes its route files ConfigMap. Before the capability stops, it removes its forward from the router.
