package ai.wanaku.backend.api.v1.datastores;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import org.jboss.logging.Logger;
import ai.wanaku.backend.common.LabelsAwareWanakuEntityBean;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.RevisionConflictException;
import ai.wanaku.backend.core.persistence.api.WanakuRepository;
import ai.wanaku.capabilities.sdk.api.exceptions.DataStoreResourceNotFoundException;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.util.StringHelper;

/**
 * Bean for managing DataStore entities.
 */
@ApplicationScoped
public class DataStoresBean extends LabelsAwareWanakuEntityBean<DataStore> {
    private static final Logger LOG = Logger.getLogger(DataStoresBean.class);

    @Inject
    Instance<DataStoreRepository> dataStoreRepositoryInstance;

    private DataStoreRepository dataStoreRepository;

    @PostConstruct
    void init() {
        dataStoreRepository = dataStoreRepositoryInstance.get();
    }

    /**
     * Add a new data store entry.
     *
     * @param dataStore the data store to add
     * @return the persisted data store with generated ID
     */
    public DataStore add(DataStore dataStore) {
        LOG.debugf("Adding data store: %s", dataStore);
        if (dataStore.getId() != null) rejectProtected(dataStoreRepository.findById(dataStore.getId()));
        rejectProtected(dataStore);
        return dataStoreRepository.create(dataStore);
    }

    /**
     * Update an existing data store entry.
     *
     * @param dataStore the data store to update
     * @throws DataStoreResourceNotFoundException if the data store doesn't exist
     */
    public void update(DataStore dataStore) throws WanakuException {
        update(dataStore, null);
    }

    /**
     * Update an existing data store entry when its revision matches.
     *
     * @param dataStore the data store to update
     * @param expectedRevision the revision the caller read, or {@code null} to skip the check
     * @return the stored data store
     * @throws DataStoreResourceNotFoundException if the data store doesn't exist
     * @throws RevisionConflictException if the stored revision is different from the expected revision
     */
    public DataStore update(DataStore dataStore, Long expectedRevision) throws WanakuException {
        LOG.debugf("Updating data store: %s", dataStore);
        if (dataStore.getId() == null) {
            throw new WanakuException("Cannot update data store without ID");
        }
        DataStore existing = dataStoreRepository.findById(dataStore.getId());
        if (existing == null) {
            throw notFound(dataStore.getId());
        }
        rejectProtected(existing);
        rejectProtected(dataStore);
        DataStore stored = dataStoreRepository.update(dataStore.getId(), dataStore, expectedRevision);
        if (stored == null) {
            throw notFound(dataStore.getId());
        }
        return stored;
    }

    private static DataStoreResourceNotFoundException notFound(String id) {
        return new DataStoreResourceNotFoundException("Data store not found with ID: %s".formatted(id));
    }

    /**
     * List all data stores, optionally filtered by label expression.
     *
     * @param labelFilter optional label expression to filter data stores
     * @return list of data stores
     * @throws WanakuException if label expression is invalid
     */
    public List<DataStore> list(String labelFilter) throws WanakuException {
        if (StringHelper.isBlank(labelFilter)) {
            LOG.debug("Listing all data stores");
            return dataStoreRepository.listAll();
        }

        LOG.debugf("Listing data stores with label filter: %s", labelFilter);
        return dataStoreRepository.findAllFilterByLabelExpression(labelFilter);
    }

    /**
     * List all data stores without filtering.
     *
     * @return list of all data stores
     */
    public List<DataStore> list() {
        return list(null);
    }

    /**
     * Find a data store by ID.
     *
     * @param id the ID to search for
     * @return the data store or null if not found
     */
    public DataStore findById(String id) {
        LOG.debugf("Finding data store by ID: %s", id);
        return dataStoreRepository.findById(id);
    }

    /**
     * Find data stores by name.
     *
     * @param name the name to search for
     * @return list of matching data stores
     */
    public List<DataStore> findByName(String name) {
        LOG.debugf("Finding data stores by name: %s", name);
        return dataStoreRepository.findByName(name);
    }

    /**
     * Remove a data store by ID.
     *
     * @param id the ID of the data store to remove
     * @return the number of entries removed
     */
    public int removeById(String id) {
        return removeById(id, null);
    }

    /**
     * Remove a data store by ID when its revision matches.
     *
     * @param id the ID of the data store to remove
     * @param expectedRevision the revision the caller read, or {@code null} to skip the check
     * @return the number of entries removed
     * @throws RevisionConflictException if the stored revision is different from the expected revision
     */
    public int removeById(String id, Long expectedRevision) {
        LOG.debugf("Removing data store by ID: %s", id);
        rejectManagedName(id);
        rejectImmutable(dataStoreRepository.findById(id));
        boolean removed = dataStoreRepository.deleteById(id, expectedRevision);
        return removed ? 1 : 0;
    }

    /**
     * Remove data stores by name.
     *
     * @param name the name of the data stores to remove
     * @return the number of entries removed
     */
    public int remove(String name) {
        LOG.debugf("Removing data stores by name: %s", name);
        rejectManagedName(name);
        List<DataStore> snapshot = dataStoreRepository.findByName(name);
        snapshot.forEach(DataStoresBean::rejectImmutable);
        return (int) snapshot.stream()
                .filter(data -> dataStoreRepository.deleteById(data.getId()))
                .count();
    }

    /** Rejects generic mutation of semantic authoring records and published artifacts. */
    private static void rejectProtected(DataStore data) {
        if (data == null) return;
        if (managedKameletId(data.getId()) || managedKameletId(data.getName()))
            throw new EntityAlreadyExistsException("Use the Kamelet catalog API to modify managed Kamelets");
        if (data.getLabels() == null) return;
        String type = data.getLabels().get("wanaku.type");
        if ("kamelet-revision".equals(type) || "kamelet-current".equals(type))
            throw new EntityAlreadyExistsException("Use the Kamelet catalog API to modify managed Kamelets");
        if ("semantic-definition".equals(type) || "semantic-publication".equals(type))
            throw new WanakuException("Use the semantic router API to modify semantic authoring records");
        rejectImmutable(data);
    }

    private static void rejectManagedName(String id) {
        if (managedKameletId(id))
            throw new EntityAlreadyExistsException("Use the Kamelet catalog API to modify managed Kamelets");
    }

    private static boolean managedKameletId(String id) {
        return id != null && (id.startsWith("kamelet-revision-") || id.startsWith("kamelet-current-"));
    }

    private static void rejectImmutable(DataStore data) {
        if (data != null && (managedKameletId(data.getId()) || managedKameletId(data.getName())))
            throw new EntityAlreadyExistsException("Use the Kamelet catalog API to modify managed Kamelets");
        if (data != null
                && data.getLabels() != null
                && ("kamelet-revision".equals(data.getLabels().get("wanaku.type"))
                        || "kamelet-current".equals(data.getLabels().get("wanaku.type"))
                        || "true".equals(data.getLabels().get("kamelet.immutable"))))
            throw new EntityAlreadyExistsException("Use the Kamelet catalog API to modify managed Kamelets");
        if (data != null
                && data.getLabels() != null
                && "true".equals(data.getLabels().get("semantic.immutable")))
            throw new WanakuException("Published semantic catalog revisions are immutable");
    }

    /** Preserves published revisions during generic bulk removal. */
    @Override
    public int removeIf(String expression) {
        List<DataStore> snapshot = dataStoreRepository.findAllFilterByLabelExpression(expression);
        snapshot.forEach(DataStoresBean::rejectImmutable);
        return (int) snapshot.stream()
                .filter(data -> dataStoreRepository.deleteById(data.getId()))
                .count();
    }

    @Override
    protected WanakuRepository<DataStore, String> getRepository() {
        return dataStoreRepository;
    }
}
