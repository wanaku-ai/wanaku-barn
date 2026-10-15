package ai.wanaku.operator.wanaku;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Replaceable;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteIngressCondition;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import io.quarkiverse.operatorsdk.annotations.RBACVerbs;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.util.Events;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;
import ai.wanaku.operator.util.ServerListener;
import ai.wanaku.operator.util.Workloads;

import static ai.wanaku.operator.util.Matchers.match;

@ControllerConfiguration(informer = @Informer(namespaces = Constants.WATCH_CURRENT_NAMESPACE), name = "wanaku-router")
@CSVMetadata(displayName = "Wanaku Router operator", description = "Deploys and manages the Wanaku Router")
@RBACRule(
        apiGroups = "",
        resources = {"persistentvolumeclaims", "services", "configmaps", "secrets", "serviceaccounts"},
        verbs = {
            RBACVerbs.GET,
            RBACVerbs.LIST,
            RBACVerbs.WATCH,
            RBACVerbs.CREATE,
            RBACVerbs.UPDATE,
            RBACVerbs.PATCH,
            RBACVerbs.DELETE
        })
@RBACRule(
        apiGroups = "apps",
        resources = {"deployments"},
        verbs = {
            RBACVerbs.GET,
            RBACVerbs.LIST,
            RBACVerbs.WATCH,
            RBACVerbs.CREATE,
            RBACVerbs.UPDATE,
            RBACVerbs.PATCH,
            RBACVerbs.DELETE
        })
@RBACRule(
        apiGroups = "route.openshift.io",
        resources = {"routes"},
        verbs = {
            RBACVerbs.GET,
            RBACVerbs.LIST,
            RBACVerbs.WATCH,
            RBACVerbs.CREATE,
            RBACVerbs.UPDATE,
            RBACVerbs.PATCH,
            RBACVerbs.DELETE
        })
@RBACRule(
        apiGroups = "route.openshift.io",
        resources = {"routes/custom-host"},
        verbs = {RBACVerbs.CREATE, RBACVerbs.UPDATE})
@RBACRule(
        apiGroups = "",
        resources = {"pods"},
        verbs = {RBACVerbs.GET, RBACVerbs.LIST, RBACVerbs.WATCH})
@RBACRule(
        apiGroups = "",
        resources = {"events"},
        verbs = {RBACVerbs.CREATE, RBACVerbs.PATCH})
@RBACRule(
        apiGroups = "networking.k8s.io",
        resources = {"ingresses"},
        verbs = {
            RBACVerbs.GET,
            RBACVerbs.LIST,
            RBACVerbs.WATCH,
            RBACVerbs.CREATE,
            RBACVerbs.UPDATE,
            RBACVerbs.PATCH,
            RBACVerbs.DELETE
        })
@RBACRule(
        apiGroups = "rbac.authorization.k8s.io",
        resources = {"roles", "rolebindings"},
        verbs = {
            RBACVerbs.GET,
            RBACVerbs.LIST,
            RBACVerbs.WATCH,
            RBACVerbs.CREATE,
            RBACVerbs.UPDATE,
            RBACVerbs.PATCH,
            RBACVerbs.DELETE
        })
public class WanakuRouterReconciler implements Reconciler<WanakuRouter> {
    private static final Logger LOG = Logger.getLogger(WanakuRouterReconciler.class);
    static final String RECONCILED_CONDITION = "Reconciled";
    static final String AUTH_CONFIGURED_CONDITION = "AuthConfigured";
    static final String EXPOSURE_READY_CONDITION = "ExposureReady";
    public static final String BARN_AVAILABLE_CONDITION = "BarnAvailable";
    static final String ADMIN_UI_ENDPOINT = "adminUi";
    private static final Pattern BASE64_TEXT = Pattern.compile("^[A-Za-z0-9+/_-]+$");

    private static final Duration NOT_READY_RECHECK = Duration.ofSeconds(30);
    private static final List<String> EXPOSURE_TYPES = List.of(
            RouterResourceFactory.EXPOSURE_AUTO,
            RouterResourceFactory.EXPOSURE_ROUTE,
            RouterResourceFactory.EXPOSURE_INGRESS,
            RouterResourceFactory.EXPOSURE_NONE);

    @Inject
    KubernetesClient kubernetesClient;

    @Override
    public List<EventSource<?, WanakuRouter>> prepareEventSources(EventSourceContext<WanakuRouter> context) {
        // Re-reconcile when an owned Deployment changes, so readiness follows the workloads
        var deployments = InformerEventSourceConfiguration.from(Deployment.class, WanakuRouter.class)
                .withLabelSelector(OperatorUtil.MANAGED_BY_LABEL + "=" + OperatorUtil.MANAGED_BY_VALUE)
                .build();
        // Re-reconcile when the auth Secret changes, to validate it and roll the proxy
        var secrets = InformerEventSourceConfiguration.from(Secret.class, WanakuRouter.class)
                .withSecondaryToPrimaryMapper(secret -> context.getPrimaryCache()
                        .list(router -> Objects.equals(
                                        router.getMetadata().getNamespace(),
                                        secret.getMetadata().getNamespace())
                                && router.getSpec().getAuth() != null
                                && Objects.equals(
                                        router.getSpec().getAuth().getSecretName(),
                                        secret.getMetadata().getName()))
                        .map(ResourceID::fromResource)
                        .collect(Collectors.toSet()))
                .build();
        return List.of(new InformerEventSource<>(deployments, context), new InformerEventSource<>(secrets, context));
    }

    @Override
    public UpdateControl<WanakuRouter> reconcile(WanakuRouter resource, Context<WanakuRouter> context) {
        LOG.infof(
                "Starting router reconciliation for %s", resource.getMetadata().getName());

        ValidateSpecResult validation = validateSpec(resource);
        if (!validation.valid) {
            return setErrorStatus(resource, "ValidationError", validation.errorMessage);
        }

        final String namespace = resource.getMetadata().getNamespace();
        final WanakuRouterStatus wanakuStatus = new WanakuRouterStatus();
        final AuthCheck authCheck = checkAuth(resource, namespace);
        final Exposure exposure = deployRouter(resource, context, namespace, wanakuStatus, authCheck);

        final List<Condition> previous = previousConditions(resource);
        final Long generation = resource.getMetadata().getGeneration();
        final List<Condition> conditions = new ArrayList<>();
        conditions.add(OperatorUtil.condition(
                RECONCILED_CONDITION,
                true,
                "ReconcileSucceeded",
                "All resources are reconciled",
                generation,
                OperatorUtil.findCondition(previous, RECONCILED_CONDITION)));
        final Condition previousAuth = OperatorUtil.findCondition(previous, AUTH_CONFIGURED_CONDITION);
        conditions.add(OperatorUtil.condition(
                AUTH_CONFIGURED_CONDITION,
                authCheck.configured,
                authCheck.reason,
                authCheck.message,
                generation,
                previousAuth));
        // Unprotected or deprecated configurations are valid, but deserve one visible warning
        if (authCheck.configured
                && !"Configured".equals(authCheck.reason)
                && (previousAuth == null || !authCheck.reason.equals(previousAuth.getReason()))) {
            Events.warn(kubernetesClient, resource, authCheck.reason, authCheck.message);
        }
        for (ComponentWorkload component : requiredComponents(resource)) {
            conditions.add(deploymentCondition(component, namespace, generation, previous));
        }
        conditions.add(exposureCondition(exposure, generation, previous));
        conditions.add(OperatorUtil.aggregateReady(conditions, generation, previous));

        wanakuStatus.setObservedGeneration(generation);
        wanakuStatus.setConditions(conditions);
        resource.setStatus(wanakuStatus);
        warnOnReadyChange(resource, previous, conditions);

        UpdateControl<WanakuRouter> control = UpdateControl.patchStatus(resource);
        if (!OperatorUtil.isTrue(OperatorUtil.findCondition(conditions, OperatorUtil.READY_CONDITION))) {
            // Pod-level failures (for example image pull errors) do not always change the Deployment
            control.rescheduleAfter(NOT_READY_RECHECK);
        }
        return control;
    }

    @Override
    public ErrorStatusUpdateControl<WanakuRouter> updateErrorStatus(
            WanakuRouter resource, Context<WanakuRouter> context, Exception e) {
        final String message =
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        LOG.warnf(
                e,
                "WanakuRouter '%s' reconciliation failed",
                resource.getMetadata().getName());
        setFailedStatus(resource, "ReconcileError", message);
        return ErrorStatusUpdateControl.patchStatus(resource);
    }

    /** The result of the authentication configuration check. */
    static final class AuthCheck {
        final boolean configured;
        final String reason;
        final String message;
        final String secretVersion;

        AuthCheck(boolean configured, String reason, String message, String secretVersion) {
            this.configured = configured;
            this.reason = reason;
            this.message = message;
            this.secretVersion = secretVersion;
        }
    }

    AuthCheck checkAuth(WanakuRouter resource, String namespace) {
        final WanakuRouterSpec.AuthSpec auth = resource.getSpec().getAuth();
        if (auth != null && (auth.getAuthServer() != null || auth.getAuthProxy() != null)) {
            return new AuthCheck(
                    false,
                    "DeprecatedFields",
                    "spec.auth.authServer and spec.auth.authProxy are no longer supported. "
                            + "Remove them and set spec.auth.enabled, issuerUrl, clientId and secretName",
                    null);
        }
        if (!RouterResourceFactory.isAuthEnabled(resource)) {
            return new AuthCheck(
                    true,
                    "AuthDisabled",
                    "Authentication is disabled: the exposed management and MCP endpoints are not protected",
                    null);
        }
        final Secret secret = kubernetesClient
                .secrets()
                .inNamespace(namespace)
                .withName(auth.getSecretName())
                .get();
        if (secret == null) {
            return new AuthCheck(
                    false, "SecretNotFound", "Secret %s does not exist".formatted(auth.getSecretName()), null);
        }
        for (String key : List.of(
                RouterResourceFactory.OAUTH2_PROXY_CLIENT_SECRET_KEY,
                RouterResourceFactory.OAUTH2_PROXY_COOKIE_SECRET_KEY)) {
            if (secretValue(secret, key) == null) {
                return new AuthCheck(
                        false,
                        "SecretKeyMissing",
                        "Secret %s has no %s key".formatted(auth.getSecretName(), key),
                        null);
            }
        }
        if (!isValidCookieSecret(secretValue(secret, RouterResourceFactory.OAUTH2_PROXY_COOKIE_SECRET_KEY))) {
            return new AuthCheck(
                    false,
                    "InvalidCookieSecret",
                    "The cookie-secret key of Secret %s must be 16, 24 or 32 bytes, or the base64 encoding of such a value"
                            .formatted(auth.getSecretName()),
                    null);
        }
        final String version = secret.getMetadata().getResourceVersion();
        if (RouterResourceFactory.isLegacyIssuerUrl(auth)) {
            return new AuthCheck(
                    true,
                    "DeprecatedIssuerFormat",
                    "spec.auth.issuerUrl has no realm path, so the operator uses %s. Set the full issuer URL"
                            .formatted(RouterResourceFactory.issuerUrl(auth)),
                    version);
        }
        return new AuthCheck(
                true, "Configured", "Authentication uses issuer %s".formatted(auth.getIssuerUrl()), version);
    }

    private static byte[] secretValue(Secret secret, String key) {
        if (secret.getData() != null && secret.getData().get(key) != null) {
            return Base64.getDecoder().decode(secret.getData().get(key));
        }
        if (secret.getStringData() != null && secret.getStringData().get(key) != null) {
            return secret.getStringData().get(key).getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /** oauth2-proxy accepts a raw 16, 24 or 32 byte cookie secret, or the base64 encoding of one. */
    static boolean isValidCookieSecret(byte[] value) {
        if (isAesKeyLength(value.length)) {
            return true;
        }
        // Standard or URL-safe alphabet, padded or not
        final String text = new String(value, StandardCharsets.UTF_8).trim().replaceAll("=+$", "");
        if (!BASE64_TEXT.matcher(text).matches() || text.length() % 4 == 1) {
            return false;
        }
        final String standard = text.replace('-', '+').replace('_', '/');
        return isAesKeyLength(
                Base64.getDecoder().decode(standard + "==".substring(0, (4 - standard.length() % 4) % 4)).length);
    }

    private static boolean isAesKeyLength(int length) {
        return length == 16 || length == 24 || length == 32;
    }

    /** A workload whose availability is required for the router to be ready. */
    static final class ComponentWorkload {
        final String conditionType;
        final String deploymentName;

        ComponentWorkload(String conditionType, String deploymentName) {
            this.conditionType = conditionType;
            this.deploymentName = deploymentName;
        }
    }

    List<ComponentWorkload> requiredComponents(WanakuRouter resource) {
        final String name = resource.getMetadata().getName();
        final List<ComponentWorkload> components = new ArrayList<>();
        components.add(new ComponentWorkload("ServerAvailable", name + "-server"));
        if (RouterResourceFactory.isAuthEnabled(resource)) {
            components.add(new ComponentWorkload("AuthProxyAvailable", name + "-oauth2-proxy"));
        }
        if (RouterResourceFactory.isBarnEnabled(resource)) {
            components.add(new ComponentWorkload(BARN_AVAILABLE_CONDITION, name + "-barn"));
        }
        return components;
    }

    Condition deploymentCondition(
            ComponentWorkload component, String namespace, Long generation, List<Condition> previous) {
        final Condition previousCondition = OperatorUtil.findCondition(previous, component.conditionType);
        final Deployment deployment = kubernetesClient
                .apps()
                .deployments()
                .inNamespace(namespace)
                .withName(component.deploymentName)
                .get();
        final Workloads.WorkloadState state = Workloads.state(kubernetesClient, deployment, namespace);
        return OperatorUtil.condition(
                component.conditionType,
                state.available,
                state.reason,
                "Deployment %s: %s".formatted(component.deploymentName, state.message),
                generation,
                previousCondition);
    }

    private static List<Condition> previousConditions(WanakuRouter resource) {
        return resource.getStatus() != null ? resource.getStatus().getConditions() : null;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    /** Emits a Warning event when Ready fails or its failure reason changes. */
    private void warnOnReadyChange(WanakuRouter resource, List<Condition> previous, List<Condition> current) {
        final Condition before = OperatorUtil.findCondition(previous, OperatorUtil.READY_CONDITION);
        final Condition after = OperatorUtil.findCondition(current, OperatorUtil.READY_CONDITION);
        // A rollout in progress is expected and not worth a Warning
        if (OperatorUtil.isTrue(after) || "Progressing".equals(after.getReason())) {
            return;
        }
        if (before != null
                && Objects.equals(before.getStatus(), after.getStatus())
                && Objects.equals(before.getReason(), after.getReason())) {
            return;
        }
        Events.warn(kubernetesClient, resource, after.getReason(), after.getMessage());
    }

    private Exposure deployRouter(
            WanakuRouter resource,
            Context<WanakuRouter> context,
            String namespace,
            WanakuRouterStatus wanakuStatus,
            AuthCheck authCheck) {

        deployServerStorageAndService(resource, namespace);

        final boolean authEnabled = RouterResourceFactory.isAuthEnabled(resource);
        final List<ServerListener> extraProxied = RouterResourceFactory.exposedListeners(resource).keySet().stream()
                .filter(l -> l == ServerListener.INFERENCE || l == ServerListener.A2A)
                .toList();
        if (authEnabled) {
            // The service must exist before the Route/Ingress that targets it
            deployOauth2ProxyService(resource, namespace, extraProxied);
        }

        final Exposure exposure = reconcileExternalAccess(resource, namespace);

        // The A2A public URL is only known once its Route or Ingress exists
        final String a2aHost = exposure.hosts.get(ServerListener.A2A);
        deployServerDeployment(resource, context, namespace, a2aHost != null ? "https://%s/".formatted(a2aHost) : null);

        // A proxy without a valid Secret cannot start, so wait until AuthConfigured is True
        if (authEnabled && authCheck.configured) {
            final Map<ServerListener, String> proxyHosts = new EnumMap<>(ServerListener.class);
            proxyHosts.put(ServerListener.MANAGEMENT, exposure.host);
            proxyHosts.put(ServerListener.MCP, exposure.host);
            proxyHosts.putAll(exposure.hosts);
            deployOauth2ProxyDeployment(resource, namespace, proxyHosts, authCheck.secretVersion);
        }

        final Map<String, String> endpoints = new LinkedHashMap<>();
        exposure.hosts.forEach((listener, host) -> endpoints.put(
                listener.id(),
                listener == ServerListener.MCP ? "https://%s/mcp".formatted(host) : "https://%s/".formatted(host)));
        final String managementHost = exposure.hosts.get(ServerListener.MANAGEMENT);
        if (managementHost != null && !RouterResourceFactory.isHeadless(resource)) {
            endpoints.put(ADMIN_UI_ENDPOINT, "https://%s/admin/".formatted(managementHost));
        }
        wanakuStatus.setEndpoints(endpoints.isEmpty() ? null : endpoints);
        final String mcpHost = exposure.hosts.getOrDefault(ServerListener.MCP, exposure.host);
        wanakuStatus.setHost("https://" + exposure.hosts.getOrDefault(ServerListener.MANAGEMENT, exposure.host));
        wanakuStatus.setSseEndpoint("https://%s/mcp/sse".formatted(mcpHost));
        wanakuStatus.setStreamableEndpoint("https://%s/mcp/".formatted(mcpHost));

        if (RouterResourceFactory.isBarnEnabled(resource)) {
            deployBarnBackend(resource, context, namespace);
            wanakuStatus.setBarnUrl(
                    RouterResourceFactory.barnUrl(resource.getMetadata().getName()));
        } else {
            removeBarnBackend(resource, namespace);
        }
        return exposure;
    }

    private void deployOauth2ProxyService(
            WanakuRouter resource, String namespace, List<ServerListener> extraListeners) {
        final Service desiredService = RouterResourceFactory.makeOauth2ProxyService(resource, extraListeners);
        Service existingService = kubernetesClient
                .services()
                .inNamespace(namespace)
                .withName(desiredService.getMetadata().getName())
                .get();
        if (!match(desiredService, existingService)) {
            LOG.infof(
                    "Creating or updating oauth2-proxy Service %s in %s",
                    desiredService.getMetadata().getName(), namespace);
            kubernetesClient
                    .services()
                    .inNamespace(namespace)
                    .resource(desiredService)
                    .createOr(Replaceable::update);
        }
    }

    private void deployOauth2ProxyDeployment(
            WanakuRouter resource, String namespace, Map<ServerListener, String> hosts, String secretVersion) {
        final Deployment desiredDeployment =
                RouterResourceFactory.makeOauth2ProxyDeployment(resource, hosts, secretVersion);
        Deployment existingDeployment = kubernetesClient
                .apps()
                .deployments()
                .inNamespace(namespace)
                .withName(desiredDeployment.getMetadata().getName())
                .get();
        if (!match(desiredDeployment, existingDeployment)) {
            LOG.infof(
                    "Creating or updating oauth2-proxy Deployment %s in %s",
                    desiredDeployment.getMetadata().getName(), namespace);
            kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
                    .resource(desiredDeployment)
                    .createOr(Replaceable::update);
        }
    }

    private void deployBarnBackend(WanakuRouter resource, Context<WanakuRouter> context, String namespace) {
        final String claimName = barnClaimName(resource, namespace);
        final WanakuRouterSpec.StorageSpec storage = resource.getSpec().getBarn() != null
                ? resource.getSpec().getBarn().getStorage()
                : null;
        if (storage == null || StringHelper.isBlank(storage.getExistingClaim())) {
            final PersistentVolumeClaim barnPVC = RouterResourceFactory.makeBarnVolumePVC(resource, claimName);
            PersistentVolumeClaim existingBarnPVC = kubernetesClient
                    .persistentVolumeClaims()
                    .inNamespace(namespace)
                    .withName(claimName)
                    .get();
            if (!match(barnPVC, existingBarnPVC)) {
                LOG.infof("Creating or updating PVC %s in %s", claimName, namespace);
                kubernetesClient
                        .persistentVolumeClaims()
                        .inNamespace(namespace)
                        .resource(barnPVC)
                        .createOr(Replaceable::update);
            }
        }

        final ConfigMap serverConfig = RouterResourceFactory.makeServerConfigMap(resource);
        final ConfigMap existingConfig = kubernetesClient
                .configMaps()
                .inNamespace(namespace)
                .withName(serverConfig.getMetadata().getName())
                .get();
        if (existingConfig == null || !OperatorUtil.sameDesiredHash(serverConfig, existingConfig)) {
            LOG.infof(
                    "Creating or updating ConfigMap %s in %s",
                    serverConfig.getMetadata().getName(), namespace);
            kubernetesClient
                    .configMaps()
                    .inNamespace(namespace)
                    .resource(serverConfig)
                    .createOr(Replaceable::update);
        }

        final Service desiredInternalService = RouterResourceFactory.makeBarnInternalService(resource);
        Service existingInternalService = kubernetesClient
                .services()
                .inNamespace(namespace)
                .withName(desiredInternalService.getMetadata().getName())
                .get();
        if (!match(desiredInternalService, existingInternalService)) {
            LOG.infof(
                    "Creating or updating Service %s in %s",
                    desiredInternalService.getMetadata().getName(), namespace);
            kubernetesClient
                    .services()
                    .inNamespace(namespace)
                    .resource(desiredInternalService)
                    .createOr(Replaceable::update);
        }

        final Deployment desiredDeployment =
                RouterResourceFactory.makeDesiredBarnDeployment(resource, context, claimName);
        Deployment existingDeployment = kubernetesClient
                .apps()
                .deployments()
                .inNamespace(namespace)
                .withName(desiredDeployment.getMetadata().getName())
                .get();
        if (!match(desiredDeployment, existingDeployment)) {
            LOG.infof(
                    "Creating or updating Deployment %s in %s",
                    desiredDeployment.getMetadata().getName(), namespace);
            kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
                    .resource(desiredDeployment)
                    .createOr(Replaceable::update);
        }
    }

    /**
     * Chooses the Barn PVC: {@code spec.barn.storage.existingClaim}, the legacy {@code barn-volume-claim}
     * when this router already owns it (so existing data is kept), or {@code <router>-barn-data}.
     */
    private String barnClaimName(WanakuRouter resource, String namespace) {
        final WanakuRouterSpec.StorageSpec storage = resource.getSpec().getBarn() != null
                ? resource.getSpec().getBarn().getStorage()
                : null;
        if (storage != null && StringHelper.isNotEmpty(storage.getExistingClaim())) {
            return storage.getExistingClaim();
        }
        final PersistentVolumeClaim legacy = kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(RouterResourceFactory.BARN_VOLUME_CLAIM)
                .get();
        if (legacy != null && isOwnedBy(legacy, resource)) {
            return RouterResourceFactory.BARN_VOLUME_CLAIM;
        }
        return RouterResourceFactory.barnClaimName(resource.getMetadata().getName());
    }

    /** Removes the Barn workload and the Server plugin configuration. The Barn PVC is kept for its data. */
    private void removeBarnBackend(WanakuRouter resource, String namespace) {
        final String name = resource.getMetadata().getName();
        final List<HasMetadata> barnObjects = new ArrayList<>();
        Optional.ofNullable(kubernetesClient
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(name + "-barn")
                        .get())
                .ifPresent(barnObjects::add);
        Optional.ofNullable(kubernetesClient
                        .services()
                        .inNamespace(namespace)
                        .withName("barn-" + name)
                        .get())
                .ifPresent(barnObjects::add);
        Optional.ofNullable(kubernetesClient
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(RouterResourceFactory.serverConfigMapName(name))
                        .get())
                .ifPresent(barnObjects::add);
        for (HasMetadata object : barnObjects) {
            if (isOwnedBy(object, resource)) {
                LOG.infof(
                        "Deleting %s %s in %s: Barn is disabled",
                        object.getKind(), object.getMetadata().getName(), namespace);
                kubernetesClient.resource(object).delete();
            }
        }
    }

    private static boolean isOwnedBy(HasMetadata object, WanakuRouter resource) {
        return nullSafe(object.getMetadata().getOwnerReferences()).stream()
                .anyMatch(ref ->
                        Objects.equals(ref.getUid(), resource.getMetadata().getUid()));
    }

    private void deployServerStorageAndService(WanakuRouter resource, String namespace) {
        final PersistentVolumeClaim serverPVC = RouterResourceFactory.makeServerVolumePVC(resource);
        PersistentVolumeClaim existingServerPVC = kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(RouterResourceFactory.SERVER_VOLUME_CLAIM)
                .get();
        if (!match(serverPVC, existingServerPVC)) {
            LOG.infof("Creating or updating PVC server-volume-claim in %s", namespace);
            kubernetesClient
                    .persistentVolumeClaims()
                    .inNamespace(namespace)
                    .resource(serverPVC)
                    .createOr(Replaceable::update);
        }

        final Service desiredService = RouterResourceFactory.makeServerInternalService(resource);
        Service existingService = kubernetesClient
                .services()
                .inNamespace(namespace)
                .withName(desiredService.getMetadata().getName())
                .get();
        if (!match(desiredService, existingService)) {
            LOG.infof(
                    "Creating or updating Wanaku Server Service %s in %s",
                    desiredService.getMetadata().getName(), namespace);
            kubernetesClient
                    .services()
                    .inNamespace(namespace)
                    .resource(desiredService)
                    .createOr(Replaceable::update);
        }
    }

    private void deployServerDeployment(
            WanakuRouter resource, Context<WanakuRouter> context, String namespace, String a2aPublicUrl) {
        final Deployment desiredDeployment =
                RouterResourceFactory.makeDesiredServerDeployment(resource, context, a2aPublicUrl);
        Deployment existingDeployment = kubernetesClient
                .apps()
                .deployments()
                .inNamespace(namespace)
                .withName(desiredDeployment.getMetadata().getName())
                .get();
        if (!match(desiredDeployment, existingDeployment)) {
            LOG.infof(
                    "Creating or updating Wanaku Server Deployment %s in %s",
                    desiredDeployment.getMetadata().getName(), namespace);
            kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
                    .resource(desiredDeployment)
                    .createOr(Replaceable::update);
        }
    }

    /** The public hosts the operator exposed, and the Routes whose admission decides ExposureReady. */
    static final class Exposure {
        /** Public host of each exposed listener. */
        final Map<ServerListener, String> hosts = new EnumMap<>(ServerListener.class);

        final List<Route> routes = new ArrayList<>();
        /** The management host, or the internal Service name when nothing is exposed. */
        String host;
    }

    Exposure reconcileExternalAccess(WanakuRouter resource, String namespace) {
        final String name = resource.getMetadata().getName();
        final String type = RouterResourceFactory.exposureType(resource);
        final boolean routeApi = kubernetesClient.supports(Route.class);
        final boolean useRoutes = RouterResourceFactory.EXPOSURE_ROUTE.equals(type)
                || (RouterResourceFactory.EXPOSURE_AUTO.equals(type) && routeApi);
        final Exposure exposure = new Exposure();
        final Set<String> desired = new HashSet<>();

        if (RouterResourceFactory.EXPOSURE_NONE.equals(type)) {
            exposure.host = internalHost(resource);
        } else if (RouterResourceFactory.isPerListenerExposure(resource)) {
            for (Map.Entry<ServerListener, String> entry :
                    RouterResourceFactory.exposedListeners(resource).entrySet()) {
                final ServerListener listener = entry.getKey();
                desired.add(name + "-" + listener.id());
                if (useRoutes) {
                    final Route route = reconcileRoute(
                            kubernetesClient.adapt(OpenShiftClient.class),
                            RouterResourceFactory.makeListenerRoute(resource, listener, entry.getValue()));
                    exposure.routes.add(route);
                    exposure.hosts.put(listener, route.getSpec().getHost());
                } else {
                    reconcileIngress(RouterResourceFactory.makeListenerIngress(resource, listener, entry.getValue()));
                    exposure.hosts.put(listener, entry.getValue());
                }
            }
            exposure.host = exposure.hosts.getOrDefault(ServerListener.MANAGEMENT, internalHost(resource));
        } else if (useRoutes) {
            desired.add(name);
            desired.add(name + "-mcp");
            createRoutesAndGetHost(resource, exposure);
        } else if (resource.getSpec().getExposure() == null) {
            exposure.host = internalHost(resource);
        } else {
            desired.add(name);
            exposure.host = createIngressAndGetHost(resource);
            exposure.hosts.put(ServerListener.MANAGEMENT, exposure.host);
            exposure.hosts.put(ServerListener.MCP, exposure.host);
        }

        pruneExposure(resource, namespace, desired, routeApi);
        return exposure;
    }

    private static String internalHost(WanakuRouter resource) {
        final String name = resource.getMetadata().getName();
        return RouterResourceFactory.isAuthEnabled(resource)
                ? RouterResourceFactory.oauth2ProxyServiceName(name)
                : "server-" + name;
    }

    /** Deletes the Routes and Ingresses of this router that are no longer selected by the spec. */
    private void pruneExposure(WanakuRouter resource, String namespace, Set<String> desired, boolean routeApi) {
        final Map<String, String> selector =
                Map.of("app", resource.getMetadata().getName() + "-server", "component", "wanaku-server");
        final List<HasMetadata> existing = new ArrayList<>();
        if (routeApi) {
            existing.addAll(kubernetesClient
                    .adapt(OpenShiftClient.class)
                    .routes()
                    .inNamespace(namespace)
                    .withLabels(selector)
                    .list()
                    .getItems());
        }
        existing.addAll(kubernetesClient
                .network()
                .v1()
                .ingresses()
                .inNamespace(namespace)
                .withLabels(selector)
                .list()
                .getItems());
        for (HasMetadata object : existing) {
            final boolean owned = nullSafe(object.getMetadata().getOwnerReferences()).stream()
                    .anyMatch(ref ->
                            Objects.equals(ref.getUid(), resource.getMetadata().getUid()));
            final boolean keep = desired.contains(object.getMetadata().getName())
                    && object.getKind().equals(desiredKind(resource, routeApi));
            if (owned && !keep) {
                LOG.infof(
                        "Deleting %s %s in %s: the listener is no longer exposed",
                        object.getKind(), object.getMetadata().getName(), namespace);
                kubernetesClient.resource(object).delete();
            }
        }
    }

    private static String desiredKind(WanakuRouter resource, boolean routeApi) {
        final String type = RouterResourceFactory.exposureType(resource);
        final boolean useRoutes = RouterResourceFactory.EXPOSURE_ROUTE.equals(type)
                || (RouterResourceFactory.EXPOSURE_AUTO.equals(type) && routeApi);
        return useRoutes ? "Route" : "Ingress";
    }

    /**
     * Creates the management and MCP Routes. Without {@code spec.exposure.host}, OpenShift generates a
     * separate hostname for each Route: setting {@code spec.host} needs the {@code routes/custom-host}
     * permission, so the operator sets it only when the user asks for a shared hostname.
     */
    private void createRoutesAndGetHost(WanakuRouter resource, Exposure exposure) {
        OpenShiftClient client = kubernetesClient.adapt(OpenShiftClient.class);
        var exposureSpec = resource.getSpec().getExposure();
        String host = exposureSpec != null ? exposureSpec.getHost() : null;
        Route management = reconcileRoute(client, RouterResourceFactory.makeServerRoute(resource, host, false));
        Route mcp = reconcileRoute(client, RouterResourceFactory.makeServerRoute(resource, host, true));
        exposure.routes.add(management);
        exposure.routes.add(mcp);
        exposure.host = assignedHost(management, "management");
        exposure.hosts.put(ServerListener.MANAGEMENT, exposure.host);
        exposure.hosts.put(ServerListener.MCP, assignedHost(mcp, "MCP"));
    }

    private static String assignedHost(Route route, String kind) {
        String host = route.getSpec().getHost();
        if (StringHelper.isBlank(host)) {
            throw new IllegalStateException(
                    "OpenShift did not assign a hostname to the Wanaku %s Route".formatted(kind));
        }
        return host;
    }

    Condition exposureCondition(Exposure exposure, Long generation, List<Condition> previous) {
        final Condition previousCondition = OperatorUtil.findCondition(previous, EXPOSURE_READY_CONDITION);
        for (Route route : exposure.routes) {
            final List<RouteIngressCondition> admission = route.getStatus() == null
                    ? List.of()
                    : nullSafe(route.getStatus().getIngress()).stream()
                            .flatMap(ingress -> nullSafe(ingress.getConditions()).stream())
                            .filter(c -> "Admitted".equals(c.getType()))
                            .toList();
            final Optional<RouteIngressCondition> rejected = admission.stream()
                    .filter(c -> "False".equals(c.getStatus()))
                    .findFirst();
            if (rejected.isPresent()) {
                return OperatorUtil.condition(
                        EXPOSURE_READY_CONDITION,
                        false,
                        "RouteNotAdmitted",
                        "Route %s: %s: %s"
                                .formatted(
                                        route.getMetadata().getName(),
                                        rejected.get().getReason(),
                                        rejected.get().getMessage()),
                        generation,
                        previousCondition);
            }
            if (admission.stream().noneMatch(c -> "True".equals(c.getStatus()))) {
                return OperatorUtil.condition(
                        EXPOSURE_READY_CONDITION,
                        false,
                        "Progressing",
                        "Route %s is waiting for router admission"
                                .formatted(route.getMetadata().getName()),
                        generation,
                        previousCondition);
            }
        }
        final boolean exposed = !exposure.hosts.isEmpty();
        return OperatorUtil.condition(
                EXPOSURE_READY_CONDITION,
                true,
                exposed ? "Exposed" : "NotExposed",
                exposed
                        ? "Exposed listeners: "
                                + exposure.hosts.keySet().stream()
                                        .map(ServerListener::id)
                                        .toList()
                        : "No listener is exposed outside the cluster",
                generation,
                previousCondition);
    }

    private Route reconcileRoute(OpenShiftClient client, Route desired) {
        var routes = client.routes().inNamespace(desired.getMetadata().getNamespace());
        Route existing = routes.withName(desired.getMetadata().getName()).get();
        if (existing != null && StringHelper.isBlank(desired.getSpec().getHost())) {
            desired.getSpec().setHost(existing.getSpec().getHost());
        }
        if (match(desired, existing)) {
            return existing;
        }
        LOG.infof(
                "Creating or updating Route %s in %s",
                desired.getMetadata().getName(), desired.getMetadata().getNamespace());
        return routes.resource(desired).createOr(Replaceable::update);
    }

    private String createIngressAndGetHost(WanakuRouter resource) {
        String host = resource.getSpec().getExposure().getHost();
        reconcileIngress(RouterResourceFactory.makeServerIngress(resource, host));
        return host;
    }

    private void reconcileIngress(Ingress desiredIngress) {
        final String namespace = desiredIngress.getMetadata().getNamespace();
        Ingress existingIngress = kubernetesClient
                .network()
                .v1()
                .ingresses()
                .inNamespace(namespace)
                .withName(desiredIngress.getMetadata().getName())
                .get();
        if (!match(desiredIngress, existingIngress)) {
            LOG.infof(
                    "Creating or updating Ingress %s in %s",
                    desiredIngress.getMetadata().getName(), namespace);
            kubernetesClient
                    .network()
                    .v1()
                    .ingresses()
                    .inNamespace(namespace)
                    .resource(desiredIngress)
                    .createOr(Replaceable::update);
        }
    }

    private UpdateControl<WanakuRouter> setErrorStatus(WanakuRouter resource, String reason, String message) {
        LOG.warnf("WanakuRouter '%s' error (%s): %s", resource.getMetadata().getName(), reason, message);
        setFailedStatus(resource, reason, message);
        return UpdateControl.patchStatus(resource);
    }

    private void setFailedStatus(WanakuRouter resource, String reason, String message) {
        final List<Condition> previous = previousConditions(resource);
        final Long generation = resource.getMetadata().getGeneration();
        final WanakuRouterStatus status =
                resource.getStatus() != null ? resource.getStatus() : new WanakuRouterStatus();
        final List<Condition> conditions = new ArrayList<>();
        conditions.add(OperatorUtil.condition(
                RECONCILED_CONDITION,
                false,
                reason,
                message,
                generation,
                OperatorUtil.findCondition(previous, RECONCILED_CONDITION)));
        conditions.add(OperatorUtil.aggregateReady(conditions, generation, previous));
        status.setObservedGeneration(generation);
        status.setConditions(conditions);
        resource.setStatus(status);
        warnOnReadyChange(resource, previous, conditions);
    }

    static class ValidateSpecResult {
        final boolean valid;
        final String errorMessage;

        ValidateSpecResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        static final ValidateSpecResult OK = new ValidateSpecResult(true, null);

        static ValidateSpecResult invalid(String message) {
            return new ValidateSpecResult(false, message);
        }
    }

    ValidateSpecResult validateSpec(WanakuRouter resource) {
        WanakuTypes.ExposureSpec exposureSpec =
                resource.getSpec() != null ? resource.getSpec().getExposure() : null;

        if (exposureSpec != null) {
            final String type = RouterResourceFactory.exposureType(resource);
            if (!EXPOSURE_TYPES.contains(type)) {
                return ValidateSpecResult.invalid("spec.exposure.type must be one of %s".formatted(EXPOSURE_TYPES));
            }
            final boolean routeApi = kubernetesClient.supports(Route.class);
            if (RouterResourceFactory.EXPOSURE_ROUTE.equals(type) && !routeApi) {
                return ValidateSpecResult.invalid(
                        "spec.exposure.type is Route, but the cluster does not serve the OpenShift Route API");
            }
            final boolean ingress = RouterResourceFactory.EXPOSURE_INGRESS.equals(type)
                    || (RouterResourceFactory.EXPOSURE_AUTO.equals(type) && !routeApi);
            if (ingress && RouterResourceFactory.isPerListenerExposure(resource)) {
                for (Map.Entry<ServerListener, String> entry :
                        RouterResourceFactory.exposedListeners(resource).entrySet()) {
                    if (StringHelper.isBlank(entry.getValue())) {
                        return ValidateSpecResult.invalid(
                                "spec.exposure.listeners.%s.host is required when the listener is exposed through an Ingress"
                                        .formatted(entry.getKey().id()));
                    }
                }
            } else if (ingress && StringHelper.isBlank(exposureSpec.getHost())) {
                return ValidateSpecResult.invalid("spec.exposure.host is required when spec.exposure.type is Ingress");
            }
        }

        WanakuRouterSpec.ServerSpec serverSpec =
                resource.getSpec() != null ? resource.getSpec().getServer() : null;
        if (serverSpec != null
                && serverSpec.getVariant() != null
                && !RouterResourceFactory.SERVER_VARIANT_STANDARD.equals(serverSpec.getVariant())
                && !RouterResourceFactory.SERVER_VARIANT_HEADLESS.equals(serverSpec.getVariant())) {
            return ValidateSpecResult.invalid("spec.server.variant must be standard or headless");
        }

        WanakuRouterSpec.AuthSpec authSpec =
                resource.getSpec() != null ? resource.getSpec().getAuth() : null;
        if (authSpec != null && authSpec.isEnabled()) {
            if (StringHelper.isBlank(authSpec.getIssuerUrl())) {
                return ValidateSpecResult.invalid("spec.auth.issuerUrl is required when spec.auth.enabled is true");
            }
            if (StringHelper.isBlank(authSpec.getSecretName())) {
                return ValidateSpecResult.invalid("spec.auth.secretName is required when spec.auth.enabled is true");
            }
        }

        return ValidateSpecResult.OK;
    }
}
