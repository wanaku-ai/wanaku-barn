package ai.wanaku.operator.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.KeyToPath;
import io.fabric8.kubernetes.api.model.KeyToPathBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.wanaku.WanakuTypes;

/**
 * Builds the workloads of runtimes that register themselves with a router as MCP forwards (semantic
 * routers and Camel integrations).
 */
public final class RuntimeWorkloads {
    /** The router and the kubelet probes reach the runtime through the pod IP. */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public static final String ALL_INTERFACES = "0.0.0.0";

    private RuntimeWorkloads() {}

    /**
     * Builds a one-replica Deployment. The rolling update starts the new pod first: it registers the
     * forward again with a new instance label, so the old pod does not remove it at shutdown.
     */
    public static Deployment deployment(
            HasMetadata owner, String name, String component, Container container, List<Volume> volumes) {
        final Map<String, String> selector = Map.of("app", name, "component", component);
        final Map<String, String> podLabels = new HashMap<>(selector);
        // Lets an optional NetworkPolicy admit runtimes that call the router management API
        podLabels.put("wanaku.ai/client", "true");
        final Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(owner.getMetadata().getNamespace())
                .withLabels(selector)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(selector)
                .endSelector()
                .withNewStrategy()
                .withType("RollingUpdate")
                .withNewRollingUpdate()
                .withMaxSurge(new IntOrString(1))
                .withMaxUnavailable(new IntOrString(0))
                .endRollingUpdate()
                .endStrategy()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(podLabels)
                .endMetadata()
                .withNewSpec()
                // Leaves time to remove the forward at shutdown
                .withTerminationGracePeriodSeconds(60L)
                .withNewSecurityContext()
                .withRunAsNonRoot(true)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
                .endSecurityContext()
                .withContainers(container)
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        RouterResourceFactory.managed(deployment, deployment.getSpec());
        deployment.addOwnerReference(owner);
        return deployment;
    }

    /**
     * Builds the Service of the runtime MCP port. It publishes addresses of pods that are not ready,
     * because the router discovers the tools while the runtime registers, before the pod is ready.
     */
    public static Service service(HasMetadata owner, String name, String component, int port) {
        final Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(owner.getMetadata().getNamespace())
                .withLabels(Map.of("app", name, "component", component))
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", name, "component", component))
                .addNewPort()
                .withName("mcp")
                .withPort(port)
                .withTargetPort(new IntOrString("mcp"))
                .withProtocol("TCP")
                .endPort()
                .withPublishNotReadyAddresses(true)
                .endSpec()
                .build();
        RouterResourceFactory.managed(service, service.getSpec());
        service.addOwnerReference(owner);
        return service;
    }

    /** A read-only volume with the route files of a ConfigMap, placed at their item paths when set. */
    public static Volume routesVolume(WanakuTypes.ConfigMapSource configMap) {
        final List<KeyToPath> items = new ArrayList<>();
        if (configMap.getItems() != null) {
            for (WanakuTypes.KeyToPath item : configMap.getItems()) {
                items.add(new KeyToPathBuilder()
                        .withKey(item.getKey())
                        .withPath(item.getPath())
                        .build());
            }
        }
        return new VolumeBuilder()
                .withName("routes")
                .withNewConfigMap()
                .withName(configMap.getName())
                .withItems(items)
                .endConfigMap()
                .build();
    }

    public static Probe httpProbe(String path, String port, int periodSeconds, int failureThreshold) {
        return new ProbeBuilder()
                .withNewHttpGet()
                .withPath(path)
                .withPort(new IntOrString(port))
                .endHttpGet()
                .withPeriodSeconds(periodSeconds)
                .withTimeoutSeconds(5)
                .withFailureThreshold(failureThreshold)
                .build();
    }

    /** Returns the user-supplied variables followed by the defaults that the user did not override. */
    public static List<EnvVar> mergeEnv(List<WanakuTypes.EnvVar> userEnv, List<EnvVar> defaults) {
        final List<EnvVar> env = new ArrayList<>();
        if (userEnv != null) {
            for (WanakuTypes.EnvVar var : userEnv) {
                env.add(OperatorUtil.toEnvVar(var));
            }
        }
        for (EnvVar var : defaults) {
            if (env.stream().noneMatch(existing -> existing.getName().equals(var.getName()))) {
                env.add(var);
            }
        }
        return env;
    }

    /** Adds a variable read from a Secret key (default key {@code token}), when the reference is set. */
    public static boolean addSecretEnv(List<EnvVar> env, String name, WanakuTypes.SecretKeyRef ref) {
        if (ref == null || StringHelper.isBlank(ref.getName())) {
            return false;
        }
        env.add(new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(ref.getName())
                .withKey(StringHelper.isNotEmpty(ref.getKey()) ? ref.getKey() : "token")
                .endSecretKeyRef()
                .endValueFrom()
                .build());
        return true;
    }

    public static EnvVar value(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    /** The router management API URL inside the cluster. */
    public static String managementUrl(String routerName) {
        return "http://server-%s:8080".formatted(routerName);
    }
}
