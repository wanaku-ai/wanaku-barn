package ai.wanaku.operator.wanaku;

import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Condition;
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
import ai.wanaku.operator.util.IntegrationResourceFactory;
import ai.wanaku.operator.util.OperatorUtil;
import ai.wanaku.operator.util.RouterResourceFactory;
import ai.wanaku.operator.util.RuntimeRegistration;
import ai.wanaku.operator.util.RuntimeWorkloads;
import ai.wanaku.operator.util.WanakuForwards;
import ai.wanaku.operator.util.Workloads;

import static ai.wanaku.operator.util.Matchers.match;

@ControllerConfiguration(
        informer = @Informer(namespaces = Constants.WATCH_CURRENT_NAMESPACE),
        name = "wanaku-integration")
@CSVMetadata(
        bundleName = WanakuBundleMetadata.BUNDLE_NAME,
        displayName = "Wanaku Integration",
        description = "Deploys Camel Integration Capability runtimes and reports their registration")
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
public class WanakuIntegrationReconciler implements Reconciler<WanakuIntegration> {
    private static final Logger LOG = Logger.getLogger(WanakuIntegrationReconciler.class);
    static final String RECONCILED_CONDITION = "Reconciled";
    static final String AVAILABLE_CONDITION = "Available";
    private static final Duration NOT_READY_RECHECK = Duration.ofSeconds(30);

    @Inject
    KubernetesClient kubernetesClient;

    /** Reads forwards from the router management API; tests replace it. */
    WanakuForwards.Lookup forwards = WanakuForwards::get;

    @Override
    public List<EventSource<?, WanakuIntegration>> prepareEventSources(EventSourceContext<WanakuIntegration> context) {
        var deployments = InformerEventSourceConfiguration.from(Deployment.class, WanakuIntegration.class)
                .withLabelSelector(OperatorUtil.MANAGED_BY_LABEL + "=" + OperatorUtil.MANAGED_BY_VALUE)
                .build();
        return List.of(new InformerEventSource<>(deployments, context));
    }

    @Override
    public UpdateControl<WanakuIntegration> reconcile(WanakuIntegration resource, Context<WanakuIntegration> context) {
        final String namespace = resource.getMetadata().getNamespace();
        final WanakuIntegrationSpec spec = resource.getSpec();
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

        final Service service = IntegrationResourceFactory.makeService(resource);
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
        final Deployment deployment = IntegrationResourceFactory.makeDeployment(resource);
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
        if (IntegrationResourceFactory.isRegistrationEnabled(resource)) {
            final WanakuIntegrationSpec.Registration registration = spec.getRegistration();
            conditions.add(RuntimeRegistration.condition(
                    forwards,
                    RuntimeWorkloads.managementUrl(spec.getRouterRef()),
                    IntegrationResourceFactory.forwardName(resource),
                    RuntimeRegistration.token(
                            kubernetesClient,
                            namespace,
                            registration != null ? registration.getTokenSecretRef() : null),
                    IntegrationResourceFactory.OWNER_LABEL,
                    workload.available,
                    generation,
                    previous));
        }
        conditions.add(OperatorUtil.aggregateReady(conditions, generation, previous));

        final WanakuRuntimeStatus status = new WanakuRuntimeStatus();
        status.setObservedGeneration(generation);
        if (IntegrationResourceFactory.isRegistrationEnabled(resource)) {
            status.setForwardName(IntegrationResourceFactory.forwardName(resource));
        }
        status.setMcpAddress(IntegrationResourceFactory.mcpAddress(resource));
        status.setWanakuEndpoint(
                RuntimeRegistration.wanakuEndpoint(router, IntegrationResourceFactory.forwardNamespace(resource)));
        status.setConditions(conditions);
        resource.setStatus(status);
        warnOnReadyChange(resource, previous, conditions);

        final UpdateControl<WanakuIntegration> control = UpdateControl.patchStatus(resource);
        if (!OperatorUtil.isTrue(OperatorUtil.findCondition(conditions, OperatorUtil.READY_CONDITION))) {
            // Registration is not visible to any informer, so check it again
            control.rescheduleAfter(NOT_READY_RECHECK);
        }
        return control;
    }

    @Override
    public ErrorStatusUpdateControl<WanakuIntegration> updateErrorStatus(
            WanakuIntegration resource, Context<WanakuIntegration> context, Exception e) {
        LOG.warnf(
                e,
                "WanakuIntegration '%s' reconciliation failed",
                resource.getMetadata().getName());
        setFailedStatus(
                resource,
                "ReconcileError",
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return ErrorStatusUpdateControl.patchStatus(resource);
    }

    String validate(WanakuIntegration resource, WanakuRouter router, String namespace) {
        final WanakuIntegrationSpec spec = resource.getSpec();
        if (StringHelper.isBlank(spec.getRouterRef())) {
            return "spec.routerRef is required";
        }
        if (router == null) {
            return "WanakuRouter %s does not exist in namespace %s".formatted(spec.getRouterRef(), namespace);
        }
        final WanakuIntegrationSpec.Routes routes = spec.getRoutes();
        final int sources = routes == null
                ? 0
                : (routes.getConfigMapRef() != null ? 1 : 0)
                        + (StringHelper.isNotEmpty(routes.getRoutesRef()) ? 1 : 0)
                        + (routes.getServiceCatalog() != null ? 1 : 0);
        if (sources != 1) {
            return "Set exactly one of spec.routes.configMapRef, spec.routes.routesRef and spec.routes.serviceCatalog";
        }
        final boolean fromBarn = routes.getServiceCatalog() != null
                || (routes.getRoutesRef() != null && routes.getRoutesRef().startsWith("datastore://"));
        if (fromBarn && !RouterResourceFactory.isBarnEnabled(router)) {
            return "datastore:// routes and service catalogs are downloaded from Wanaku Barn, "
                    + "but WanakuRouter %s does not enable it (spec.barn.enabled)".formatted(spec.getRouterRef());
        }
        if (routes.getServiceCatalog() != null
                && (StringHelper.isBlank(routes.getServiceCatalog().getName())
                        || StringHelper.isBlank(routes.getServiceCatalog().getSystem()))) {
            return "spec.routes.serviceCatalog requires name and system";
        }
        final WanakuTypes.ConfigMapSource configMap = routes.getConfigMapRef();
        if (configMap != null) {
            if (StringHelper.isBlank(configMap.getName()) || StringHelper.isBlank(configMap.getMain())) {
                return "spec.routes.configMapRef requires name and main";
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
        final int port = IntegrationResourceFactory.mcpPort(resource);
        if (port < 1 || port > 65535) {
            return "spec.mcp.port must be between 1 and 65535";
        }
        final WanakuIntegrationSpec.Registration registration = spec.getRegistration();
        return RuntimeRegistration.missingSecret(
                kubernetesClient, namespace, registration != null ? registration.getTokenSecretRef() : null);
    }

    private void setFailedStatus(WanakuIntegration resource, String reason, String message) {
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

    private void warnOnReadyChange(WanakuIntegration resource, List<Condition> previous, List<Condition> current) {
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

    private static List<Condition> previousConditions(WanakuIntegration resource) {
        return resource.getStatus() != null ? resource.getStatus().getConditions() : null;
    }
}
