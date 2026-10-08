package ai.wanaku.backend.core.persistence.infinispan;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.infinispan.Cache;
import org.infinispan.commons.api.query.Query;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DataStoreRecord;

/**
 * Infinispan implementation of the DataStoreRepository with label support.
 * <p>
 * Every write stores a new {@link DataStoreRecord}: the creation metadata of the previous record is kept,
 * the update time is set and the revision is incremented. The cache holds live objects, so reads return
 * detached copies and writes never modify a stored record in place.
 * </p>
 */
public class InfinispanDataStoreRepository extends AbstractLabelAwareInfinispanRepository<DataStore, String>
        implements DataStoreRepository {

    public InfinispanDataStoreRepository(EmbeddedCacheManager cacheManager, Configuration configuration) {
        super(cacheManager, configuration);
    }

    @Override
    protected String entityName() {
        return "datastore";
    }

    @Override
    protected Class<DataStore> entityType() {
        return DataStore.class;
    }

    @Override
    protected String queryEntityName() {
        return DataStoreRecord.class.getCanonicalName();
    }

    @Override
    protected String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Creates the entry, or replaces the entry with the same identifier.
     *
     * @param entity the data store to write; an identifier is assigned when it has none
     * @return a copy of the stored record
     */
    @Override
    public DataStore persist(DataStore entity) {
        if (entity.getId() != null && entity.getId().startsWith("kamelet-revision-"))
            throw new EntityAlreadyExistsException("Immutable Kamelet revisions require atomic creation");
        if (entity.getId() != null
                && entity.getId().startsWith("kamelet-current-")
                && (entity.getLabels() == null
                        || !"kamelet-current".equals(entity.getLabels().get("wanaku.type"))))
            throw new EntityAlreadyExistsException("Use the Kamelet catalog API to modify current selections");
        if (entity.getId() == null) {
            entity.setId(newId());
        }
        return cache().compute(entity.getId(), (id, current) -> stamp(entity, current))
                .copy();
    }

    @Override
    public DataStore persistIfAbsent(DataStore dataStore) {
        if (dataStore == null || dataStore.getId() == null)
            throw new IllegalArgumentException("Atomic persistence requires an assigned data store identifier");
        return copyOf(cache().putIfAbsent(dataStore.getId(), stamp(dataStore, null)));
    }

    /**
     * Replaces an existing entry. Does not create missing entries.
     *
     * @return true if the entry existed and was replaced
     */
    @Override
    public boolean update(String id, DataStore entity) {
        return cache().computeIfPresent(id, (key, current) -> stamp(entity, current, key)) != null;
    }

    /**
     * Applies a change to a copy of an existing entry and stores the result. Does not create missing entries.
     *
     * @return true if the entry existed and was replaced
     */
    @Override
    public boolean update(String id, Consumer<DataStore> consumer) {
        DataStore current = findById(id);
        if (current == null) {
            return false;
        }
        consumer.accept(current);
        return update(id, current);
    }

    @Override
    public void upsert(String id, Consumer<DataStore> consumer) {
        throw new UnsupportedOperationException("Use persist to create or replace data store entries");
    }

    @Override
    public DataStore findById(String id) {
        return copyOf(cache().get(id));
    }

    @Override
    public List<DataStore> listAll() {
        return cache().values().stream()
                .map(InfinispanDataStoreRepository::copyOf)
                .toList();
    }

    @Override
    public List<DataStore> findAllFilterByLabelExpression(String labelExpression) {
        return super.findAllFilterByLabelExpression(labelExpression).stream()
                .map(InfinispanDataStoreRepository::copyOf)
                .toList();
    }

    @Override
    public List<DataStore> findByName(String name) {
        Query<DataStore> query = cacheManager
                .getCache(entityName())
                .query("from %s d where d.name = :name".formatted(queryEntityName()));
        query.setParameter("name", name);
        return query.execute().list().stream()
                .map(InfinispanDataStoreRepository::copyOf)
                .toList();
    }

    private Cache<String, DataStoreRecord> cache() {
        return cacheManager.getCache(entityName());
    }

    private static DataStore copyOf(DataStore stored) {
        if (stored == null) {
            return null;
        }
        return stored instanceof DataStoreRecord record ? record.copy() : DataStoreRecord.of(stored);
    }

    static DataStoreRecord stamp(DataStore incoming, DataStoreRecord current) {
        return stamp(incoming, current, incoming.getId());
    }

    /**
     * Builds the record to store for a write. Client-supplied metadata is ignored.
     */
    private static DataStoreRecord stamp(DataStore incoming, DataStoreRecord current, String id) {
        DataStoreRecord next = DataStoreRecord.of(incoming);
        next.setId(id);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (current == null) {
            next.setCreatedAt(now);
            next.setRevision(1);
        } else {
            next.setCreatedAt(current.getCreatedAt());
            next.setCreatedBy(current.getCreatedBy());
            next.setRevision(current.getRevision() + 1);
        }
        next.setUpdatedAt(now);
        return next;
    }
}
