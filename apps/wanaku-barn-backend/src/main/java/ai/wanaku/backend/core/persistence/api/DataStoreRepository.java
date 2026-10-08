package ai.wanaku.backend.core.persistence.api;

import java.util.List;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

/**
 * Repository interface for DataStore entity operations.
 * <p>
 * This interface extends {@link LabelAwareInfinispanRepository} to provide persistence operations
 * for data store entries, which represent arbitrary binary or text data that can be stored,
 * retrieved, and filtered by labels.
 */
public interface DataStoreRepository extends LabelAwareInfinispanRepository<DataStore, String> {
    /**
     * Atomically stores an entry only when its deterministic identifier is absent.
     * @param dataStore entry with an assigned identifier
     * @return the existing entry, or null when the entry was stored
     */
    DataStore persistIfAbsent(DataStore dataStore);

    /**
     * Creates a new entry. The name and the identifier must be unique.
     *
     * @param dataStore the entry to create; an identifier is assigned when it has none
     * @return the stored entry
     * @throws ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException if an entry with the same
     *         name or identifier exists
     */
    DataStore create(DataStore dataStore);

    /**
     * Replaces an existing entry when its revision matches. A changed name must not belong to another entry.
     *
     * @param id the identifier of the entry
     * @param dataStore the new content
     * @param expectedRevision the revision that the caller read, or {@code null} to skip the check
     * @return the stored entry, or {@code null} when no entry has the identifier
     * @throws RevisionConflictException if the stored revision is different from the expected revision
     * @throws ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException if the new name belongs to
     *         another entry
     */
    DataStore update(String id, DataStore dataStore, Long expectedRevision);

    /**
     * Deletes an entry when its revision matches.
     *
     * @param id the identifier of the entry
     * @param expectedRevision the revision that the caller read, or {@code null} to skip the check
     * @return true if the entry was deleted, false if no entry has the identifier
     * @throws RevisionConflictException if the stored revision is different from the expected revision
     */
    boolean deleteById(String id, Long expectedRevision);

    /**
     * Finds the entries with the given {@code wanaku.type} label.
     *
     * @param type the type, for example {@code catalog}
     * @return the matching entries
     */
    List<DataStore> findByType(String type);

    /**
     * Finds the entries with the given {@code wanaku.type} and {@code wanaku.catalog-name} labels.
     *
     * @param type the type, for example {@code catalog}
     * @param catalogName the name from {@code index.properties}
     * @return the matching entries
     */
    List<DataStore> findByTypeAndCatalogName(String type, String catalogName);

    /**
     * Returns one page of all entries, sorted by name and then by identifier.
     *
     * @param offset the number of entries to skip
     * @param limit the maximum number of entries to return
     * @return the page and the total number of entries
     */
    Page<DataStore> listPage(int offset, int limit);

    /**
     * Find all data stores with the given name.
     *
     * @param name the name to search for
     * @return list of matching data stores, or an empty list if none found
     */
    List<DataStore> findByName(String name);
}
