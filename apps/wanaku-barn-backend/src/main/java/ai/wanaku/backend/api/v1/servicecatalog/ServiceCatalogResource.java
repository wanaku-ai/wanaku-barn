package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import ai.wanaku.backend.audit.AuditContext;
import ai.wanaku.backend.audit.Audited;
import ai.wanaku.backend.common.Paging;
import ai.wanaku.capabilities.sdk.api.exceptions.DataStoreResourceNotFoundException;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.core.services.api.CatalogVersion;
import ai.wanaku.core.services.api.DeploymentInstructions;
import ai.wanaku.core.services.api.ServiceCatalogIndex;
import ai.wanaku.core.services.api.ValidationResult;
import ai.wanaku.core.util.StringHelper;

/**
 * REST API resource for service catalog operations.
 * Base path: /api/v1/service-catalog
 */
@ApplicationScoped
@Path("/api/v1/service-catalog")
public class ServiceCatalogResource {
    private static final Logger LOG = Logger.getLogger(ServiceCatalogResource.class);

    @Inject
    AuditContext auditContext;

    @Inject
    ServiceCatalogBean serviceCatalogBean;

    @Inject
    CatalogLifecycle lifecycle;

    @Inject
    DeploymentInstructionsBean deploymentInstructionsBean;

    @Inject
    CatalogValidator catalogValidator;

    /**
     * List all service catalog entries, optionally filtered by search term.
     * GET /api/v1/service-catalog
     * GET /api/v1/service-catalog?search={term}
     *
     * @param search optional search term
     * @return response with list of catalog summaries
     */
    @GET
    public RestResponse<WanakuResponse<List<Map<String, Object>>>> list(
            @QueryParam("search") String search,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        boolean paged = Paging.requested(offset, limit);
        if (search != null && !search.isBlank()) {
            LOG.debugf("REST: Listing service catalogs with search: %s", search);
        } else {
            LOG.debug("REST: Listing all service catalogs");
        }

        List<DataStore> catalogs = serviceCatalogBean.list(search);
        Long total = null;
        if (paged && StringHelper.isBlank(search)) {
            // Sort by the name label, so only the catalogs of the page are decoded
            List<DataStore> sorted = catalogs.stream()
                    .sorted(Comparator.comparing(
                                    (DataStore ds) -> ds.getLabels().get(CatalogLifecycle.CATALOG_NAME_LABEL),
                                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                            .thenComparing(DataStore::getId))
                    .toList();
            total = (long) sorted.size();
            catalogs = Paging.slice(sorted, offset, limit);
        }
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (DataStore ds : catalogs) {
            try {
                ServiceCatalogIndex index = serviceCatalogBean.parseIndex(ds);
                Map<String, Object> summary = new HashMap<>();
                summary.put("id", ds.getId());
                summary.put("name", index.getName());
                summary.put("icon", index.getIcon());
                summary.put("description", index.getDescription());
                summary.put(
                        "services",
                        index.getServiceNames().stream()
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .toList());
                summaries.add(summary);
            } catch (WanakuException e) {
                LOG.warnf("Failed to parse catalog index for '%s': %s", ds.getName(), e.getMessage());
            }
        }

        summaries.sort(Comparator.comparing(
                        (Map<String, Object> m) -> (String) m.get("name"),
                        Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(m -> (String) m.get("id"), Comparator.nullsFirst(Comparator.naturalOrder())));

        if (paged && total == null) {
            total = (long) summaries.size();
            summaries = Paging.slice(summaries, offset, limit);
        }
        return Paging.response(summaries, total);
    }

    /**
     * Get a specific service catalog by name with system details.
     * GET /api/v1/service-catalog/{name}
     *
     * @param name the catalog name
     * @return response with catalog detail including system information
     */
    @Path("/{name}")
    @GET
    public WanakuResponse<Map<String, Object>> get(@PathParam("name") String name) {
        LOG.debugf("REST: Getting service catalog: %s", name);

        DataStore catalog = serviceCatalogBean.get(name);
        if (catalog == null) {
            throw new DataStoreResourceNotFoundException("Service catalog not found: " + name);
        }

        ServiceCatalogIndex index = serviceCatalogBean.parseIndex(catalog);
        Map<String, Object> detail = new HashMap<>();
        detail.put("id", catalog.getId());
        detail.put("name", index.getName());
        detail.put("icon", index.getIcon());
        detail.put("description", index.getDescription());

        List<Map<String, String>> systems = new ArrayList<>();
        for (String system : index.getServiceNames()) {
            Map<String, String> systemInfo = new HashMap<>();
            systemInfo.put("name", system);
            systemInfo.put("routesFile", index.getRoutesFile(system));
            systemInfo.put("rulesFile", index.getRulesFile(system));
            systemInfo.put("dependenciesFile", index.getDependenciesFile(system));
            systems.add(systemInfo);
        }
        detail.put("services", systems);

        return new WanakuResponse<>(detail);
    }

    /**
     * Download a service catalog by name, returning the raw DataStore with Base64-encoded ZIP data.
     * GET /api/v1/service-catalog/download?name={name}
     *
     * @param name the catalog name
     * @return response with the DataStore containing the Base64-encoded ZIP
     */
    @Path("/download")
    @GET
    public WanakuResponse<DataStore> download(@QueryParam("name") String name) {
        LOG.debugf("REST: Downloading service catalog: %s", name);

        if (name == null || name.isBlank()) {
            throw new WanakuException("Query parameter 'name' is required");
        }

        DataStore catalog = serviceCatalogBean.get(name);
        if (catalog == null) {
            throw new DataStoreResourceNotFoundException("Service catalog not found: " + name);
        }

        return new WanakuResponse<>(catalog);
    }

    /**
     * Deploy a service catalog ZIP package.
     * POST /api/v1/service-catalog
     *
     * @param dataStore the data store entry containing the Base64-encoded ZIP
     * @return response with the created data store entry
     */
    @POST
    @Audited(operation = "service_catalog.deploy", targetType = "service_catalog", targetField = "name")
    public WanakuResponse<DataStore> deploy(@QueryParam("expectedVersion") Long expectedVersion, DataStore dataStore) {
        LOG.debugf("REST: Deploying service catalog: %s", dataStore.getName());
        auditContext.setTarget(dataStore.getName());
        DataStore result = serviceCatalogBean.deploy(dataStore, CatalogLifecycle.ORIGIN_API, expectedVersion);
        auditContext.setPolicyRevision(Long.toString(CatalogLifecycle.activeVersion(result)));
        return new WanakuResponse<>(result);
    }

    /**
     * List the removed service catalogs. Removed catalogs keep their versions and can be restored.
     * GET /api/v1/service-catalog/removed
     *
     * @return response with the name, removal time and active version of each removed catalog
     */
    @Path("/removed")
    @GET
    public WanakuResponse<List<Map<String, Object>>> removed() {
        return new WanakuResponse<>(lifecycle.removedSummaries(ServiceCatalogBean.LABEL_TYPE_VALUE));
    }

    /**
     * Restore a removed service catalog with its active version.
     * POST /api/v1/service-catalog/{name}/restore
     *
     * @param name the catalog name
     * @return response with the restored catalog entry
     */
    @Path("/{name}/restore")
    @POST
    @Audited(operation = "service_catalog.restore", targetType = "service_catalog")
    public WanakuResponse<DataStore> restore(@PathParam("name") String name) {
        return new WanakuResponse<>(lifecycle.restoreRemoved(ServiceCatalogBean.LABEL_TYPE_VALUE, name));
    }

    /**
     * List the versions of a service catalog, newest first.
     * GET /api/v1/service-catalog/{name}/versions
     *
     * @param name the catalog name
     * @return response with the version metadata
     */
    @Path("/{name}/versions")
    @GET
    public WanakuResponse<List<CatalogVersion>> versions(
            @PathParam("name") String name, @QueryParam("from") String from, @QueryParam("to") String to) {
        return new WanakuResponse<>(lifecycle.versions(
                ServiceCatalogBean.LABEL_TYPE_VALUE, name, instant("from", from), instant("to", to)));
    }

    /**
     * Get the metadata of one version of a service catalog.
     * GET /api/v1/service-catalog/{name}/versions/{version}
     *
     * @param name the catalog name
     * @param version the version number
     * @return response with the version metadata
     */
    @Path("/{name}/versions/{version}")
    @GET
    public WanakuResponse<CatalogVersion> version(@PathParam("name") String name, @PathParam("version") long version) {
        return new WanakuResponse<>(lifecycle.version(ServiceCatalogBean.LABEL_TYPE_VALUE, name, version));
    }

    /**
     * Download the package of one version of a service catalog.
     * GET /api/v1/service-catalog/{name}/versions/{version}/download
     *
     * @param name the catalog name
     * @param version the version number
     * @return response with a DataStore that contains the Base64-encoded ZIP
     */
    @Path("/{name}/versions/{version}/download")
    @GET
    public WanakuResponse<DataStore> downloadVersion(
            @PathParam("name") String name, @PathParam("version") long version) {
        return new WanakuResponse<>(lifecycle.content(ServiceCatalogBean.LABEL_TYPE_VALUE, name, version));
    }

    /**
     * Restore an earlier version of a service catalog. The restore creates a new version.
     * POST /api/v1/service-catalog/{name}/versions/{version}/activate
     *
     * @param name the catalog name
     * @param version the version to restore
     * @param expectedVersion optional active version that the caller expects; a mismatch returns 409
     * @return response with the catalog entry
     */
    @Path("/{name}/versions/{version}/activate")
    @POST
    @Audited(operation = "service_catalog.activate_version", targetType = "service_catalog")
    public WanakuResponse<DataStore> activateVersion(
            @PathParam("name") String name,
            @PathParam("version") long version,
            @QueryParam("expectedVersion") Long expectedVersion) {
        DataStore result = lifecycle.restore(ServiceCatalogBean.LABEL_TYPE_VALUE, name, version, expectedVersion);
        auditContext.setPolicyRevision(Long.toString(CatalogLifecycle.activeVersion(result)));
        return new WanakuResponse<>(result);
    }

    /**
     * Validate a service catalog package without deploying it.
     * POST /api/v1/service-catalog/validate
     *
     * @param dataStore the data store entry containing the Base64-encoded ZIP
     * @return response with the validation result: HTTP 200 with the errors in the body when the
     *         package is invalid, or HTTP 422 when there is no package data to validate
     */
    @Path("/validate")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            description = "The package was validated: check 'valid' and 'errors' in the body for the outcome")
    @APIResponse(responseCode = "422", description = "The request carries no package data to validate")
    public WanakuResponse<ValidationResult> validate(DataStore dataStore) {
        LOG.debugf("REST: Validating service catalog: %s", dataStore != null ? dataStore.getName() : null);
        return new WanakuResponse<>(catalogValidator.validateCatalog(dataStore));
    }

    /**
     * Remove a service catalog by name.
     * DELETE /api/v1/service-catalog/{name}
     *
     * @param name the catalog name to remove
     * @return HTTP 200 if removed, 404 if not found
     */
    @Path("/{name}")
    @DELETE
    @Audited(operation = "service_catalog.remove", targetType = "service_catalog")
    public WanakuResponse<Void> remove(@PathParam("name") String name) {
        LOG.debugf("REST: Removing service catalog: %s", name);

        int removed = serviceCatalogBean.remove(name);
        if (removed > 0) {
            return new WanakuResponse<>();
        } else {
            throw new DataStoreResourceNotFoundException(name);
        }
    }

    /**
     * Get deployment instructions for a service catalog.
     * {@code GET /api/v1/service-catalog/instructions?name={name}&model={model}}
     *
     * @param name the catalog name
     * @param model the deployment model: local, docker, or kubernetes
     * @return response with deployment instructions including commands/YAML and placeholder definitions
     */
    @Path("/instructions")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public WanakuResponse<DeploymentInstructions> getDeploymentInstructions(
            @QueryParam("name") String name, @QueryParam("model") String model) {
        LOG.debugf("REST: Getting deployment instructions for catalog '%s' with model '%s'", name, model);

        if (name == null || name.isBlank()) {
            throw new WanakuException("Query parameter 'name' is required");
        }
        if (model == null || model.isBlank()) {
            throw new WanakuException("Query parameter 'model' is required");
        }

        DeploymentInstructions instructions = deploymentInstructionsBean.generateInstructions(name, model);
        return new WanakuResponse<>(instructions);
    }

    private static Instant instant(String parameter, String value) {
        if (StringHelper.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'%s' must be an ISO 8601 UTC timestamp".formatted(parameter));
        }
    }
}
