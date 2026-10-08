package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.ServiceCatalogIndex;
import ai.wanaku.core.util.StringHelper;

/**
 * Business logic for service catalog operations.
 * <p>
 * Catalog entries are stored as DataStore entities with label {@code wanaku.type=catalog}.
 * Each entry contains a Base64-encoded ZIP package with an {@code index.properties} manifest.
 * </p>
 */
@ApplicationScoped
public class ServiceCatalogBean {
    private static final Logger LOG = Logger.getLogger(ServiceCatalogBean.class);

    /** Label key used to identify service catalog entries in the data store. */
    public static final String LABEL_TYPE_KEY = "wanaku.type";

    /** Label value used to identify service catalog entries. */
    public static final String LABEL_TYPE_VALUE = "catalog";

    @Inject
    Instance<DataStoreRepository> dataStoreRepositoryInstance;

    @Inject
    CatalogLifecycle lifecycle;

    private DataStoreRepository dataStoreRepository;

    @PostConstruct
    void init() {
        dataStoreRepository = dataStoreRepositoryInstance.get();
    }

    /**
     * List all service catalog entries, optionally filtered by search term.
     *
     * @param search optional search term to filter by catalog name or description
     * @return list of catalog data store entries
     */
    public List<DataStore> list(String search) {
        LOG.debug("Listing service catalogs");
        List<DataStore> all = lifecycle.list(LABEL_TYPE_VALUE);

        if (StringHelper.isBlank(search)) {
            return all;
        }

        String lowerSearch = search.toLowerCase();
        return all.stream().filter(ds -> matchesSearch(ds, lowerSearch)).collect(Collectors.toList());
    }

    /**
     * Get a specific service catalog by its catalog index name.
     * Searches all catalog entries and matches against the name stored in the ZIP's index.properties.
     *
     * @param name the catalog name (from index.properties, not the DataStore name)
     * @return the data store entry, or null if not found
     */
    public DataStore get(String name) {
        LOG.debugf("Getting service catalog: %s", name);
        return lifecycle.find(LABEL_TYPE_VALUE, name);
    }

    /**
     * Deploy a service catalog ZIP package.
     *
     * @param dataStore the data store entry containing the Base64-encoded ZIP
     * @return the persisted data store entry
     * @throws WanakuException if validation fails
     */
    public DataStore deploy(DataStore dataStore) throws WanakuException {
        return deploy(dataStore, CatalogLifecycle.ORIGIN_API, null);
    }

    /**
     * Deploy a service catalog ZIP package as a new version.
     * Validates the ZIP structure, then stores the package as the active version of the catalog with the name
     * from {@code index.properties}. A redeploy keeps the identifier of the catalog entry.
     *
     * @param dataStore the data store entry containing the Base64-encoded ZIP
     * @param origin how the deploy was started
     * @param expectedVersion the active version that the caller expects, or {@code null} to skip the check
     * @return the persisted data store entry
     * @throws WanakuException if validation fails
     */
    public DataStore deploy(DataStore dataStore, String origin, Long expectedVersion) throws WanakuException {
        LOG.debugf("Deploying service catalog: %s", dataStore.getName());

        if (StringHelper.isBlank(dataStore.getName())) {
            throw new WanakuException("Catalog name is required");
        }
        if (StringHelper.isBlank(dataStore.getData())) {
            throw new WanakuException("Catalog data (Base64-encoded ZIP) is required");
        }

        return lifecycle.deploy(LABEL_TYPE_VALUE, dataStore, origin, expectedVersion);
    }

    /**
     * Remove a service catalog by name.
     *
     * @param name the catalog name to remove
     * @return the number of entries removed
     */
    public int remove(String name) {
        LOG.debugf("Removing service catalog: %s", name);
        DataStore catalog = get(name);
        if (catalog == null) {
            return 0;
        }
        if (catalog.getLabels() != null && "true".equals(catalog.getLabels().get("semantic.immutable")))
            throw new WanakuException("Published semantic catalog revisions are immutable");
        boolean removed = dataStoreRepository.deleteById(catalog.getId());
        return removed ? 1 : 0;
    }

    /**
     * Parse the catalog index from a data store entry.
     *
     * @param dataStore the data store entry containing the ZIP
     * @return the parsed index
     * @throws WanakuException if parsing fails
     */
    public ServiceCatalogIndex parseIndex(DataStore dataStore) throws WanakuException {
        return ServiceCatalogIndex.fromBase64(dataStore.getData());
    }

    private boolean matchesSearch(DataStore ds, String lowerSearch) {
        if (ds.getName() != null && ds.getName().toLowerCase().contains(lowerSearch)) {
            return true;
        }
        // Try to parse the index for description matching
        try {
            ServiceCatalogIndex index = ServiceCatalogIndex.fromBase64(ds.getData());
            if (index.getDescription() != null
                    && index.getDescription().toLowerCase().contains(lowerSearch)) {
                return true;
            }
        } catch (WanakuException e) {
            LOG.debugf("Failed to parse catalog index for search: %s", e.getMessage());
        }
        return false;
    }
}
