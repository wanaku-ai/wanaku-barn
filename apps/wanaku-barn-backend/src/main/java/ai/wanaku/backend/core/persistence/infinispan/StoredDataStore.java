package ai.wanaku.backend.core.persistence.infinispan;

import java.util.HashMap;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DataStoreRecord;

/**
 * The stored form of a data store entry, with derived properties that Ickle queries can filter on.
 * <p>
 * Reads return {@link DataStoreRecord} copies, so this type does not appear in the REST API.
 * </p>
 */
public class StoredDataStore extends DataStoreRecord {

    /** The label that holds the type of the entry, for example {@code catalog}. */
    public static final String TYPE_LABEL = "wanaku.type";

    /** The label that holds the name from {@code index.properties} of a catalog or template. */
    public static final String CATALOG_NAME_LABEL = "wanaku.catalog-name";

    /**
     * Creates a stored entry with the content of the given data store and no metadata.
     *
     * @param dataStore the source data store
     * @return a new stored entry
     */
    public static StoredDataStore from(DataStore dataStore) {
        StoredDataStore stored = new StoredDataStore();
        stored.setId(dataStore.getId());
        stored.setName(dataStore.getName());
        stored.setData(dataStore.getData());
        stored.setLabels(dataStore.getLabels() == null ? null : new HashMap<>(dataStore.getLabels()));
        return stored;
    }

    /**
     * Creates a stored entry with the content and the metadata of a record, for an import.
     *
     * @param record the source record
     * @return a new stored entry
     */
    public static StoredDataStore restore(DataStoreRecord record) {
        StoredDataStore stored = from(record);
        stored.setCreatedAt(record.getCreatedAt());
        stored.setUpdatedAt(record.getUpdatedAt());
        stored.setCreatedBy(record.getCreatedBy());
        stored.setUpdatedBy(record.getUpdatedBy());
        stored.setRevision(record.getRevision());
        return stored;
    }

    /** The value of the {@value #TYPE_LABEL} label, or {@code null}. */
    public String getType() {
        return getLabelValue(TYPE_LABEL);
    }

    /** The value of the {@value #CATALOG_NAME_LABEL} label, or {@code null}. */
    public String getCatalogName() {
        return getLabelValue(CATALOG_NAME_LABEL);
    }

    /** The update time in milliseconds since the epoch, or 0 for entries written before metadata existed. */
    public long getUpdatedAtMillis() {
        return getUpdatedAt() == null ? 0 : getUpdatedAt().toEpochMilli();
    }
}
