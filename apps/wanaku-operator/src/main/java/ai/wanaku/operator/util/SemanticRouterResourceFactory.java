package ai.wanaku.operator.util;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.wanaku.WanakuSemanticRouter;
import ai.wanaku.operator.wanaku.WanakuSemanticRouterSpec;
import ai.wanaku.operator.wanaku.WanakuTypes;

import static ai.wanaku.operator.util.RuntimeWorkloads.value;

/** Builds the workload of a Wanaku Semantic Router (WSR) runtime. */
public final class SemanticRouterResourceFactory {
    public static final String DEFAULT_IMAGE = "quay.io/wanaku/wanaku-semantic-router:latest";
    public static final int MCP_PORT = 8090;
    public static final int STATUS_PORT = 8091;
    public static final String COMPONENT = "wanaku-semantic-router";
    public static final String ROUTES_PATH = "/opt/wsr/routes";
    /** The label WSR sets on the forwards it registers. */
    public static final String OWNER_LABEL = "wsr.instance";

    private static final String DATA_PATH = "/home/wsr/data";

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
            volumes.add(RuntimeWorkloads.routesVolume(configMap));
        }

        final String image = StringHelper.isNotEmpty(spec.getImage()) ? spec.getImage() : DEFAULT_IMAGE;
        OperatorUtil.validateImageAllowed(image);
        final Container container = new ContainerBuilder()
                .withName("wsr")
                .withImage(image)
                .withImagePullPolicy(OperatorUtil.resolveImagePullPolicy(spec.getImagePullPolicy(), null))
                .withArgs(args(spec))
                .withEnv(RuntimeWorkloads.mergeEnv(spec.getEnv(), defaultEnv(resource, barnEnabled)))
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
                // Probe contract: wanaku-ai/wanaku-semantic-router docs/deployment.md, "Health probes".
                // The startup probe allows 10 minutes for Maven dependency resolution
                .withStartupProbe(RuntimeWorkloads.httpProbe("/api/v1/health/started", "status", 5, 120))
                .withReadinessProbe(RuntimeWorkloads.httpProbe("/api/v1/health/ready", "status", 5, 3))
                .withLivenessProbe(RuntimeWorkloads.httpProbe("/api/v1/health/live", "status", 20, 3))
                .withTerminationMessagePolicy("FallbackToLogsOnError")
                .withNewSecurityContext()
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .endSecurityContext()
                .withVolumeMounts(mounts)
                .build();
        return RuntimeWorkloads.deployment(resource, workloadName(resource), COMPONENT, container, volumes);
    }

    public static Service makeService(WanakuSemanticRouter resource) {
        return RuntimeWorkloads.service(resource, workloadName(resource), COMPONENT, MCP_PORT);
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

    private static List<EnvVar> defaultEnv(WanakuSemanticRouter resource, boolean barnEnabled) {
        final WanakuSemanticRouterSpec spec = resource.getSpec();
        final String router = spec.getRouterRef();
        final List<EnvVar> env = new ArrayList<>();
        env.add(value("WSR_REGISTRATION_URL", RuntimeWorkloads.managementUrl(router)));
        if (barnEnabled) {
            env.add(value("WSR_BARN_URL", RouterResourceFactory.barnUrl(router)));
        }
        env.add(value("WSR_NAME", forwardName(resource)));
        env.add(value("WSR_NAMESPACE", forwardNamespace(resource)));
        env.add(value("WSR_MCP_ADDRESS", mcpAddress(resource)));
        env.add(value("WSR_BIND", RuntimeWorkloads.ALL_INTERFACES));
        env.add(value("WSR_MCP_PORT", String.valueOf(MCP_PORT)));
        env.add(value("WSR_STATUS_BIND", RuntimeWorkloads.ALL_INTERFACES));
        env.add(value("WSR_STATUS_PORT", String.valueOf(STATUS_PORT)));
        env.add(value("WSR_DATA_DIR", DATA_PATH));
        if (spec.getDependencies() != null && !spec.getDependencies().isEmpty()) {
            env.add(value("WSR_DEPENDENCIES", String.join(",", spec.getDependencies())));
        }
        sourceEnv(spec.getSource(), env);
        // WSR reads each token from the environment variable that the *_TOKEN_ENV variable names
        if (RuntimeWorkloads.addSecretEnv(env, "WANAKU_TOKEN", spec.getWanakuTokenSecretRef())) {
            env.add(value("WSR_WANAKU_TOKEN_ENV", "WANAKU_TOKEN"));
        }
        if (RuntimeWorkloads.addSecretEnv(env, "BARN_TOKEN", spec.getBarnTokenSecretRef())) {
            env.add(value("WSR_BARN_TOKEN_ENV", "BARN_TOKEN"));
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
}
