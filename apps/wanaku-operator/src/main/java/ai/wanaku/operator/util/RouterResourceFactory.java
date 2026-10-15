package ai.wanaku.operator.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.javaoperatorsdk.operator.ReconcilerUtilsInternal;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.wanaku.WanakuRouter;
import ai.wanaku.operator.wanaku.WanakuRouterReconciler;
import ai.wanaku.operator.wanaku.WanakuRouterSpec;
import ai.wanaku.operator.wanaku.WanakuTypes;

public final class RouterResourceFactory {
    private static final Logger LOG = Logger.getLogger(RouterResourceFactory.class);

    public static final String BARN_DEPLOYMENT_FILE = "wanaku-barn-deployment.yaml";
    public static final String BARN_INTERNAL_SERVICE_FILE = "wanaku-barn-service-internal.yaml";
    public static final String BARN_EXTERNAL_SERVICE_FILE = "wanaku-barn-service-external.yaml";
    public static final String SERVER_INGRESS_FILE = "wanaku-server-ingress.yaml";
    public static final String SERVER_DEPLOYMENT_FILE = "wanaku-server-deployment.yaml";
    public static final String SERVER_INTERNAL_SERVICE_FILE = "wanaku-server-service-internal.yaml";
    public static final String OAUTH2_PROXY_DEPLOYMENT_FILE = "wanaku-oauth2-proxy-deployment.yaml";
    public static final String OAUTH2_PROXY_SERVICE_FILE = "wanaku-oauth2-proxy-service.yaml";
    public static final String SERVICES_VOLUME_PVC_FILE = "services-volume-pvc.yaml";
    public static final String BARN_VOLUME_CLAIM = "barn-volume-claim";
    public static final String SERVER_VOLUME_CLAIM = "server-volume-claim";

    public static final String DEFAULT_OAUTH2_PROXY_CLIENT_ID = "wanaku-mcp-router";
    public static final String OAUTH2_PROXY_CLIENT_SECRET_KEY = "client-secret";
    public static final String OAUTH2_PROXY_COOKIE_SECRET_KEY = "cookie-secret";
    public static final String AUTH_SECRET_VERSION_ANNOTATION = "wanaku.ai/auth-secret-version";

    private RouterResourceFactory() {}

    public static Deployment makeDesiredBarnDeployment(WanakuRouter resource, Context<WanakuRouter> context) {
        return makeDesiredBarnDeployment(
                resource, context, barnClaimName(resource.getMetadata().getName()));
    }

    /**
     * Builds the Wanaku Barn Deployment.
     *
     * @param claimName the PVC that holds the Barn data
     */
    public static Deployment makeDesiredBarnDeployment(
            WanakuRouter resource, Context<WanakuRouter> context, String claimName) {
        Deployment desiredDeployment =
                ReconcilerUtilsInternal.loadYaml(Deployment.class, WanakuRouterReconciler.class, BARN_DEPLOYMENT_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        desiredDeployment.getMetadata().setName(barnName(deploymentName));
        desiredDeployment.getMetadata().setNamespace(ns);

        final DeploymentSpec serviceSpec = desiredDeployment.getSpec();

        serviceSpec.getSelector().getMatchLabels().put("app", barnName(deploymentName));
        serviceSpec.getSelector().getMatchLabels().put("component", "wanaku-barn-backend");
        serviceSpec.getTemplate().getMetadata().getLabels().put("app", barnName(deploymentName));
        serviceSpec.getTemplate().getMetadata().getLabels().put("component", "wanaku-barn-backend");

        setupBackendContainer(resource, serviceSpec);
        serviceSpec.getTemplate().getSpec().getVolumes().stream()
                .filter(v -> "barn-volume".equals(v.getName()))
                .forEach(v -> v.getPersistentVolumeClaim().setClaimName(claimName));

        managed(desiredDeployment, desiredDeployment.getSpec());
        desiredDeployment.addOwnerReference(resource);
        return desiredDeployment;
    }

    public static Service makeBarnInternalService(WanakuRouter resource) {
        Service service = ReconcilerUtilsInternal.loadYaml(
                Service.class, WanakuRouterReconciler.class, BARN_INTERNAL_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        LOG.infof("Creating internal service for barn-backend: %s", deploymentName);
        service.getMetadata().setName("barn-" + deploymentName);
        service.getMetadata().setNamespace(ns);
        service.getMetadata().getLabels().put("app", barnName(deploymentName));
        service.getMetadata().getLabels().put("component", "wanaku-barn-backend");

        ServiceSpec serviceSpec = service.getSpec();
        serviceSpec.setSelector(Map.of("app", barnName(deploymentName), "component", "wanaku-barn-backend"));

        managed(service, service.getSpec());
        service.addOwnerReference(resource);
        return service;
    }

    /**
     * Creates the management or the MCP Route. Without a host, OpenShift generates a separate host for each Route,
     * so the MCP Route takes every path: MCP clients also need the OAuth discovery endpoints ({@code /.well-known/*},
     * {@code /authorize}, {@code /token}, {@code /register}) and the namespace endpoints ({@code /<namespace>/mcp}).
     * With a shared host, the MCP Route takes only {@code /mcp}, because the management Route takes {@code /}.
     */
    public static Route makeServerRoute(WanakuRouter resource, String host, boolean mcp) {
        String name = resource.getMetadata().getName();
        boolean authEnabled = isAuthEnabled(resource);
        String service = authEnabled ? oauth2ProxyServiceName(name) : "server-" + name;
        int port = mcp ? (authEnabled ? 4180 : 8081) : authEnabled ? 4181 : 8080;
        Route route = new RouteBuilder()
                .withNewMetadata()
                .withName(mcp ? name + "-mcp" : name)
                .withNamespace(resource.getMetadata().getNamespace())
                .addToLabels("app", serverName(name))
                .addToLabels("component", "wanaku-server")
                .endMetadata()
                .withNewSpec()
                .withHost(host)
                .withPath(mcp && StringHelper.isNotEmpty(host) ? "/mcp" : "/")
                .withNewTo()
                .withKind("Service")
                .withName(service)
                .withWeight(100)
                .endTo()
                .withNewPort()
                .withNewTargetPort(port)
                .endPort()
                .withNewTls()
                .withTermination("edge")
                .withInsecureEdgeTerminationPolicy("Redirect")
                .endTls()
                .withWildcardPolicy("None")
                .endSpec()
                .build();
        if (resource.getSpec().getExposure() != null) {
            route.getMetadata().setAnnotations(resource.getSpec().getExposure().getAnnotations());
        }
        route.addOwnerReference(resource);
        return route;
    }

    public static final String EXPOSURE_AUTO = "Auto";
    public static final String EXPOSURE_ROUTE = "Route";
    public static final String EXPOSURE_INGRESS = "Ingress";
    public static final String EXPOSURE_NONE = "None";
    public static final String LISTENER_LABEL = "wanaku.ai/listener";

    /** Where the Barn PVC is mounted; also the Barn persistence base folder. */
    public static final String BARN_DATA_PATH = "/home/default/.wanaku/barn";

    public static final String SERVER_CONFIG_HASH_ANNOTATION = "wanaku.ai/server-config-hash";
    public static final String BARN_PLUGIN_ID = "wanaku-barn";
    public static final String BARN_PLUGIN_SERVICE = "wanaku-barn-api";

    /**
     * Returns the Barn settings: {@code spec.barn}, or the deprecated {@code spec.router} when
     * {@code spec.barn} is unset.
     */
    public static WanakuRouterSpec.RouterSpec barnSpec(WanakuRouter resource) {
        return resource.getSpec().getBarn() != null
                ? resource.getSpec().getBarn()
                : resource.getSpec().getRouter();
    }

    public static boolean isBarnEnabled(WanakuRouter resource) {
        final WanakuRouterSpec.RouterSpec barn = barnSpec(resource);
        return barn != null && barn.isEnabled();
    }

    /** The default name of the Barn PVC for a router. */
    public static String barnClaimName(String routerName) {
        return routerName + "-barn-data";
    }

    /** The in-cluster URL of the Barn API for a router. */
    public static String barnUrl(String routerName) {
        return OperatorUtil.getRouterBaseUrl(routerName);
    }

    public static String serverConfigMapName(String routerName) {
        return routerName + "-server-config";
    }

    /**
     * Builds the {@code wanaku.yaml} the Server reads at startup. It points the Barn plugin service at the
     * Barn API, so the Server proxies {@code /api/plugins/wanaku-barn/wanaku-barn-api/...} to Barn.
     */
    public static ConfigMap makeServerConfigMap(WanakuRouter resource) {
        final String name = resource.getMetadata().getName();
        final ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(serverConfigMapName(name))
                .withNamespace(resource.getMetadata().getNamespace())
                .addToLabels("app", serverName(name))
                .addToLabels("component", "wanaku-server")
                .endMetadata()
                .addToData("wanaku.yaml", serverConfig(name))
                .build();
        managed(configMap, configMap.getData());
        configMap.addOwnerReference(resource);
        return configMap;
    }

    private static String serverConfig(String routerName) {
        return """
                plugins:
                  - id: %s
                    services:
                      %s:
                        target: %s
                """
                .formatted(BARN_PLUGIN_ID, BARN_PLUGIN_SERVICE, barnUrl(routerName));
    }

    public static String exposureType(WanakuRouter resource) {
        final WanakuTypes.ExposureSpec exposure = resource.getSpec().getExposure();
        return exposure != null && StringHelper.isNotEmpty(exposure.getType()) ? exposure.getType() : EXPOSURE_AUTO;
    }

    /** True when spec.exposure.listeners is set: each exposed listener gets its own host. */
    public static boolean isPerListenerExposure(WanakuRouter resource) {
        final WanakuTypes.ExposureSpec exposure = resource.getSpec().getExposure();
        return exposure != null && exposure.getListeners() != null;
    }

    /** The listeners exposed in per-listener mode, with their configured host (empty when generated). */
    public static Map<ServerListener, String> exposedListeners(WanakuRouter resource) {
        final Map<ServerListener, String> exposed = new java.util.EnumMap<>(ServerListener.class);
        if (!isPerListenerExposure(resource) || EXPOSURE_NONE.equals(exposureType(resource))) {
            return exposed;
        }
        final WanakuTypes.ListenersSpec listeners =
                resource.getSpec().getExposure().getListeners();
        addListener(exposed, ServerListener.MANAGEMENT, listeners.getManagement(), true);
        addListener(exposed, ServerListener.MCP, listeners.getMcp(), true);
        addListener(exposed, ServerListener.INFERENCE, listeners.getInference(), false);
        addListener(exposed, ServerListener.A2A, listeners.getA2a(), false);
        return exposed;
    }

    private static void addListener(
            Map<ServerListener, String> exposed,
            ServerListener listener,
            WanakuTypes.ListenerSpec spec,
            boolean enabledByDefault) {
        final boolean enabled = spec != null && spec.getEnabled() != null ? spec.getEnabled() : enabledByDefault;
        if (enabled) {
            exposed.put(listener, spec != null && spec.getHost() != null ? spec.getHost() : "");
        }
    }

    /** Builds the per-listener Route {@code <router>-<listener>} for the root path of its own host. */
    public static Route makeListenerRoute(WanakuRouter resource, ServerListener listener, String host) {
        final String name = resource.getMetadata().getName();
        final boolean authEnabled = isAuthEnabled(resource);
        Route route = new RouteBuilder()
                .withNewMetadata()
                .withName(name + "-" + listener.id())
                .withNamespace(resource.getMetadata().getNamespace())
                .addToLabels("app", serverName(name))
                .addToLabels("component", "wanaku-server")
                .addToLabels(LISTENER_LABEL, listener.id())
                .endMetadata()
                .withNewSpec()
                .withHost(StringHelper.isNotEmpty(host) ? host : null)
                .withPath("/")
                .withNewTo()
                .withKind("Service")
                .withName(authEnabled ? oauth2ProxyServiceName(name) : "server-" + name)
                .withWeight(100)
                .endTo()
                .withNewPort()
                .withNewTargetPort(authEnabled ? listener.proxyPort() : listener.serverPort())
                .endPort()
                .withNewTls()
                .withTermination("edge")
                .withInsecureEdgeTerminationPolicy("Redirect")
                .endTls()
                .withWildcardPolicy("None")
                .endSpec()
                .build();
        route.getMetadata().setAnnotations(resource.getSpec().getExposure().getAnnotations());
        route.addOwnerReference(resource);
        return route;
    }

    /** Builds the per-listener Ingress {@code <router>-<listener>} for the root path of its own host. */
    public static Ingress makeListenerIngress(WanakuRouter resource, ServerListener listener, String host) {
        final String name = resource.getMetadata().getName();
        final boolean authEnabled = isAuthEnabled(resource);
        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                .withName(name + "-" + listener.id())
                .withNamespace(resource.getMetadata().getNamespace())
                .addToLabels("app", serverName(name))
                .addToLabels("component", "wanaku-server")
                .addToLabels(LISTENER_LABEL, listener.id())
                .endMetadata()
                .withNewSpec()
                .addNewRule()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath("/")
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName(authEnabled ? oauth2ProxyServiceName(name) : "server-" + name)
                .withNewPort()
                .withNumber(authEnabled ? listener.proxyPort() : listener.serverPort())
                .endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build();
        applyIngressExtras(ingress, resource.getSpec().getExposure());
        ingress.addOwnerReference(resource);
        return ingress;
    }

    public static Ingress makeServerIngress(WanakuRouter resource, String host) {
        Ingress ingress =
                ReconcilerUtilsInternal.loadYaml(Ingress.class, WanakuRouterReconciler.class, SERVER_INGRESS_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        LOG.infof("Creating ingress for Wanaku Server: %s", deploymentName);
        ingress.getMetadata().setName(deploymentName);
        ingress.getMetadata().setNamespace(ns);
        ingress.getMetadata().getLabels().put("app", serverName(deploymentName));
        ingress.getMetadata().getLabels().put("component", "wanaku-server");

        String backendServiceName =
                isAuthEnabled(resource) ? oauth2ProxyServiceName(deploymentName) : "server-" + deploymentName;
        int backendMcpServicePort = isAuthEnabled(resource) ? 4180 : 8081;
        int backendMgmtServicePort = isAuthEnabled(resource) ? 4181 : 8080;

        ingress.getSpec().getRules().getFirst().setHost(host);

        // configure the /mcp
        HTTPIngressPath mcpIngress =
                ingress.getSpec().getRules().getFirst().getHttp().getPaths().getFirst();
        mcpIngress.getBackend().getService().setName(backendServiceName);
        mcpIngress.setPath("/mcp");
        mcpIngress.getBackend().getService().getPort().setNumber(backendMcpServicePort);

        // configure the management (web) path
        HTTPIngressPath mgmtIngress =
                ingress.getSpec().getRules().getFirst().getHttp().getPaths().getLast();
        mgmtIngress.getBackend().getService().setName(backendServiceName);
        mgmtIngress.setPath("/");
        mgmtIngress.getBackend().getService().getPort().setNumber(backendMgmtServicePort);

        applyIngressExtras(ingress, resource.getSpec().getExposure());
        ingress.addOwnerReference(resource);
        return ingress;
    }

    private static void applyIngressExtras(Ingress ingress, WanakuTypes.ExposureSpec exposureSpec) {
        if (exposureSpec == null) {
            return;
        }
        if (StringHelper.isNotEmpty(exposureSpec.getIngressClassName())) {
            ingress.getSpec().setIngressClassName(exposureSpec.getIngressClassName());
        }
        if (exposureSpec.getAnnotations() != null
                && !exposureSpec.getAnnotations().isEmpty()) {
            Map<String, String> merged = new HashMap<>();
            if (ingress.getMetadata().getAnnotations() != null) {
                merged.putAll(ingress.getMetadata().getAnnotations());
            }
            merged.putAll(exposureSpec.getAnnotations());
            ingress.getMetadata().setAnnotations(merged);
        }
        IngressTLS ingressTls = new IngressTLS();
        // intentionally empty to let the kubernetes platform set to the default hostname
        // minikube, openshift defaults
        // ingressTls.setHosts(List.of("{}"));
        ingress.getSpec().setTls(List.of(ingressTls));
    }

    public static PersistentVolumeClaim makeBarnVolumePVC(WanakuRouter resource) {
        return makeBarnVolumePVC(resource, barnClaimName(resource.getMetadata().getName()));
    }

    /** Builds the Barn PVC with the size and storage class from {@code spec.barn.storage}. */
    public static PersistentVolumeClaim makeBarnVolumePVC(WanakuRouter resource, String claimName) {
        PersistentVolumeClaim pvc = ReconcilerUtilsInternal.loadYaml(
                PersistentVolumeClaim.class, WanakuRouterReconciler.class, SERVICES_VOLUME_PVC_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        final WanakuRouterSpec.StorageSpec storage = resource.getSpec().getBarn() != null
                ? resource.getSpec().getBarn().getStorage()
                : null;
        if (storage != null && StringHelper.isNotEmpty(storage.getSize())) {
            pvc.getSpec().getResources().getRequests().put("storage", new Quantity(storage.getSize()));
        }
        if (storage != null && StringHelper.isNotEmpty(storage.getStorageClassName())) {
            pvc.getSpec().setStorageClassName(storage.getStorageClassName());
        }

        pvc.getMetadata().setName(claimName);
        pvc.getMetadata().setNamespace(ns);
        pvc.getMetadata().getLabels().put("app", barnName(deploymentName));
        pvc.getMetadata().getLabels().put("component", "wanaku-barn-storage");

        pvc.addOwnerReference(resource);
        return pvc;
    }

    private static void setupBackendContainer(WanakuRouter resource, DeploymentSpec spec) {
        final List<Container> containers = spec.getTemplate().getSpec().getContainers();

        final Container service = containers.stream()
                .filter(c -> c.getName().equals("wanaku-barn"))
                .findFirst()
                .get();

        List<EnvVar> envVars = new java.util.ArrayList<>();

        final WanakuRouterSpec.RouterSpec routerSpec = barnSpec(resource);

        String componentPolicy = routerSpec != null ? routerSpec.getImagePullPolicy() : null;
        String globalPolicy = resource.getSpec().getImagePullPolicy();
        String resolvedPolicy = OperatorUtil.resolveImagePullPolicy(componentPolicy, globalPolicy);
        service.setImagePullPolicy(resolvedPolicy);

        if (routerSpec != null) {
            final String image = routerSpec.getImage();
            if (image != null) {
                OperatorUtil.validateImageAllowed(image);
                service.setImage(image);
            }

            if (routerSpec.getEnv() != null && !routerSpec.getEnv().isEmpty()) {
                for (WanakuTypes.EnvVar env : routerSpec.getEnv()) {
                    envVars.add(new EnvVarBuilder()
                            .withName(env.getName())
                            .withValue(env.getValue())
                            .build());
                }
            }
        }

        final List<EnvVar> templateEnvs = service.getEnv();
        for (EnvVar templateVar : templateEnvs) {
            final Optional<EnvVar> override = envVars.stream()
                    .filter(envVar -> envVar.getName().equals(templateVar.getName()))
                    .findFirst();

            if (override.isEmpty()) {
                envVars.add(templateVar);
            }
        }

        EnvironmentVariableHelper.applyAnnotationEnvVars(
                envVars, resource.getMetadata().getAnnotations());

        // Do not depend on user.home: OpenShift runs the image with an arbitrary UID that may have no home
        addIfAbsent(envVars, envVar("WANAKU_PERSISTENCE_INFINISPAN_BASE_FOLDER", BARN_DATA_PATH + "/"));

        service.setEnv(envVars);
    }

    public static PersistentVolumeClaim makeServerVolumePVC(WanakuRouter resource) {
        PersistentVolumeClaim pvc = ReconcilerUtilsInternal.loadYaml(
                PersistentVolumeClaim.class, WanakuRouterReconciler.class, SERVICES_VOLUME_PVC_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        pvc.getMetadata().setName(SERVER_VOLUME_CLAIM);
        pvc.getMetadata().setNamespace(ns);
        pvc.getMetadata().getLabels().put("app", serverName(deploymentName));
        pvc.getMetadata().getLabels().put("component", "wanaku-server-storage");

        pvc.addOwnerReference(resource);
        return pvc;
    }

    public static Deployment makeDesiredServerDeployment(WanakuRouter resource, Context<WanakuRouter> context) {
        return makeDesiredServerDeployment(resource, context, null);
    }

    /**
     * Builds the Wanaku Server Deployment.
     *
     * @param a2aPublicUrl the public URL of the A2A listener, advertised in agent proxy URLs (may be null)
     */
    public static Deployment makeDesiredServerDeployment(
            WanakuRouter resource, Context<WanakuRouter> context, String a2aPublicUrl) {
        Deployment desiredDeployment = ReconcilerUtilsInternal.loadYaml(
                Deployment.class, WanakuRouterReconciler.class, SERVER_DEPLOYMENT_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        desiredDeployment.getMetadata().setName(serverName(deploymentName));
        desiredDeployment.getMetadata().setNamespace(ns);

        final DeploymentSpec serviceSpec = desiredDeployment.getSpec();
        serviceSpec.getSelector().getMatchLabels().put("app", serverName(deploymentName));
        serviceSpec.getSelector().getMatchLabels().put("component", "wanaku-server");
        serviceSpec.getTemplate().getMetadata().getLabels().put("app", serverName(deploymentName));
        serviceSpec.getTemplate().getMetadata().getLabels().put("component", "wanaku-server");

        final Container serverContainer = serviceSpec.getTemplate().getSpec().getContainers().stream()
                .filter(c -> c.getName().equals("wanaku-server"))
                .findFirst()
                .get();

        List<EnvVar> envVars = new java.util.ArrayList<>();

        final WanakuRouterSpec.ServerSpec serverSpec = resource.getSpec().getServer();
        if (serverSpec != null) {
            if (serverSpec.getImage() != null && !serverSpec.getImage().isEmpty()) {
                OperatorUtil.validateImageAllowed(serverSpec.getImage());
                serverContainer.setImage(serverSpec.getImage());
            }

            String componentPolicy = serverSpec.getImagePullPolicy();
            String globalPolicy = resource.getSpec().getImagePullPolicy();
            serverContainer.setImagePullPolicy(OperatorUtil.resolveImagePullPolicy(componentPolicy, globalPolicy));

            if (serverSpec.getEnv() != null) {
                for (WanakuTypes.EnvVar env : serverSpec.getEnv()) {
                    envVars.add(new EnvVarBuilder()
                            .withName(env.getName())
                            .withValue(env.getValue())
                            .build());
                }
            }
        }

        if (a2aPublicUrl != null) {
            addIfAbsent(envVars, envVar("WANAKU_A2A_PUBLIC_URL", a2aPublicUrl));
        }

        if (isAuthEnabled(resource)) {
            // Lets MCP clients discover the authorization server (RFC 9728 metadata on the MCP listener)
            final WanakuRouterSpec.AuthSpec authSpec = resource.getSpec().getAuth();
            addIfAbsent(envVars, envVar("WANAKU_AUTH_ISSUER", issuerUrl(authSpec)));
            if (StringHelper.isNotEmpty(authSpec.getInternalIssuerUrl())) {
                addIfAbsent(envVars, envVar("WANAKU_AUTH_UPSTREAM_ISSUER", internalIssuerUrl(authSpec)));
            }
        }

        final List<EnvVar> templateEnvs = serverContainer.getEnv();
        for (EnvVar templateVar : templateEnvs) {
            final Optional<EnvVar> override = envVars.stream()
                    .filter(envVar -> envVar.getName().equals(templateVar.getName()))
                    .findFirst();
            if (override.isEmpty()) {
                envVars.add(templateVar);
            }
        }
        serverContainer.setEnv(envVars);

        if (isBarnEnabled(resource)) {
            // Mount only the file: the image keeps its other files in /etc/wanaku
            serverContainer
                    .getVolumeMounts()
                    .add(new VolumeMountBuilder()
                            .withName("server-config")
                            .withMountPath("/etc/wanaku/wanaku.yaml")
                            .withSubPath("wanaku.yaml")
                            .withReadOnly(true)
                            .build());
            serviceSpec
                    .getTemplate()
                    .getSpec()
                    .getVolumes()
                    .add(new VolumeBuilder()
                            .withName("server-config")
                            .withNewConfigMap()
                            .withName(serverConfigMapName(deploymentName))
                            .endConfigMap()
                            .build());
            // A subPath mount is not refreshed in running pods, so a config change rolls the Server
            final Map<String, String> podAnnotations = new HashMap<>();
            if (serviceSpec.getTemplate().getMetadata().getAnnotations() != null) {
                podAnnotations.putAll(serviceSpec.getTemplate().getMetadata().getAnnotations());
            }
            podAnnotations.put(
                    SERVER_CONFIG_HASH_ANNOTATION,
                    Integer.toHexString(serverConfig(deploymentName).hashCode()));
            serviceSpec.getTemplate().getMetadata().setAnnotations(podAnnotations);
        }

        managed(desiredDeployment, desiredDeployment.getSpec());
        desiredDeployment.addOwnerReference(resource);
        return desiredDeployment;
    }

    public static Service makeServerInternalService(WanakuRouter resource) {
        Service service = ReconcilerUtilsInternal.loadYaml(
                Service.class, WanakuRouterReconciler.class, SERVER_INTERNAL_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        service.getMetadata().setName("server-" + deploymentName);
        service.getMetadata().setNamespace(ns);
        service.getMetadata().getLabels().put("app", serverName(deploymentName));
        service.getMetadata().getLabels().put("component", "wanaku-server");

        ServiceSpec serviceSpec = service.getSpec();
        serviceSpec.setSelector(Map.of("app", serverName(deploymentName), "component", "wanaku-server"));

        managed(service, service.getSpec());
        service.addOwnerReference(resource);
        return service;
    }

    public static boolean isAuthEnabled(WanakuRouter resource) {
        final WanakuRouterSpec.AuthSpec auth =
                resource.getSpec() != null ? resource.getSpec().getAuth() : null;
        return auth != null && auth.isEnabled();
    }

    public static Service makeOauth2ProxyService(WanakuRouter resource) {
        return makeOauth2ProxyService(resource, List.of());
    }

    /**
     * Builds the oauth2-proxy Service.
     *
     * @param resource the router
     * @param extraListeners inference or A2A listeners whose proxy ports the Service also exposes
     */
    public static Service makeOauth2ProxyService(WanakuRouter resource, List<ServerListener> extraListeners) {
        Service service = ReconcilerUtilsInternal.loadYaml(
                Service.class, WanakuRouterReconciler.class, OAUTH2_PROXY_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        service.getMetadata().setName(oauth2ProxyServiceName(deploymentName));
        service.getMetadata().setNamespace(ns);
        service.getMetadata().getLabels().put("app", oauth2ProxyName(deploymentName));
        service.getMetadata().getLabels().put("component", "wanaku-oauth2-proxy");

        ServiceSpec serviceSpec = service.getSpec();
        serviceSpec.setSelector(Map.of("app", oauth2ProxyName(deploymentName), "component", "wanaku-oauth2-proxy"));

        for (ServerListener listener : extraListeners) {
            serviceSpec
                    .getPorts()
                    .add(new ServicePortBuilder()
                            .withName(listener.proxyPort() + "-tcp")
                            .withPort(listener.proxyPort())
                            .withProtocol("TCP")
                            .withTargetPort(new IntOrString(listener.proxyPort()))
                            .build());
        }

        managed(service, service.getSpec());
        service.addOwnerReference(resource);
        return service;
    }

    /**
     * Builds the oauth2-proxy Deployment.
     *
     * @param resource the router
     * @param host the public host used for the OAuth redirect URL
     * @param secretVersion the resourceVersion of the auth Secret; changing it rolls the proxy pods, because
     *     environment variables read from a Secret are not refreshed in running pods
     */
    public static Deployment makeOauth2ProxyDeployment(WanakuRouter resource, String host, String secretVersion) {
        return makeOauth2ProxyDeployment(
                resource, Map.of(ServerListener.MANAGEMENT, host, ServerListener.MCP, host), secretVersion);
    }

    /**
     * Builds the oauth2-proxy Deployment with one container per protected listener. The management and
     * MCP proxies always exist; inference and A2A proxies exist when those listeners are exposed.
     *
     * @param resource the router
     * @param hosts the public host of each exposed listener, used for the OAuth redirect URLs
     * @param secretVersion the resourceVersion of the auth Secret (see the other overload)
     */
    public static Deployment makeOauth2ProxyDeployment(
            WanakuRouter resource, Map<ServerListener, String> hosts, String secretVersion) {
        Deployment desiredDeployment = ReconcilerUtilsInternal.loadYaml(
                Deployment.class, WanakuRouterReconciler.class, OAUTH2_PROXY_DEPLOYMENT_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        desiredDeployment.getMetadata().setName(oauth2ProxyName(deploymentName));
        desiredDeployment.getMetadata().setNamespace(ns);

        final DeploymentSpec deploymentSpec = desiredDeployment.getSpec();
        deploymentSpec.getSelector().getMatchLabels().put("app", oauth2ProxyName(deploymentName));
        deploymentSpec.getSelector().getMatchLabels().put("component", "wanaku-oauth2-proxy");
        deploymentSpec.getTemplate().getMetadata().getLabels().put("app", oauth2ProxyName(deploymentName));
        deploymentSpec.getTemplate().getMetadata().getLabels().put("component", "wanaku-oauth2-proxy");

        if (secretVersion != null) {
            Map<String, String> podAnnotations = new HashMap<>();
            if (deploymentSpec.getTemplate().getMetadata().getAnnotations() != null) {
                podAnnotations.putAll(deploymentSpec.getTemplate().getMetadata().getAnnotations());
            }
            podAnnotations.put(AUTH_SECRET_VERSION_ANNOTATION, secretVersion);
            deploymentSpec.getTemplate().getMetadata().setAnnotations(podAnnotations);
        }

        final WanakuRouterSpec.AuthSpec authSpec = resource.getSpec().getAuth();
        final List<Container> containers =
                deploymentSpec.getTemplate().getSpec().getContainers();
        final Container mgmtTemplate = containers.stream()
                .filter(c -> "oauth2-proxy-mgmt".equals(c.getName()))
                .findFirst()
                .orElseThrow();
        for (ServerListener listener : List.of(ServerListener.INFERENCE, ServerListener.A2A)) {
            if (hosts.containsKey(listener)) {
                containers.add(proxyContainerFor(mgmtTemplate, listener));
            }
        }

        for (Container container : containers) {
            final ServerListener listener = proxiedListener(container);
            final String redirectUrl = "https://%s/oauth2/callback"
                    .formatted(hosts.getOrDefault(listener, hosts.get(ServerListener.MANAGEMENT)));
            if (authSpec.getImage() != null && !authSpec.getImage().isEmpty()) {
                OperatorUtil.validateImageAllowed(authSpec.getImage());
                container.setImage(authSpec.getImage());
            }
            container.setImagePullPolicy(OperatorUtil.resolveImagePullPolicy(
                    authSpec.getImagePullPolicy(), resource.getSpec().getImagePullPolicy()));

            List<EnvVar> envVars = new java.util.ArrayList<>();
            if (authSpec.getEnv() != null) {
                for (WanakuTypes.EnvVar env : authSpec.getEnv()) {
                    envVars.add(new EnvVarBuilder()
                            .withName(env.getName())
                            .withValue(env.getValue())
                            .build());
                }
            }
            List<EnvVar> defaults =
                    switch (listener) {
                        case MCP -> oauth2ProxyMcpEnv(authSpec, deploymentName, redirectUrl);
                        case MANAGEMENT -> oauth2ProxyMgmtEnv(authSpec, deploymentName, redirectUrl);
                        case INFERENCE, A2A -> oauth2ProxyApiEnv(authSpec, deploymentName, redirectUrl, listener);
                    };
            for (EnvVar defaultVar : defaults) {
                final Optional<EnvVar> override = envVars.stream()
                        .filter(envVar -> envVar.getName().equals(defaultVar.getName()))
                        .findFirst();
                if (override.isEmpty()) {
                    envVars.add(defaultVar);
                }
            }
            container.setEnv(envVars);
        }

        managed(desiredDeployment, desiredDeployment.getSpec());
        desiredDeployment.addOwnerReference(resource);
        return desiredDeployment;
    }

    private static List<EnvVar> oauth2ProxyMcpEnv(
            WanakuRouterSpec.AuthSpec authSpec, String deploymentName, String redirectUrl) {
        List<EnvVar> env = oauth2ProxySharedEnv(authSpec, redirectUrl);
        env.add(envVar("OAUTH2_PROXY_HTTP_ADDRESS", "0.0.0.0:4180"));
        env.add(envVar("OAUTH2_PROXY_UPSTREAMS", "http://server-%s:8081".formatted(deploymentName)));
        env.add(envVar(
                "OAUTH2_PROXY_SKIP_AUTH_ROUTES",
                "^/.well-known/.*,^/public/.*,^/authorize$,^/token$,^/register$,OPTIONS=^/.*"));
        env.add(envVar("OAUTH2_PROXY_API_ROUTES", "^/$,^/mcp/?$,^/[^/]+/mcp/?$"));
        env.add(envVar("OAUTH2_PROXY_UPSTREAM_TIMEOUT", "3600s"));
        return env;
    }

    private static List<EnvVar> oauth2ProxyMgmtEnv(
            WanakuRouterSpec.AuthSpec authSpec, String deploymentName, String redirectUrl) {
        List<EnvVar> env = oauth2ProxySharedEnv(authSpec, redirectUrl);
        env.add(envVar("OAUTH2_PROXY_HTTP_ADDRESS", "0.0.0.0:4181"));
        env.add(envVar("OAUTH2_PROXY_UPSTREAMS", "http://server-%s:8080".formatted(deploymentName)));
        env.add(envVar("OAUTH2_PROXY_SKIP_AUTH_ROUTES", "^/healthz$,^/health$"));
        env.add(envVar("OAUTH2_PROXY_API_ROUTES", "^/api/.*"));
        return env;
    }

    /** Inference and A2A clients are programs, so every path is an API route that returns 401 without a token. */
    private static List<EnvVar> oauth2ProxyApiEnv(
            WanakuRouterSpec.AuthSpec authSpec, String deploymentName, String redirectUrl, ServerListener listener) {
        List<EnvVar> env = oauth2ProxySharedEnv(authSpec, redirectUrl);
        env.add(envVar("OAUTH2_PROXY_HTTP_ADDRESS", "0.0.0.0:" + listener.proxyPort()));
        env.add(envVar(
                "OAUTH2_PROXY_UPSTREAMS", "http://server-%s:%d".formatted(deploymentName, listener.serverPort())));
        env.add(envVar("OAUTH2_PROXY_API_ROUTES", "^/.*"));
        if (listener == ServerListener.A2A) {
            // Agent cards are public discovery documents
            env.add(envVar("OAUTH2_PROXY_SKIP_AUTH_ROUTES", "^/[^/]+/a2a/[^/]+/\\.well-known/.*,OPTIONS=^/.*"));
        } else {
            env.add(envVar("OAUTH2_PROXY_SKIP_AUTH_ROUTES", "OPTIONS=^/.*"));
        }
        env.add(envVar("OAUTH2_PROXY_UPSTREAM_TIMEOUT", "3600s"));
        return env;
    }

    private static Container proxyContainerFor(Container template, ServerListener listener) {
        final IntOrString port = new IntOrString(listener.proxyPort());
        final Container container = new ContainerBuilder(template)
                .withName("oauth2-proxy-" + listener.id())
                .withPorts(new ContainerPortBuilder()
                        .withContainerPort(listener.proxyPort())
                        .withProtocol("TCP")
                        .build())
                .build();
        container.getReadinessProbe().getHttpGet().setPort(port);
        container.getLivenessProbe().getHttpGet().setPort(port);
        return container;
    }

    private static ServerListener proxiedListener(Container container) {
        for (ServerListener listener : ServerListener.values()) {
            if (("oauth2-proxy-" + listener.id()).equals(container.getName())) {
                return listener;
            }
        }
        // The template names the management proxy container oauth2-proxy-mgmt
        return ServerListener.MANAGEMENT;
    }

    private static List<EnvVar> oauth2ProxySharedEnv(WanakuRouterSpec.AuthSpec authSpec, String redirectUrl) {
        String clientId = StringHelper.isNotEmpty(authSpec.getClientId())
                ? authSpec.getClientId()
                : DEFAULT_OAUTH2_PROXY_CLIENT_ID;

        final String issuer = issuerUrl(authSpec);
        final String internalIssuer = internalIssuerUrl(authSpec);

        List<EnvVar> env = new java.util.ArrayList<>();
        env.add(envVar("OAUTH2_PROXY_PROVIDER", "keycloak-oidc"));
        env.add(envVar("OAUTH2_PROXY_OIDC_ISSUER_URL", issuer));
        // The public and in-cluster issuer URLs may differ, so skip the OIDC discovery: the browser uses the
        // public login URL, and the proxy calls the token and JWKS endpoints through the internal URL.
        // Each one can be overridden through spec.auth.env
        env.add(envVar("OAUTH2_PROXY_SKIP_OIDC_DISCOVERY", "true"));
        env.add(envVar("OAUTH2_PROXY_LOGIN_URL", issuer + "/protocol/openid-connect/auth"));
        env.add(envVar("OAUTH2_PROXY_REDEEM_URL", internalIssuer + "/protocol/openid-connect/token"));
        env.add(envVar("OAUTH2_PROXY_OIDC_JWKS_URL", internalIssuer + "/protocol/openid-connect/certs"));
        env.add(envVar("OAUTH2_PROXY_CLIENT_ID", clientId));
        env.add(secretEnvVar("OAUTH2_PROXY_CLIENT_SECRET", authSpec.getSecretName(), OAUTH2_PROXY_CLIENT_SECRET_KEY));
        env.add(secretEnvVar("OAUTH2_PROXY_COOKIE_SECRET", authSpec.getSecretName(), OAUTH2_PROXY_COOKIE_SECRET_KEY));
        env.add(envVar("OAUTH2_PROXY_REDIRECT_URL", redirectUrl));
        env.add(envVar("OAUTH2_PROXY_EMAIL_DOMAINS", "*"));
        env.add(envVar("OAUTH2_PROXY_CODE_CHALLENGE_METHOD", "S256"));
        env.add(envVar("OAUTH2_PROXY_SKIP_JWT_BEARER_TOKENS", "true"));
        env.add(envVar("OAUTH2_PROXY_PASS_AUTHORIZATION_HEADER", "true"));
        env.add(envVar("OAUTH2_PROXY_PASS_USER_HEADERS", "true"));
        env.add(envVar("OAUTH2_PROXY_SET_XAUTHREQUEST", "true"));
        env.add(envVar("OAUTH2_PROXY_OIDC_EXTRA_AUDIENCES", "mcp-client,wanaku-mcp-client"));
        env.add(envVar("OAUTH2_PROXY_INSECURE_OIDC_ALLOW_UNVERIFIED_EMAIL", "true"));
        return env;
    }

    /** Realm appended to issuer URLs that use the deprecated format without a realm path. */
    public static final String LEGACY_REALM_PATH = "/realms/wanaku";

    /**
     * Returns the full OIDC issuer URL. Issuer URLs without a {@code /realms/} path use the deprecated
     * format, which named only the Keycloak base URL; the operator appends the {@code wanaku} realm to them.
     */
    public static String issuerUrl(WanakuRouterSpec.AuthSpec authSpec) {
        final String issuer = trimTrailingSlash(authSpec.getIssuerUrl());
        return isLegacyIssuerUrl(authSpec) ? issuer + LEGACY_REALM_PATH : issuer;
    }

    public static boolean isLegacyIssuerUrl(WanakuRouterSpec.AuthSpec authSpec) {
        return authSpec.getIssuerUrl() != null && !authSpec.getIssuerUrl().contains("/realms/");
    }

    /** Returns the in-cluster issuer URL, or the public issuer URL when no internal URL is set. */
    public static String internalIssuerUrl(WanakuRouterSpec.AuthSpec authSpec) {
        return StringHelper.isNotEmpty(authSpec.getInternalIssuerUrl())
                ? trimTrailingSlash(authSpec.getInternalIssuerUrl())
                : issuerUrl(authSpec);
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void addIfAbsent(List<EnvVar> envVars, EnvVar envVar) {
        if (envVars.stream().noneMatch(existing -> existing.getName().equals(envVar.getName()))) {
            envVars.add(envVar);
        }
    }

    private static EnvVar envVar(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    private static EnvVar secretEnvVar(String name, String secretName, String key) {
        return new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secretName)
                .withKey(key)
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    private static void managed(HasMetadata desired, Object spec) {
        Map<String, String> labels = new HashMap<>();
        if (desired.getMetadata().getLabels() != null) {
            labels.putAll(desired.getMetadata().getLabels());
        }
        labels.put(OperatorUtil.MANAGED_BY_LABEL, OperatorUtil.MANAGED_BY_VALUE);
        desired.getMetadata().setLabels(labels);
        OperatorUtil.stampDesiredHash(desired, spec);
    }

    private static String barnName(String deploymentName) {
        return deploymentName + "-barn";
    }

    private static String serverName(String deploymentName) {
        return deploymentName + "-server";
    }

    private static String oauth2ProxyName(String deploymentName) {
        return deploymentName + "-oauth2-proxy";
    }

    public static String oauth2ProxyServiceName(String deploymentName) {
        return "oauth2-proxy-" + deploymentName;
    }
}
