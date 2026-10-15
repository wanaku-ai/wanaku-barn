package ai.wanaku.operator.wanaku;

import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Replaceable;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import io.quarkiverse.operatorsdk.annotations.RBACVerbs;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.util.Events;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;
import ai.wanaku.operator.util.SemanticRouterResourceFactory;
import ai.wanaku.operator.util.WanakuForwards;
import ai.wanaku.operator.util.Workloads;

import static ai.wanaku.operator.util.Matchers.match;

@ControllerConfiguration(
        informer = @Informer(namespaces = Constants.WATCH_CURRENT_NAMESPACE),
        name = "wanaku-semantic-router")
@CSVMetadata(
        displayName = "Wanaku Semantic Router operator",
        description = "Deploys Wanaku Semantic Router runtimes and reports their registration")
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
        apiGroups = "",
        resources = {"services"},
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
        apiGroups = "",
        resources = {"configmaps", "secrets", "pods"},
        verbs = {RBACVerbs.GET, RBACVerbs.LIST, RBACVerbs.WATCH})
@RBACRule(
        apiGroups = "",
        resources = {"events"},
        verbs = {RBACVerbs.CREATE, RBACVerbs.PATCH})
@RBACRule(
        apiGroups = "wanaku.ai",
        resources = {"wanakurouters"},
        verbs = {RBACVerbs.GET, RBACVerbs.LIST, RBACVerbs.WATCH})
public class WanakuSemanticRouterReconciler implements Reconciler<WanakuSemanticRouter> {
    private static final Logger LOG = Logger.getLogger(WanakuSemanticRouterReconciler.class);
    static final String RECONCILED_CONDITION = "Reconciled";
    static final String AVAILABLE_CONDITION = "Available";
    static final String REGISTERED_CONDITION = "Registered";
    private static final Duration NOT_READY_RECHECK = Duration.ofSeconds(30);

    @Inject
    KubernetesClient kubernetesClient;

    /** Reads forwards from the router management API; tests replace it. */
    WanakuForwards.Lookup forwards = WanakuForwards::get;

    @Override
    public List<EventSource<?, WanakuSemanticRouter>> prepareEventSources(
            EventSourceContext<WanakuSemanticRouter> context) {
        var deployments = InformerEventSourceConfiguration.from(Deployment.class, WanakuSemanticRouter.class)
                .withLabelSelector(OperatorUtil.MANAGED_BY_LABEL + "=" + OperatorUtil.MANAGED_BY_VALUE)
                .build();
        return List.of(new InformerEventSource<>(deployments, context));
    }

    @Override
    public UpdateControl<WanakuSemanticRouter> reconcile(
            WanakuSemanticRouter resource, Context<WanakuSemanticRouter> context) {
        final String namespace = resource.getMetadata().getNamespace();
        final WanakuSemanticRouterSpec spec = resource.getSpec();
        final WanakuRouter router = StringHelper.isBlank(spec.getRouterRef())
                ? null
                : kubernetesClient
                        .resources(WanakuRouter.class)
                        .inNamespace(namespace)
                        .withName(spec.getRouterRef())
                        .get();

        final String invalid = validate(resource, router, namespace);
        if (invalid != null) {
            setFailedStatus(resource, "ValidationError", invalid);
            return UpdateControl.patchStatus(resource);
        }

        final Service service = SemanticRouterResourceFactory.makeService(resource);
        final Service existingService = kubernetesClient
                .services()
                .inNamespace(namespace)
                .withName(service.getMetadata().getName())
                .get();
        if (!match(service, existingService)) {
            LOG.infof(
                    "Creating or updating Service %s in %s",
                    service.getMetadata().getName(), namespace);
            kubernetesClient.services().inNamespace(namespace).resource(service).createOr(Replaceable::update);
        }
        final Deployment deployment =
                SemanticRouterResourceFactory.makeDeployment(resource, RouterResourceFactory.isBarnEnabled(router));
        final Deployment existingDeployment = kubernetesClient
                .apps()
                .deployments()
                .inNamespace(namespace)
                .withName(deployment.getMetadata().getName())
                .get();
        if (!match(deployment, existingDeployment)) {
            LOG.infof(
                    "Creating or updating Deployment %s in %s",
                    deployment.getMetadata().getName(), namespace);
            kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
                    .resource(deployment)
                    .createOr(Replaceable::update);
        }

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
        final Workloads.WorkloadState workload = Workloads.state(
                kubernetesClient,
                kubernetesClient
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(deployment.getMetadata().getName())
                        .get(),
                namespace);
        conditions.add(OperatorUtil.condition(
                AVAILABLE_CONDITION,
                workload.available,
                workload.reason,
                "Deployment %s: %s".formatted(deployment.getMetadata().getName(), workload.message),
                generation,
                OperatorUtil.findCondition(previous, AVAILABLE_CONDITION)));
        conditions.add(registeredCondition(resource, workload.available, namespace, generation, previous));
        conditions.add(OperatorUtil.aggregateReady(conditions, generation, previous));

        final WanakuRuntimeStatus status = new WanakuRuntimeStatus();
        status.setObservedGeneration(generation);
        status.setForwardName(SemanticRouterResourceFactory.forwardName(resource));
        status.setMcpAddress(SemanticRouterResourceFactory.mcpAddress(resource));
        status.setWanakuEndpoint(wanakuEndpoint(router, SemanticRouterResourceFactory.forwardNamespace(resource)));
        status.setConditions(conditions);
        resource.setStatus(status);
        warnOnReadyChange(resource, previous, conditions);

        final UpdateControl<WanakuSemanticRouter> control = UpdateControl.patchStatus(resource);
        if (!OperatorUtil.isTrue(OperatorUtil.findCondition(conditions, OperatorUtil.READY_CONDITION))) {
            // Registration is not visible to any informer, so check it again
            control.rescheduleAfter(NOT_READY_RECHECK);
        }
        return control;
    }

    @Override
    public ErrorStatusUpdateControl<WanakuSemanticRouter> updateErrorStatus(
            WanakuSemanticRouter resource, Context<WanakuSemanticRouter> context, Exception e) {
        LOG.warnf(
                e,
                "WanakuSemanticRouter '%s' reconciliation failed",
                resource.getMetadata().getName());
        setFailedStatus(
                resource,
                "ReconcileError",
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return ErrorStatusUpdateControl.patchStatus(resource);
    }

    String validate(WanakuSemanticRouter resource, WanakuRouter router, String namespace) {
        final WanakuSemanticRouterSpec spec = resource.getSpec();
        if (StringHelper.isBlank(spec.getRouterRef())) {
            return "spec.routerRef is required";
        }
        if (router == null) {
            return "WanakuRouter %s does not exist in namespace %s".formatted(spec.getRouterRef(), namespace);
        }
        final WanakuSemanticRouterSpec.Source source = spec.getSource();
        final int sources = source == null
                ? 0
                : (source.getSemanticRoute() != null ? 1 : 0)
                        + (source.getCatalog() != null ? 1 : 0)
                        + (source.getConfigMapRef() != null ? 1 : 0);
        if (sources != 1) {
            return "Set exactly one of spec.source.semanticRoute, spec.source.catalog and spec.source.configMapRef";
        }
        if ((source.getSemanticRoute() != null || source.getCatalog() != null)
                && !RouterResourceFactory.isBarnEnabled(router)) {
            return "spec.source.semanticRoute and spec.source.catalog read from Wanaku Barn, "
                    + "but WanakuRouter %s does not enable it (spec.barn.enabled)".formatted(spec.getRouterRef());
        }
        if (source.getSemanticRoute() != null
                && StringHelper.isBlank(source.getSemanticRoute().getName())) {
            return "spec.source.semanticRoute.name is required";
        }
        final WanakuSemanticRouterSpec.CatalogSource catalog = source.getCatalog();
        if (catalog != null
                && (StringHelper.isBlank(catalog.getName())
                        || StringHelper.isBlank(catalog.getService())
                        || StringHelper.isBlank(catalog.getRevision())
                        || StringHelper.isBlank(catalog.getSha256()))) {
            return "spec.source.catalog requires name, service, revision and sha256";
        }
        final WanakuTypes.ConfigMapSource configMap = source.getConfigMapRef();
        if (configMap != null) {
            if (StringHelper.isBlank(configMap.getName()) || StringHelper.isBlank(configMap.getMain())) {
                return "spec.source.configMapRef requires name and main";
            }
            if (kubernetesClient
                            .configMaps()
                            .inNamespace(namespace)
                            .withName(configMap.getName())
                            .get()
                    == null) {
                return "ConfigMap %s does not exist".formatted(configMap.getName());
            }
        }
        for (WanakuTypes.SecretKeyRef ref :
                List.of(nonNull(spec.getWanakuTokenSecretRef()), nonNull(spec.getBarnTokenSecretRef()))) {
            if (StringHelper.isNotEmpty(ref.getName())
                    && kubernetesClient
                                    .secrets()
                                    .inNamespace(namespace)
                                    .withName(ref.getName())
                                    .get()
                            == null) {
                return "Secret %s does not exist".formatted(ref.getName());
            }
        }
        return null;
    }

    private Condition registeredCondition(
            WanakuSemanticRouter resource,
            boolean available,
            String namespace,
            Long generation,
            List<Condition> previous) {
        final Condition previousCondition = OperatorUtil.findCondition(previous, REGISTERED_CONDITION);
        final String forwardName = SemanticRouterResourceFactory.forwardName(resource);
        if (!available) {
            return OperatorUtil.condition(
                    REGISTERED_CONDITION,
                    false,
                    "WaitingForRuntime",
                    "The runtime registers forward %s once it is ready".formatted(forwardName),
                    generation,
                    previousCondition);
        }
        final String managementUrl =
                "http://server-%s:8080".formatted(resource.getSpec().getRouterRef());
        try {
            final WanakuForwards.Forward forward = forwards.get(
                    managementUrl, forwardName, token(resource.getSpec().getWanakuTokenSecretRef(), namespace));
            if (forward == null) {
                return OperatorUtil.condition(
                        REGISTERED_CONDITION,
                        false,
                        "NotRegistered",
                        "The router has no forward %s".formatted(forwardName),
                        generation,
                        previousCondition);
            }
            if (!forward.labels.containsKey("wsr.instance")) {
                return OperatorUtil.condition(
                        REGISTERED_CONDITION,
                        false,
                        "ForeignForward",
                        "Forward %s was not registered by a semantic router runtime".formatted(forwardName),
                        generation,
                        previousCondition);
            }
            if (!forward.available) {
                return OperatorUtil.condition(
                        REGISTERED_CONDITION,
                        false,
                        "ForwardUnavailable",
                        "Forward %s is unavailable: %s".formatted(forwardName, forward.statusMessage),
                        generation,
                        previousCondition);
            }
            return OperatorUtil.condition(
                    REGISTERED_CONDITION,
                    true,
                    "Registered",
                    "Forward %s is registered and available".formatted(forwardName),
                    generation,
                    previousCondition);
        } catch (IOException e) {
            return OperatorUtil.condition(
                    REGISTERED_CONDITION,
                    false,
                    "RouterUnreachable",
                    "Cannot read forward %s from %s: %s".formatted(forwardName, managementUrl, e.getMessage()),
                    generation,
                    previousCondition);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading forward " + forwardName, e);
        }
    }

    private String token(WanakuTypes.SecretKeyRef ref, String namespace) {
        if (ref == null || StringHelper.isBlank(ref.getName())) {
            return null;
        }
        final Secret secret = kubernetesClient
                .secrets()
                .inNamespace(namespace)
                .withName(ref.getName())
                .get();
        final String key = StringHelper.isNotEmpty(ref.getKey()) ? ref.getKey() : "token";
        if (secret == null) {
            return null;
        }
        if (secret.getData() != null && secret.getData().get(key) != null) {
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8).trim();
        }
        return secret.getStringData() != null ? secret.getStringData().get(key) : null;
    }

    /** The router MCP endpoint for the forward namespace, when the router exposes MCP. */
    static String wanakuEndpoint(WanakuRouter router, String forwardNamespace) {
        final Map<String, String> endpoints =
                router.getStatus() != null ? router.getStatus().getEndpoints() : null;
        final String mcp = endpoints != null ? endpoints.get("mcp") : null;
        if (mcp == null) {
            return null;
        }
        return "default".equals(forwardNamespace) ? mcp : mcp.replaceFirst("/mcp$", "/" + forwardNamespace + "/mcp");
    }

    private void setFailedStatus(WanakuSemanticRouter resource, String reason, String message) {
        final List<Condition> previous = previousConditions(resource);
        final Long generation = resource.getMetadata().getGeneration();
        final List<Condition> conditions = new ArrayList<>();
        conditions.add(OperatorUtil.condition(
                RECONCILED_CONDITION,
                false,
                reason,
                message,
                generation,
                OperatorUtil.findCondition(previous, RECONCILED_CONDITION)));
        conditions.add(OperatorUtil.aggregateReady(conditions, generation, previous));
        final WanakuRuntimeStatus status =
                resource.getStatus() != null ? resource.getStatus() : new WanakuRuntimeStatus();
        status.setObservedGeneration(generation);
        status.setConditions(conditions);
        resource.setStatus(status);
        warnOnReadyChange(resource, previous, conditions);
    }

    private void warnOnReadyChange(WanakuSemanticRouter resource, List<Condition> previous, List<Condition> current) {
        final Condition before = OperatorUtil.findCondition(previous, OperatorUtil.READY_CONDITION);
        final Condition after = OperatorUtil.findCondition(current, OperatorUtil.READY_CONDITION);
        if (OperatorUtil.isTrue(after)
                || "Progressing".equals(after.getReason())
                || "WaitingForRuntime".equals(after.getReason())) {
            return;
        }
        if (before != null
                && before.getStatus().equals(after.getStatus())
                && before.getReason().equals(after.getReason())) {
            return;
        }
        Events.warn(kubernetesClient, resource, after.getReason(), after.getMessage());
    }

    private static List<Condition> previousConditions(WanakuSemanticRouter resource) {
        return resource.getStatus() != null ? resource.getStatus().getConditions() : null;
    }

    private static WanakuTypes.SecretKeyRef nonNull(WanakuTypes.SecretKeyRef ref) {
        return ref != null ? ref : new WanakuTypes.SecretKeyRef();
    }
}
