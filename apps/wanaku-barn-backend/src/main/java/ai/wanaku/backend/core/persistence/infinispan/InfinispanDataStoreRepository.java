package ai.wanaku.backend.core.persistence.infinispan;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.infinispan.Cache;
import org.infinispan.commons.api.query.Query;
import org.infinispan.commons.api.query.QueryResult;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.Page;
import ai.wanaku.backend.core.persistence.api.RevisionConflictException;
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
 * <p>
 * All writes hold the repository lock, so checks that span entries (such as name uniqueness) are atomic in
 * this JVM. Writes that depend on the stored value use conditional cache operations. The repository must be
 * a single instance per cache manager.
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
        return StoredDataStore.class.getCanonicalName();
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
        try {
            lock.lock();
            if (entity.getId() == null) {
                entity.setId(newId());
            }
            return cache().compute(entity.getId(), (id, current) -> stamp(entity, current, id))
                    .copy();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DataStore persistIfAbsent(DataStore dataStore) {
        if (dataStore == null || dataStore.getId() == null)
            throw new IllegalArgumentException("Atomic persistence requires an assigned data store identifier");
        try {
            lock.lock();
            return copyOf(cache().putIfAbsent(dataStore.getId(), stamp(dataStore, null, dataStore.getId())));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DataStore create(DataStore dataStore) {
        try {
            lock.lock();
            if (!findByName(dataStore.getName()).isEmpty()) {
                throw EntityAlreadyExistsException.forName(dataStore.getName());
            }
            String id = dataStore.getId() == null ? newId() : dataStore.getId();
            StoredDataStore stored = stamp(dataStore, null, id);
            if (cache().putIfAbsent(id, stored) != null) {
                throw new EntityAlreadyExistsException("A data store with ID %s already exists".formatted(id));
            }
            dataStore.setId(id);
            return stored.copy();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replaces an existing entry. Does not create missing entries.
     *
     * @return true if the entry existed and was replaced
     */
    @Override
    public boolean update(String id, DataStore entity) {
        return update(id, entity, null) != null;
    }

    @Override
    public DataStore update(String id, DataStore dataStore, Long expectedRevision) {
        try {
            lock.lock();
            StoredDataStore current = cache().get(id);
            if (current == null) {
                return null;
            }
            checkRevision(id, expectedRevision, current);
            if (!Objects.equals(current.getName(), dataStore.getName())
                    && findByName(dataStore.getName()).stream().anyMatch(other -> !id.equals(other.getId()))) {
                throw EntityAlreadyExistsException.forName(dataStore.getName());
            }
            StoredDataStore next = stamp(dataStore, current, id);
            if (!cache().replace(id, current, next)) {
                throw new RevisionConflictException(id, current.getRevision(), revisionOf(cache().get(id)));
            }
            return next.copy();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a change to a copy of an existing entry and stores the result. Does not create missing entries.
     * The write fails with {@link RevisionConflictException} if the entry changes in between.
     *
     * @return true if the entry existed and was replaced
     */
    @Override
    public boolean update(String id, Consumer<DataStore> consumer) {
        DataStoreRecord current = (DataStoreRecord) findById(id);
        if (current == null) {
            return false;
        }
        consumer.accept(current);
        return update(id, current, current.getRevision()) != null;
    }

    @Override
    public void upsert(String id, Consumer<DataStore> consumer) {
        throw new UnsupportedOperationException("Use persist to create or replace data store entries");
    }

    @Override
    public boolean deleteById(String id) {
        return deleteById(id, null);
    }

    @Override
    public boolean deleteById(String id, Long expectedRevision) {
        if (id == null) {
            return false;
        }
        try {
            lock.lock();
            StoredDataStore current = cache().get(id);
            if (current == null) {
                return false;
            }
            checkRevision(id, expectedRevision, current);
            return cache().remove(id, current);
        } finally {
            lock.unlock();
        }
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
    public void replaceAll(List<DataStoreRecord> records) {
        try {
            lock.lock();
            cache().clear();
            for (DataStoreRecord record : records) {
                cache().put(record.getId(), StoredDataStore.restore(record));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<DataStore> findByType(String type) {
        return query("from %s d where d.type = :type".formatted(queryEntityName()), Map.of("type", type));
    }

    @Override
    public List<DataStore> findByTypeAndCatalogName(String type, String catalogName) {
        return query(
                "from %s d where d.type = :type and d.catalogName = :name".formatted(queryEntityName()),
                Map.of("type", type, "name", catalogName));
    }

    @Override
    public Page<DataStore> listPage(int offset, int limit) {
        Query<DataStore> query = cacheManager
                .getCache(entityName())
                .query("from %s d order by d.name, d.id".formatted(queryEntityName()));
        query.startOffset(offset).maxResults(limit);
        QueryResult<DataStore> result = query.execute();
        return new Page<>(
                result.list().stream()
                        .map(InfinispanDataStoreRepository::copyOf)
                        .toList(),
                result.count().value());
    }

    private List<DataStore> query(String statement, Map<String, Object> parameters) {
        Query<DataStore> query = cacheManager.getCache(entityName()).query(statement);
        parameters.forEach(query::setParameter);
        return query.execute().list().stream()
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

    private Cache<String, StoredDataStore> cache() {
        return cacheManager.getCache(entityName());
    }

    private static void checkRevision(String id, Long expectedRevision, DataStoreRecord current) {
        if (expectedRevision != null && expectedRevision != current.getRevision()) {
            throw new RevisionConflictException(id, expectedRevision, current.getRevision());
        }
    }

    private static long revisionOf(DataStoreRecord stored) {
        return stored == null ? 0 : stored.getRevision();
    }

    private static DataStore copyOf(DataStore stored) {
        if (stored == null) {
            return null;
        }
        return stored instanceof DataStoreRecord record ? record.copy() : DataStoreRecord.of(stored);
    }

    /**
     * Builds the record to store for a write. Client-supplied metadata is ignored.
     */
    private static StoredDataStore stamp(DataStore incoming, DataStoreRecord current, String id) {
        StoredDataStore next = StoredDataStore.from(incoming);
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
