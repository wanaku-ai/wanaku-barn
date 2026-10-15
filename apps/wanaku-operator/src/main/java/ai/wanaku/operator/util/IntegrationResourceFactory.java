package ai.wanaku.operator.util;

import java.util.ArrayList;
import java.util.List;
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
import ai.wanaku.operator.wanaku.WanakuIntegration;
import ai.wanaku.operator.wanaku.WanakuIntegrationSpec;

import static ai.wanaku.operator.util.RuntimeWorkloads.value;

/** Builds the workload of a Camel Integration Capability (CIC) runtime. */
public final class IntegrationResourceFactory {
    public static final String DEFAULT_IMAGE = "quay.io/wanaku/camel-integration-capability:latest";
    public static final int DEFAULT_MCP_PORT = 9090;
    public static final String COMPONENT = "camel-integration-capability";
    public static final String ROUTES_PATH = "/opt/cic/routes";
    /** The label CIC sets on the forwards it registers. */
    public static final String OWNER_LABEL = "cic.instance";

    private static final String DATA_PATH = "/data";

    private IntegrationResourceFactory() {}

    public static String workloadName(WanakuIntegration resource) {
        return resource.getMetadata().getName() + "-cic";
    }

    public static boolean isRegistrationEnabled(WanakuIntegration resource) {
        final WanakuIntegrationSpec.Registration registration =
                resource.getSpec().getRegistration();
        return registration == null || registration.getEnabled() == null || registration.getEnabled();
    }

    public static String forwardName(WanakuIntegration resource) {
        final WanakuIntegrationSpec.Registration registration =
                resource.getSpec().getRegistration();
        return registration != null && StringHelper.isNotEmpty(registration.getName())
                ? registration.getName()
                : resource.getMetadata().getName();
    }

    public static String forwardNamespace(WanakuIntegration resource) {
        final WanakuIntegrationSpec.Registration registration =
                resource.getSpec().getRegistration();
        return registration != null && StringHelper.isNotEmpty(registration.getNamespace())
                ? registration.getNamespace()
                : "default";
    }

    public static int mcpPort(WanakuIntegration resource) {
        final WanakuIntegrationSpec.Mcp mcp = resource.getSpec().getMcp();
        return mcp != null && mcp.getPort() != null ? mcp.getPort() : DEFAULT_MCP_PORT;
    }

    /** The address the router uses to reach the integration MCP server. */
    public static String mcpAddress(WanakuIntegration resource) {
        return "http://%s.%s.svc:%d/mcp"
                .formatted(workloadName(resource), resource.getMetadata().getNamespace(), mcpPort(resource));
    }

    public static Deployment makeDeployment(WanakuIntegration resource) {
        final WanakuIntegrationSpec spec = resource.getSpec();
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
        if (spec.getRoutes() != null && spec.getRoutes().getConfigMapRef() != null) {
            mounts.add(new VolumeMountBuilder()
                    .withName("routes")
                    .withMountPath(ROUTES_PATH)
                    .withReadOnly(true)
                    .build());
            volumes.add(RuntimeWorkloads.routesVolume(spec.getRoutes().getConfigMapRef()));
        }

        final String image = StringHelper.isNotEmpty(spec.getImage()) ? spec.getImage() : DEFAULT_IMAGE;
        OperatorUtil.validateImageAllowed(image);
        final Container container = new ContainerBuilder()
                .withName("cic")
                .withImage(image)
                .withImagePullPolicy(OperatorUtil.resolveImagePullPolicy(spec.getImagePullPolicy(), null))
                .withEnv(RuntimeWorkloads.mergeEnv(spec.getEnv(), defaultEnv(resource)))
                .addNewPort()
                .withName("mcp")
                .withContainerPort(mcpPort(resource))
                .withProtocol("TCP")
                .endPort()
                // Camel health endpoints on the MCP port; readiness includes the Wanaku registration.
                // The startup probe allows 10 minutes for Maven dependency resolution
                .withStartupProbe(RuntimeWorkloads.httpProbe("/observe/health/live", "mcp", 5, 120))
                .withReadinessProbe(RuntimeWorkloads.httpProbe("/observe/health/ready", "mcp", 5, 3))
                .withLivenessProbe(RuntimeWorkloads.httpProbe("/observe/health/live", "mcp", 20, 3))
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

    public static Service makeService(WanakuIntegration resource) {
        return RuntimeWorkloads.service(resource, workloadName(resource), COMPONENT, mcpPort(resource));
    }

    private static List<EnvVar> defaultEnv(WanakuIntegration resource) {
        final WanakuIntegrationSpec spec = resource.getSpec();
        final String router = spec.getRouterRef();
        final List<EnvVar> env = new ArrayList<>();
        // Barn base URL for datastore:// and service catalog downloads; the capability requires it
        env.add(value("REGISTRATION_URL", RouterResourceFactory.barnUrl(router)));
        env.add(value("SERVICE_NAME", forwardName(resource)));
        env.add(value("DATA_DIR", DATA_PATH));
        env.add(value("MCP_PORT", String.valueOf(mcpPort(resource))));
        if (spec.getMcp() != null && StringHelper.isNotEmpty(spec.getMcp().getTags())) {
            env.add(value("MCP_TAGS", spec.getMcp().getTags()));
        }
        final WanakuIntegrationSpec.Routes routes = spec.getRoutes();
        if (routes != null && routes.getConfigMapRef() != null) {
            env.add(value(
                    "ROUTES_PATH",
                    "file://%s/%s"
                            .formatted(ROUTES_PATH, routes.getConfigMapRef().getMain())));
        }
        if (routes != null && StringHelper.isNotEmpty(routes.getRoutesRef())) {
            env.add(value("ROUTES_PATH", routes.getRoutesRef()));
        }
        if (routes != null && routes.getServiceCatalog() != null) {
            env.add(value("SERVICE_CATALOG", routes.getServiceCatalog().getName()));
            env.add(value("SERVICE_CATALOG_SYSTEM", routes.getServiceCatalog().getSystem()));
        }
        if (spec.getDependencies() != null && !spec.getDependencies().isEmpty()) {
            env.add(value("DEPENDENCIES", String.join(",", spec.getDependencies())));
        }
        if (spec.getRepositories() != null && !spec.getRepositories().isEmpty()) {
            env.add(value("REPOSITORIES", String.join(",", spec.getRepositories())));
        }
        if (isRegistrationEnabled(resource)) {
            env.add(value("WANAKU_REGISTER", "true"));
            env.add(value("WANAKU_URL", RuntimeWorkloads.managementUrl(router)));
            env.add(value("WANAKU_NAMESPACE", forwardNamespace(resource)));
            env.add(value("MCP_ADDRESS", mcpAddress(resource)));
            final WanakuIntegrationSpec.Registration registration = spec.getRegistration();
            // The capability reads the token from the environment variable that WANAKU_TOKEN_ENV names
            if (registration != null
                    && RuntimeWorkloads.addSecretEnv(env, "WANAKU_TOKEN", registration.getTokenSecretRef())) {
                env.add(value("WANAKU_TOKEN_ENV", "WANAKU_TOKEN"));
            }
        }
        return env;
    }
}
