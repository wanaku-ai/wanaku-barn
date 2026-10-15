package ai.wanaku.operator.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.wanaku.WanakuSemanticRouter;
import ai.wanaku.operator.wanaku.WanakuSemanticRouterSpec;
import ai.wanaku.operator.wanaku.WanakuTypes;

/** Builds the workload of a Wanaku Semantic Router (WSR) runtime. */
public final class SemanticRouterResourceFactory {
    public static final String DEFAULT_IMAGE = "quay.io/wanaku/wanaku-semantic-router:latest";
    public static final int MCP_PORT = 8090;
    public static final int STATUS_PORT = 8091;
    public static final String COMPONENT = "wanaku-semantic-router";
    public static final String ROUTES_PATH = "/opt/wsr/routes";
    private static final String DATA_PATH = "/home/wsr/data";

    /** The router and the kubelet probes reach the runtime through the pod IP. */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String ALL_INTERFACES = "0.0.0.0";

    private SemanticRouterResourceFactory() {}

    public static String workloadName(WanakuSemanticRouter resource) {
        return resource.getMetadata().getName() + "-wsr";
    }

    public static String forwardName(WanakuSemanticRouter resource) {
        final WanakuTypes.ForwardSpec forward = resource.getSpec().getForward();
        return forward != null && StringHelper.isNotEmpty(forward.getName())
                ? forward.getName()
                : resource.getMetadata().getName();
    }

    public static String forwardNamespace(WanakuSemanticRouter resource) {
        final WanakuTypes.ForwardSpec forward = resource.getSpec().getForward();
        return forward != null && StringHelper.isNotEmpty(forward.getNamespace()) ? forward.getNamespace() : "default";
    }

    /** The address the router uses to reach the runtime MCP server. */
    public static String mcpAddress(WanakuSemanticRouter resource) {
        return "http://%s.%s.svc:%d/mcp"
                .formatted(workloadName(resource), resource.getMetadata().getNamespace(), MCP_PORT);
    }

    public static Deployment makeDeployment(WanakuSemanticRouter resource, boolean barnEnabled) {
        final WanakuSemanticRouterSpec spec = resource.getSpec();
        final String name = workloadName(resource);
        final Map<String, String> selector = Map.of("app", name, "component", COMPONENT);
        final Map<String, String> podLabels = new HashMap<>(selector);
        // Lets an optional NetworkPolicy admit runtimes that call the router management API
        podLabels.put("wanaku.ai/client", "true");

        final List<VolumeMount> mounts = new ArrayList<>();
        final List<Volume> volumes = new ArrayList<>();
        mounts.add(new VolumeMountBuilder()
                .withName("data")
                .withMountPath(DATA_PATH)
                .build());
        volumes.add(new VolumeBuilder()
                .withName("data")
                .withNewEmptyDir()
                .endEmptyDir()
                .build());
        final WanakuTypes.ConfigMapSource configMap =
                spec.getSource() != null ? spec.getSource().getConfigMapRef() : null;
        if (configMap != null) {
            mounts.add(new VolumeMountBuilder()
                    .withName("routes")
                    .withMountPath(ROUTES_PATH)
                    .withReadOnly(true)
                    .build());
            volumes.add(new VolumeBuilder()
                    .withName("routes")
                    .withNewConfigMap()
                    .withName(configMap.getName())
                    .endConfigMap()
                    .build());
        }

        final String image = StringHelper.isNotEmpty(spec.getImage()) ? spec.getImage() : DEFAULT_IMAGE;
        OperatorUtil.validateImageAllowed(image);
        final Container container = new ContainerBuilder()
                .withName("wsr")
                .withImage(image)
                .withImagePullPolicy(OperatorUtil.resolveImagePullPolicy(spec.getImagePullPolicy(), null))
                .withArgs(args(spec))
                .withEnv(env(resource, barnEnabled))
                .addNewPort()
                .withName("mcp")
                .withContainerPort(MCP_PORT)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("status")
                .withContainerPort(STATUS_PORT)
                .withProtocol("TCP")
                .endPort()
                // Probe contract: wanaku-ai/wanaku-semantic-router docs/deployment.md, "Health probes"
                .withStartupProbe(probe("/api/v1/health/started", 5, 120))
                .withReadinessProbe(probe("/api/v1/health/ready", 5, 3))
                .withLivenessProbe(probe("/api/v1/health/live", 20, 3))
                .withTerminationMessagePolicy("FallbackToLogsOnError")
                .withNewSecurityContext()
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .endSecurityContext()
                .withVolumeMounts(mounts)
                .build();

        final Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(resource.getMetadata().getNamespace())
                .withLabels(selector)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(selector)
                .endSelector()
                // The new pod registers with a new instance label, so the old pod leaves the forward in place
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
        deployment.addOwnerReference(resource);
        return deployment;
    }

    public static Service makeService(WanakuSemanticRouter resource) {
        final String name = workloadName(resource);
        final Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(resource.getMetadata().getNamespace())
                .withLabels(Map.of("app", name, "component", COMPONENT))
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", name, "component", COMPONENT))
                .addNewPort()
                .withName("mcp")
                .withPort(MCP_PORT)
                .withTargetPort(new IntOrString("mcp"))
                .withProtocol("TCP")
                .endPort()
                // The router discovers the tool while the runtime registers, before the pod is ready
                .withPublishNotReadyAddresses(true)
                .endSpec()
                .build();
        RouterResourceFactory.managed(service, service.getSpec());
        service.addOwnerReference(resource);
        return service;
    }

    /** The runtime command, with each {@code spec.properties} entry as a {@code -p KEY=VALUE} option in key order. */
    static List<String> args(WanakuSemanticRouterSpec spec) {
        final List<String> args = new ArrayList<>();
        args.add("runtime");
        if (spec.getProperties() != null) {
            new TreeMap<>(spec.getProperties()).forEach((key, value) -> {
                args.add("-p");
                args.add(key + "=" + value);
            });
        }
        return args;
    }

    private static List<EnvVar> env(WanakuSemanticRouter resource, boolean barnEnabled) {
        final WanakuSemanticRouterSpec spec = resource.getSpec();
        final String router = spec.getRouterRef();
        final List<EnvVar> env = new ArrayList<>();
        if (spec.getEnv() != null) {
            for (WanakuTypes.EnvVar var : spec.getEnv()) {
                env.add(OperatorUtil.toEnvVar(var));
            }
        }
        final List<EnvVar> defaults = new ArrayList<>();
        defaults.add(value("WSR_REGISTRATION_URL", "http://server-%s:8080".formatted(router)));
        if (barnEnabled) {
            defaults.add(value("WSR_BARN_URL", RouterResourceFactory.barnUrl(router)));
        }
        defaults.add(value("WSR_NAME", forwardName(resource)));
        defaults.add(value("WSR_NAMESPACE", forwardNamespace(resource)));
        defaults.add(value("WSR_MCP_ADDRESS", mcpAddress(resource)));
        defaults.add(value("WSR_BIND", ALL_INTERFACES));
        defaults.add(value("WSR_MCP_PORT", String.valueOf(MCP_PORT)));
        defaults.add(value("WSR_STATUS_BIND", ALL_INTERFACES));
        defaults.add(value("WSR_STATUS_PORT", String.valueOf(STATUS_PORT)));
        defaults.add(value("WSR_DATA_DIR", DATA_PATH));
        if (spec.getDependencies() != null && !spec.getDependencies().isEmpty()) {
            defaults.add(value("WSR_DEPENDENCIES", String.join(",", spec.getDependencies())));
        }
        sourceEnv(spec.getSource(), defaults);
        tokenEnv(spec.getWanakuTokenSecretRef(), "WANAKU_TOKEN", "WSR_WANAKU_TOKEN_ENV", defaults);
        tokenEnv(spec.getBarnTokenSecretRef(), "BARN_TOKEN", "WSR_BARN_TOKEN_ENV", defaults);
        for (EnvVar var : defaults) {
            if (env.stream().noneMatch(existing -> existing.getName().equals(var.getName()))) {
                env.add(var);
            }
        }
        return env;
    }

    private static void sourceEnv(WanakuSemanticRouterSpec.Source source, List<EnvVar> env) {
        if (source == null) {
            return;
        }
        if (source.getSemanticRoute() != null) {
            env.add(value("WSR_SEMANTIC_ROUTE", source.getSemanticRoute().getName()));
        }
        final WanakuSemanticRouterSpec.CatalogSource catalog = source.getCatalog();
        if (catalog != null) {
            env.add(value("WSR_CATALOG_NAME", catalog.getName()));
            env.add(value("WSR_CATALOG_SERVICE", catalog.getService()));
            env.add(value("WSR_CATALOG_REVISION", catalog.getRevision()));
            env.add(value("WSR_CATALOG_SHA256", catalog.getSha256()));
            if (StringHelper.isNotEmpty(catalog.getMain())) {
                env.add(value("WSR_CATALOG_MAIN", catalog.getMain()));
            }
        }
        if (source.getConfigMapRef() != null) {
            env.add(value(
                    "WSR_ROUTES_REF",
                    "file:%s/%s".formatted(ROUTES_PATH, source.getConfigMapRef().getMain())));
        }
    }

    /** WSR reads a token from the environment variable that the second variable names. */
    private static void tokenEnv(WanakuTypes.SecretKeyRef ref, String tokenVar, String pointerVar, List<EnvVar> env) {
        if (ref == null || StringHelper.isBlank(ref.getName())) {
            return;
        }
        env.add(new EnvVarBuilder()
                .withName(tokenVar)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(ref.getName())
                .withKey(StringHelper.isNotEmpty(ref.getKey()) ? ref.getKey() : "token")
                .endSecretKeyRef()
                .endValueFrom()
                .build());
        env.add(value(pointerVar, tokenVar));
    }

    private static Probe probe(String path, int periodSeconds, int failureThreshold) {
        return new ProbeBuilder()
                .withNewHttpGet()
                .withPath(path)
                .withPort(new IntOrString("status"))
                .endHttpGet()
                .withPeriodSeconds(periodSeconds)
                .withTimeoutSeconds(5)
                .withFailureThreshold(failureThreshold)
                .build();
    }

    private static EnvVar value(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }
}
