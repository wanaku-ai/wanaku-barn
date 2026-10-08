package ai.wanaku.operator.wanaku;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import io.quarkiverse.operatorsdk.annotations.RBACVerbs;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.ServiceCatalogService;

import static ai.wanaku.operator.util.OperatorUtil.READY_CONDITION;
import static ai.wanaku.operator.util.OperatorUtil.findCondition;
import static ai.wanaku.operator.util.OperatorUtil.getRouterBaseUrl;
import static ai.wanaku.operator.util.OperatorUtil.readyCondition;

@ControllerConfiguration(
        informer = @Informer(namespaces = Constants.WATCH_CURRENT_NAMESPACE),
        name = "wanaku-service-catalog")
@CSVMetadata(
        displayName = "Wanaku Service Catalog operator",
        description = "Deploys and manages Wanaku Service Catalogs")
@RBACRule(
        apiGroups = "",
        resources = {"configmaps"},
        verbs = {RBACVerbs.GET, RBACVerbs.LIST, RBACVerbs.WATCH})
@RBACRule(
        apiGroups = "",
        resources = {"secrets"},
        verbs = {RBACVerbs.GET, RBACVerbs.LIST, RBACVerbs.WATCH})
public class WanakuServiceCatalogReconciler implements Reconciler<WanakuServiceCatalog>, Cleaner<WanakuServiceCatalog> {
    private static final Logger LOG = Logger.getLogger(WanakuServiceCatalogReconciler.class);

    private static final String CATALOG_DATA_KEY = "catalog.zip";

    private volatile ServiceCatalogService cachedClient;
    private volatile String cachedBaseUri;

    @Inject
    KubernetesClient kubernetesClient;

    @Override
    public UpdateControl<WanakuServiceCatalog> reconcile(
            WanakuServiceCatalog resource, Context<WanakuServiceCatalog> context) {
        LOG.infof(
                "Starting service catalog reconciliation for %s",
                resource.getMetadata().getName());

        final String namespace = resource.getMetadata().getNamespace();

        final String routerRef = resource.getSpec().getRouterRef();
        if (routerRef == null || routerRef.isBlank()) {
            return setErrorStatus(
                    resource,
                    "ValidationError",
                    "routerRef must be specified in the WanakuServiceCatalog spec to indicate which WanakuRouter to deploy to.");
        }

        WanakuRouter router = kubernetesClient
                .resources(WanakuRouter.class)
                .inNamespace(namespace)
                .withName(routerRef)
                .get();
        if (router == null) {
            return setErrorStatus(
                    resource,
                    "ValidationError",
                    String.format(
                            "Referenced WanakuRouter '%s' not found in namespace '%s'. "
                                    + "Ensure the WanakuRouter resource is created before the WanakuServiceCatalog.",
                            routerRef, namespace));
        }

        WanakuRouterSpec.RouterSpec routerSpec = router.getSpec().getRouter();
        if (routerSpec == null || !routerSpec.isEnabled()) {
            return setErrorStatus(
                    resource,
                    "ValidationError",
                    String.format(
                            "WanakuRouter '%s' does not have barn-backend enabled (spec.router.enabled must be true). "
                                    + "Service catalogs require barn-backend for persistence.",
                            routerRef));
        }

        final String routerBaseUrl = getRouterBaseUrl(routerRef);

        final List<String> deployedCatalogs;
        try {
            deployedCatalogs = deployCatalogs(resource, namespace, routerBaseUrl);
        } catch (WanakuException e) {
            return setErrorStatus(resource, "DeploymentError", e.getMessage());
        }

        final WanakuServiceCatalogStatus status = new WanakuServiceCatalogStatus();
        status.setDeployedCatalogs(deployedCatalogs);
        final Condition previousReadyCondition = findCondition(
                resource.getStatus() != null ? resource.getStatus().getConditions() : null, READY_CONDITION);
        status.setConditions(List.of(readyCondition(
                resource.getMetadata().getGeneration(),
                previousReadyCondition,
                "WanakuServiceCatalog deployment is ready")));
        resource.setStatus(status);

        return UpdateControl.patchStatus(resource);
    }

    @Override
    public DeleteControl cleanup(WanakuServiceCatalog resource, Context<WanakuServiceCatalog> context) {
        LOG.infof("Cleaning up service catalogs for %s", resource.getMetadata().getName());

        final String routerRef = resource.getSpec().getRouterRef();
        if (routerRef == null || routerRef.isBlank()) {
            return DeleteControl.defaultDelete();
        }

        List<String> deployedCatalogs =
                resource.getStatus() != null ? resource.getStatus().getDeployedCatalogs() : null;
        if (deployedCatalogs == null || deployedCatalogs.isEmpty()) {
            return DeleteControl.defaultDelete();
        }

        final String routerBaseUrl = getRouterBaseUrl(routerRef);
        for (String catalogName : deployedCatalogs) {
            try {
                removeServiceCatalog(routerBaseUrl, catalogName);
                LOG.infof("Removed service catalog '%s' from router", catalogName);
            } catch (Exception e) {
                LOG.warnf("Failed to remove service catalog '%s' during cleanup: %s", catalogName, e.getMessage());
            }
        }

        return DeleteControl.defaultDelete();
    }

    private UpdateControl<WanakuServiceCatalog> setErrorStatus(
            WanakuServiceCatalog resource, String reason, String message) {
        LOG.warnf(
                "WanakuServiceCatalog '%s' error (%s): %s",
                resource.getMetadata().getName(), reason, message);

        final WanakuServiceCatalogStatus status = new WanakuServiceCatalogStatus();
        Condition condition = new ConditionBuilder()
                .withType(READY_CONDITION)
                .withStatus("False")
                .withObservedGeneration(resource.getMetadata().getGeneration())
                .withLastTransitionTime(OffsetDateTime.now(ZoneOffset.UTC).toString())
                .withReason(reason)
                .withMessage(message)
                .build();
        status.setConditions(List.of(condition));
        resource.setStatus(status);

        return UpdateControl.patchStatus(resource);
    }

    private List<String> deployCatalogs(WanakuServiceCatalog resource, String namespace, String routerBaseUrl)
            throws WanakuException {
        List<WanakuServiceCatalogSpec.CatalogEntrySpec> catalogs =
                resource.getSpec().getCatalogs();
        if (catalogs == null || catalogs.isEmpty()) {
            LOG.infof("No catalogs to deploy for %s", resource.getMetadata().getName());
            return List.of();
        }

        List<String> deployed = new ArrayList<>();
        for (WanakuServiceCatalogSpec.CatalogEntrySpec entry : catalogs) {
            String catalogName = entry.getName();
            String configMapName = entry.getConfigMapRef();

            LOG.infof("Deploying service catalog '%s' from ConfigMap '%s'", catalogName, configMapName);

            ConfigMap configMap = kubernetesClient
                    .configMaps()
                    .inNamespace(namespace)
                    .withName(configMapName)
                    .get();
            if (configMap == null) {
                throw new WanakuException(String.format(
                        "ConfigMap '%s' not found in namespace '%s' for catalog '%s'",
                        configMapName, namespace, catalogName));
            }

            Map<String, String> data = configMap.getData();
            if (data == null || !data.containsKey(CATALOG_DATA_KEY)) {
                throw new WanakuException(String.format(
                        "ConfigMap '%s' does not contain key '%s' for catalog '%s'",
                        configMapName, CATALOG_DATA_KEY, catalogName));
            }

            String catalogData = data.get(CATALOG_DATA_KEY);
            deployServiceCatalog(routerBaseUrl, catalogName, catalogData);
            deployed.add(catalogName);
            LOG.infof("Successfully deployed service catalog '%s'", catalogName);
        }

        return deployed;
    }

    private void deployServiceCatalog(String routerBaseUrl, String name, String data) throws WanakuException {
        DataStore dataStore = new DataStore();
        dataStore.setName(name);
        dataStore.setData(data);

        try {
            deployOrRestore(getOrCreateClient(routerBaseUrl), name, dataStore);
        } catch (WebApplicationException e) {
            throw new WanakuException(
                    String.format(
                            "Failed to deploy service catalog '%s': HTTP %d - %s",
                            name, e.getResponse().getStatus(), e.getResponse().readEntity(String.class)),
                    e);
        } catch (Exception e) {
            throw new WanakuException("Failed to deploy service catalog '%s'".formatted(name), e);
        }
    }

    /**
     * Deploys a catalog. Barn keeps removed catalogs and rejects a deploy with the name of a removed catalog
     * (HTTP 409). The resource declares that the catalog must exist, so the removed catalog is restored and the
     * deploy is repeated.
     */
    static void deployOrRestore(ServiceCatalogService service, String name, DataStore dataStore) {
        try {
            service.deploy(dataStore);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() != 409) {
                throw e;
            }
            try {
                service.restore(name);
            } catch (WebApplicationException restoreFailure) {
                // The conflict has another cause: report the original deploy failure
                throw e;
            }
            LOG.infof("Restored removed service catalog '%s' before deploying it", name);
            service.deploy(dataStore);
        }
    }

    private void removeServiceCatalog(String routerBaseUrl, String name) {
        ServiceCatalogService service = getOrCreateClient(routerBaseUrl);
        service.remove(name);
    }

    private ServiceCatalogService getOrCreateClient(String routerBaseUrl) {
        if (cachedClient != null && routerBaseUrl.equals(cachedBaseUri)) {
            return cachedClient;
        }

        cachedClient = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(routerBaseUrl))
                .build(ServiceCatalogService.class);
        cachedBaseUri = routerBaseUrl;
        return cachedClient;
    }
}
