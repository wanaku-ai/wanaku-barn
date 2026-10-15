package ai.wanaku.operator.wanaku;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Replaceable;
import io.fabric8.openshift.api.model.Route;
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
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;

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
    private static final Pattern BASE64_TEXT = Pattern.compile("^[A-Za-z0-9+/_-]+$");

    private static final Duration NOT_READY_RECHECK = Duration.ofSeconds(30);
    private static final Set<String> FAILURE_REASONS = Set.of(
            "ImagePullBackOff",
            "ErrImagePull",
            "InvalidImageName",
            "CrashLoopBackOff",
            "CreateContainerConfigError",
            "CreateContainerError",
            "RunContainerError");

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
        deployRouter(resource, context, namespace, wanakuStatus, authCheck);

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
            recordWarning(resource, authCheck.reason, authCheck.message);
        }
        for (ComponentWorkload component : requiredComponents(resource)) {
            conditions.add(deploymentCondition(component, namespace, generation, previous));
        }
        conditions.add(aggregateReady(conditions, generation, previous));

        wanakuStatus.setObservedGeneration(generation);
        wanakuStatus.setConditions(conditions);
        resource.setStatus(wanakuStatus);
        warnOnReadyChange(resource, previous, conditions);

        UpdateControl<WanakuRouter> control = UpdateControl.patchStatus(resource);
        if (!isTrue(OperatorUtil.findCondition(conditions, OperatorUtil.READY_CONDITION))) {
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
        WanakuRouterSpec.RouterSpec routerSpec = resource.getSpec().getRouter();
        if (routerSpec != null && routerSpec.isEnabled()) {
            components.add(new ComponentWorkload("BarnAvailable", name + "-barn"));
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
        final WorkloadState state = workloadState(deployment, namespace);
        return OperatorUtil.condition(
                component.conditionType,
                state.available,
                state.reason,
                "Deployment %s: %s".formatted(component.deploymentName, state.message),
                generation,
                previousCondition);
    }

    static final class WorkloadState {
        final boolean available;
        final String reason;
        final String message;

        WorkloadState(boolean available, String reason, String message) {
            this.available = available;
            this.reason = reason;
            this.message = message;
        }
    }

    WorkloadState workloadState(Deployment deployment, String namespace) {
        if (deployment == null) {
            return new WorkloadState(false, "NotFound", "the Deployment does not exist");
        }
        final DeploymentStatus status =
                deployment.getStatus() != null ? deployment.getStatus() : new DeploymentStatus();
        final int desired =
                Optional.ofNullable(deployment.getSpec().getReplicas()).orElse(1);
        final int available = Optional.ofNullable(status.getAvailableReplicas()).orElse(0);
        final long generation =
                Optional.ofNullable(deployment.getMetadata().getGeneration()).orElse(0L);
        final boolean observed = status.getObservedGeneration() != null && status.getObservedGeneration() >= generation;
        final int updated = Optional.ofNullable(status.getUpdatedReplicas()).orElse(0);
        if (observed
                && updated >= desired
                && available >= desired
                && hasDeploymentCondition(status, "Available", "True")) {
            return new WorkloadState(true, "Available", "%d/%d replicas available".formatted(available, desired));
        }
        final WorkloadState podFailure = podFailure(deployment, namespace);
        if (podFailure != null) {
            return podFailure;
        }
        for (DeploymentCondition condition : nullSafe(status.getConditions())) {
            if ("Progressing".equals(condition.getType()) && "ProgressDeadlineExceeded".equals(condition.getReason())) {
                return new WorkloadState(false, "ProgressDeadlineExceeded", condition.getMessage());
            }
        }
        return new WorkloadState(false, "Progressing", "%d/%d replicas available".formatted(available, desired));
    }

    private WorkloadState podFailure(Deployment deployment, String namespace) {
        final List<Pod> pods = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withLabels(deployment.getSpec().getSelector().getMatchLabels())
                .list()
                .getItems();
        for (Pod pod : nullSafe(pods)) {
            if (pod.getStatus() == null) {
                continue;
            }
            for (PodCondition condition : nullSafe(pod.getStatus().getConditions())) {
                if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())) {
                    return new WorkloadState(
                            false,
                            Optional.ofNullable(condition.getReason()).orElse("Unschedulable"),
                            condition.getMessage());
                }
            }
            final List<ContainerStatus> statuses =
                    new ArrayList<>(nullSafe(pod.getStatus().getInitContainerStatuses()));
            statuses.addAll(nullSafe(pod.getStatus().getContainerStatuses()));
            for (ContainerStatus containerStatus : statuses) {
                ContainerStateWaiting waiting = containerStatus.getState() != null
                        ? containerStatus.getState().getWaiting()
                        : null;
                if (waiting != null && FAILURE_REASONS.contains(waiting.getReason())) {
                    return new WorkloadState(false, waiting.getReason(), containerMessage(containerStatus, waiting));
                }
            }
        }
        return null;
    }

    private static String containerMessage(ContainerStatus containerStatus, ContainerStateWaiting waiting) {
        String message = "container %s: %s"
                .formatted(
                        containerStatus.getName(),
                        Optional.ofNullable(waiting.getMessage()).orElse(waiting.getReason()));
        if (containerStatus.getLastState() != null
                && containerStatus.getLastState().getTerminated() != null
                && containerStatus.getLastState().getTerminated().getMessage() != null) {
            message += "; last termination: "
                    + containerStatus.getLastState().getTerminated().getMessage();
        }
        return message;
    }

    private static boolean hasDeploymentCondition(DeploymentStatus status, String type, String value) {
        return nullSafe(status.getConditions()).stream()
                .anyMatch(c -> type.equals(c.getType()) && value.equals(c.getStatus()));
    }

    private static Condition aggregateReady(List<Condition> conditions, Long generation, List<Condition> previous) {
        final Condition previousReady = OperatorUtil.findCondition(previous, OperatorUtil.READY_CONDITION);
        for (Condition condition : conditions) {
            if (!isTrue(condition)) {
                return OperatorUtil.condition(
                        OperatorUtil.READY_CONDITION,
                        false,
                        condition.getReason(),
                        condition.getType() + ": " + condition.getMessage(),
                        generation,
                        previousReady);
            }
        }
        return OperatorUtil.readyCondition(generation, previousReady, "All required components are available");
    }

    private static boolean isTrue(Condition condition) {
        return condition != null && OperatorUtil.CONDITION_STATUS_TRUE.equals(condition.getStatus());
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
        if (isTrue(after) || "Progressing".equals(after.getReason())) {
            return;
        }
        if (before != null
                && Objects.equals(before.getStatus(), after.getStatus())
                && Objects.equals(before.getReason(), after.getReason())) {
            return;
        }
        recordWarning(resource, after.getReason(), after.getMessage());
    }

    private void recordWarning(WanakuRouter resource, String reason, String message) {
        final String now = OperatorUtil.now();
        final ObjectMeta meta = resource.getMetadata();
        final Event event = new EventBuilder()
                .withNewMetadata()
                .withGenerateName(meta.getName() + "-")
                .withNamespace(meta.getNamespace())
                .endMetadata()
                .withNewInvolvedObject()
                .withApiVersion(resource.getApiVersion())
                .withKind(resource.getKind())
                .withName(meta.getName())
                .withNamespace(meta.getNamespace())
                .withUid(meta.getUid())
                .endInvolvedObject()
                .withType("Warning")
                .withReason(reason)
                .withMessage(message)
                .withFirstTimestamp(now)
                .withLastTimestamp(now)
                .withCount(1)
                .withNewSource()
                .withComponent(OperatorUtil.MANAGED_BY_VALUE)
                .endSource()
                .build();
        try {
            kubernetesClient
                    .v1()
                    .events()
                    .inNamespace(meta.getNamespace())
                    .resource(event)
                    .create();
        } catch (KubernetesClientException e) {
            // Events are best effort: the condition already carries the failure
            LOG.warnf("Unable to record event for WanakuRouter '%s': %s", meta.getName(), e.getMessage());
        }
    }

    private void deployRouter(
            WanakuRouter resource,
            Context<WanakuRouter> context,
            String namespace,
            WanakuRouterStatus wanakuStatus,
            AuthCheck authCheck) {

        deployServer(resource, context, namespace);

        final boolean authEnabled = RouterResourceFactory.isAuthEnabled(resource);
        if (authEnabled) {
            // The service must exist before the Route/Ingress that targets it
            deployOauth2ProxyService(resource, namespace);
        }

        ExternalHosts hosts = reconcileExternalAccess(resource, namespace);

        // A proxy without a valid Secret cannot start, so wait until AuthConfigured is True
        if (authEnabled && authCheck.configured) {
            deployOauth2ProxyDeployment(resource, namespace, hosts.management, authCheck.secretVersion);
        }

        wanakuStatus.setHost("https://" + hosts.management);
        wanakuStatus.setSseEndpoint("https://%s/mcp/sse".formatted(hosts.mcp));
        wanakuStatus.setStreamableEndpoint("https://%s/mcp/".formatted(hosts.mcp));

        WanakuRouterSpec.RouterSpec routerSpec = resource.getSpec().getRouter();
        if (routerSpec != null && routerSpec.isEnabled()) {
            deployBarnBackend(resource, context, namespace);
        }
    }

    private void deployOauth2ProxyService(WanakuRouter resource, String namespace) {
        final Service desiredService = RouterResourceFactory.makeOauth2ProxyService(resource);
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
            WanakuRouter resource, String namespace, String host, String secretVersion) {
        final Deployment desiredDeployment =
                RouterResourceFactory.makeOauth2ProxyDeployment(resource, host, secretVersion);
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
        final PersistentVolumeClaim barnPVC = RouterResourceFactory.makeBarnVolumePVC(resource);
        PersistentVolumeClaim existingBarnPVC = kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(RouterResourceFactory.BARN_VOLUME_CLAIM)
                .get();
        if (!match(barnPVC, existingBarnPVC)) {
            LOG.infof("Creating or updating PVC barn-volume-claim in %s", namespace);
            kubernetesClient
                    .persistentVolumeClaims()
                    .inNamespace(namespace)
                    .resource(barnPVC)
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

        final Deployment desiredDeployment = RouterResourceFactory.makeDesiredBarnDeployment(resource, context);
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

    private void deployServer(WanakuRouter resource, Context<WanakuRouter> context, String namespace) {
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

        final Deployment desiredDeployment = RouterResourceFactory.makeDesiredServerDeployment(resource, context);
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

    /** Externally reachable hostnames of the management API and the MCP endpoint. */
    static final class ExternalHosts {
        final String management;
        final String mcp;

        ExternalHosts(String management, String mcp) {
            this.management = management;
            this.mcp = mcp;
        }
    }

    ExternalHosts reconcileExternalAccess(WanakuRouter resource, String namespace) {
        WanakuTypes.ExposureSpec exposureSpec = resource.getSpec().getExposure();
        if (kubernetesClient.supports(Route.class)) {
            return createRoutesAndGetHosts(resource);
        }
        String host;
        if (exposureSpec != null) {
            host = createIngressAndGetHost(resource, namespace);
        } else if (RouterResourceFactory.isAuthEnabled(resource)) {
            host = RouterResourceFactory.oauth2ProxyServiceName(
                    resource.getMetadata().getName());
        } else {
            host = "server-" + resource.getMetadata().getName();
        }
        return new ExternalHosts(host, host);
    }

    /**
     * Creates the management and MCP Routes. Without {@code spec.exposure.host}, OpenShift generates a
     * separate hostname for each Route: setting {@code spec.host} needs the {@code routes/custom-host}
     * permission, so the operator sets it only when the user asks for a shared hostname.
     */
    private ExternalHosts createRoutesAndGetHosts(WanakuRouter resource) {
        OpenShiftClient client = kubernetesClient.adapt(OpenShiftClient.class);
        var exposure = resource.getSpec().getExposure();
        String host = exposure != null ? exposure.getHost() : null;
        String management = assignedHost(
                reconcileRoute(client, RouterResourceFactory.makeServerRoute(resource, host, false)), "management");
        String mcp = assignedHost(
                reconcileRoute(client, RouterResourceFactory.makeServerRoute(resource, host, true)), "MCP");
        return new ExternalHosts(management, mcp);
    }

    private static String assignedHost(Route route, String kind) {
        String host = route.getSpec().getHost();
        if (StringHelper.isBlank(host)) {
            throw new IllegalStateException(
                    "OpenShift did not assign a hostname to the Wanaku %s Route".formatted(kind));
        }
        return host;
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

    private String createIngressAndGetHost(WanakuRouter resource, String namespace) {
        String host = resource.getSpec().getExposure().getHost();
        final Ingress desiredIngress = RouterResourceFactory.makeServerIngress(resource, host);
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
        return host;
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
        conditions.add(aggregateReady(conditions, generation, previous));
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

        if (exposureSpec != null
                && StringHelper.isBlank(exposureSpec.getHost())
                && !kubernetesClient.supports(Route.class)) {
            return ValidateSpecResult.invalid("spec.exposure.host is required when spec.exposure.type is Ingress");
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
