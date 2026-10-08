package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.commons.api.query.Query;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.logging.Logger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.audit.AuditEvent;
import ai.wanaku.backend.audit.AuditStore;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.RevisionConflictException;
import ai.wanaku.backend.core.persistence.infinispan.StoredDataStore;
import ai.wanaku.capabilities.sdk.api.exceptions.DataStoreResourceNotFoundException;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.CatalogVersion;
import ai.wanaku.core.services.api.DataStoreRecord;
import ai.wanaku.core.services.api.SafeZip;
import ai.wanaku.core.services.api.ServiceCatalogIndex;

/**
 * Version history for service catalogs and service templates.
 * <p>
 * Each catalog or template has one data store entry with the active content. Its identifier does not change
 * when the content changes. The entry carries the active version number in the {@value #VERSION_LABEL} label.
 * Each deploy also stores an immutable version with the content, so earlier content can be listed, downloaded
 * and restored. A restore creates a new version; it never changes an earlier one.
 * </p>
 * <p>
 * The name in {@code index.properties} identifies a catalog or template. One lock serializes all deploys and
 * restores in this JVM, so the version number, the version content and the active pointer change together.
 * </p>
 */
@ApplicationScoped
public class CatalogLifecycle {
    private static final Logger LOG = Logger.getLogger(CatalogLifecycle.class);

    /** The label that holds the type of a catalog or template entry. */
    public static final String TYPE_LABEL = "wanaku.type";

    /** The label that holds the name from {@code index.properties}. The cache index uses it for lookups. */
    public static final String CATALOG_NAME_LABEL = StoredDataStore.CATALOG_NAME_LABEL;

    /** The label that holds the active version number of a catalog or template entry. */
    public static final String VERSION_LABEL = "wanaku.version";

    /** The label that holds the removal time (ISO 8601 UTC) of a removed catalog or template. */
    public static final String REMOVED_AT_LABEL = "wanaku.removed-at";

    /** The suffix of the type label of a removed catalog or template, for example {@code catalog.removed}. */
    public static final String REMOVED_SUFFIX = ".removed";

    public static final String ORIGIN_API = "api";
    public static final String ORIGIN_STARTUP = "startup";
    public static final String ORIGIN_INSTANTIATE = "instantiate";
    public static final String ORIGIN_RESTORE = "restore";
    public static final String ORIGIN_LEGACY = "legacy";

    static final String VERSIONS_CACHE = "catalog-version";
    static final String COUNTERS_CACHE = "catalog-version-counter";
    private static final String VERSION_TYPE = CatalogVersionRecord.class.getCanonicalName();

    @Inject
    DataStoreRepository repository;

    @Inject
    EmbeddedCacheManager cacheManager;

    @Inject
    Configuration configuration;

    @Inject
    AuditStore auditStore;

    @ConfigProperty(name = "wanaku.catalog.max-versions", defaultValue = "50")
    int maxVersions;

    @ConfigProperty(name = "wanaku.catalog.purge-after")
    Optional<Duration> purgeAfter;

    private final ReentrantLock lock = new ReentrantLock();

    @PostConstruct
    void init() {
        for (String name : List.of(VERSIONS_CACHE, COUNTERS_CACHE)) {
            if (cacheManager.getCacheConfiguration(name) == null) {
                cacheManager.defineConfiguration(name, configuration);
            }
        }
    }

    /**
     * Gives version 1 to the catalogs and templates that were stored before versioning existed.
     * Runs before the built-in templates are seeded. Entries that already have a version are skipped.
     */
    void migrateLegacyEntries(@Observes @Priority(1) StartupEvent event) {
        for (String type : List.of(ServiceCatalogBean.LABEL_TYPE_VALUE, ServiceTemplateBean.LABEL_TYPE_VALUE)) {
            for (String stored : List.of(type, removedType(type))) {
                for (DataStore entry : list(stored)) {
                    migrate(type, entry);
                }
            }
        }
    }

    /**
     * Gives version 1 to an entry without a version, and adds the {@value #CATALOG_NAME_LABEL} label when it is
     * missing. The entry is updated in place.
     */
    private void migrate(String type, DataStore entry) {
        Map<String, String> labels = entry.getLabels();
        if (labels.containsKey(VERSION_LABEL) && labels.containsKey(CATALOG_NAME_LABEL)) {
            return;
        }
        try {
            String name = ServiceCatalogIndex.fromBase64(entry.getData()).getName();
            if (!labels.containsKey(VERSION_LABEL)) {
                deploy(type, entry, ORIGIN_LEGACY, null, null, entry);
                LOG.infof("Created version history for existing %s '%s'", type, name);
            } else {
                Map<String, String> updated = new HashMap<>(labels);
                updated.put(CATALOG_NAME_LABEL, name);
                relabel(entry, updated);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Cannot migrate %s entry %s: %s", type, entry.getId(), e.getMessage());
        }
    }

    /**
     * Lists the entries of a type.
     *
     * @param type {@code catalog} or {@code template}
     * @return the entries
     */
    public List<DataStore> list(String type) {
        return repository.findByType(type);
    }

    /**
     * Finds the entry with the given name in {@code index.properties}. Uses the cache index.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @return the entry, or {@code null}
     */
    public DataStore find(String type, String name) {
        List<DataStore> found = repository.findByTypeAndCatalogName(type, name);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Stores a new version and makes it the active content.
     *
     * @param type {@code catalog} or {@code template}
     * @param incoming the package to deploy
     * @param origin how the deploy was started
     * @param expectedVersion the active version that the caller expects, or {@code null} to skip the check
     * @return the entry with the new active content
     * @throws RevisionConflictException if the active version is different from the expected version
     */
    public DataStore deploy(String type, DataStore incoming, String origin, Long expectedVersion) {
        return deploy(type, incoming, origin, expectedVersion, null, null);
    }

    /**
     * Restores the content of an earlier version as a new version.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @param version the version to restore
     * @param expectedVersion the active version that the caller expects, or {@code null} to skip the check
     * @return the entry with the restored content
     */
    public DataStore restore(String type, String name, long version, Long expectedVersion) {
        CatalogVersionRecord source = record(type, name, version);
        if (source.getData() == null) {
            throw new InvalidPayloadException(
                    "Version %d of %s was rejected and has no content".formatted(version, name));
        }
        DataStore content = new DataStore(null, source.getDataStoreName(), source.getData());
        content.setLabels(new HashMap<>(source.getLabels()));
        return deploy(type, content, ORIGIN_RESTORE, expectedVersion, version, null);
    }

    /**
     * Lists the versions of a catalog or template, newest first.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @return the version metadata
     * @throws DataStoreResourceNotFoundException if the name has no versions
     */
    public List<CatalogVersion> versions(String type, String name) {
        return versions(type, name, null, null);
    }

    /**
     * Lists the versions of a catalog or template that were created in a time range, newest first.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @param from the earliest creation time (inclusive), or {@code null}
     * @param to the latest creation time (inclusive), or {@code null}
     * @return the version metadata
     * @throws DataStoreResourceNotFoundException if the name has no versions
     */
    public List<CatalogVersion> versions(String type, String name, Instant from, Instant to) {
        if (records(type, name).isEmpty()) {
            throw new DataStoreResourceNotFoundException("No versions found for %s '%s'".formatted(type, name));
        }
        long active = activeVersion(findAny(type, name));
        StringBuilder statement =
                new StringBuilder("from %s v where v.type = :type and v.name = :name".formatted(VERSION_TYPE));
        Map<String, Object> parameters = new HashMap<>(Map.of("type", type, "name", name));
        if (from != null) {
            statement.append(" and v.createdAtMillis >= :from");
            parameters.put("from", from.toEpochMilli());
        }
        if (to != null) {
            statement.append(" and v.createdAtMillis <= :to");
            parameters.put("to", to.toEpochMilli());
        }
        statement.append(" order by v.version desc");
        Query<CatalogVersionRecord> query = versions().query(statement.toString());
        parameters.forEach(query::setParameter);
        return query.execute().list().stream()
                .map(record -> record.metadata(status(record, active)))
                .toList();
    }

    /**
     * Returns the metadata of one version.
     *
     * @throws DataStoreResourceNotFoundException if the version does not exist
     */
    public CatalogVersion version(String type, String name, long version) {
        CatalogVersionRecord record = record(type, name, version);
        return record.metadata(status(record, activeVersion(findAny(type, name))));
    }

    /**
     * Returns the content of one version as a data store entry.
     *
     * @throws DataStoreResourceNotFoundException if the version does not exist
     * @throws InvalidPayloadException if the version was rejected and has no content
     */
    public DataStore content(String type, String name, long version) {
        CatalogVersionRecord record = record(type, name, version);
        if (record.getData() == null) {
            throw new InvalidPayloadException(
                    "Version %d of %s was rejected and has no content".formatted(version, name));
        }
        DataStore content = new DataStore(null, record.getDataStoreName(), record.getData());
        content.setLabels(new HashMap<>(record.getLabels()));
        return content;
    }

    /**
     * Finds an active or a removed entry.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @return the entry, or {@code null}
     */
    public DataStore findAny(String type, String name) {
        DataStore entry = find(type, name);
        return entry != null ? entry : find(removedType(type), name);
    }

    /**
     * Lists the removed entries of a type.
     *
     * @param type {@code catalog} or {@code template}
     * @return the removed entries
     */
    public List<DataStore> removed(String type) {
        return list(removedType(type));
    }

    /**
     * Summarizes the removed entries of a type.
     *
     * @param type {@code catalog} or {@code template}
     * @return the name, removal time, active version and data store name of each removed entry
     */
    public List<Map<String, Object>> removedSummaries(String type) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (DataStore entry : removed(type)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            try {
                summary.put(
                        "name", ServiceCatalogIndex.fromBase64(entry.getData()).getName());
            } catch (WanakuException e) {
                continue;
            }
            summary.put("removedAt", entry.getLabels().get(REMOVED_AT_LABEL));
            summary.put("version", activeVersion(entry));
            summary.put("dataStoreName", entry.getName());
            summaries.add(summary);
        }
        summaries.sort(Comparator.comparing(summary -> summary.get("name").toString()));
        return summaries;
    }

    /**
     * Marks an entry as removed. The entry and its versions are kept, so the entry can be restored.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @return true if an active entry was removed, false if no active entry has the name
     */
    public boolean remove(String type, String name) {
        try {
            lock.lock();
            DataStore entry = find(type, name);
            if (entry == null) {
                return false;
            }
            if (isImmutable(entry)) {
                throw new WanakuException("Published semantic catalog revisions are immutable");
            }
            Map<String, String> labels = new HashMap<>(entry.getLabels());
            labels.put(TYPE_LABEL, removedType(type));
            labels.put(REMOVED_AT_LABEL, now().toString());
            relabel(entry, labels);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Restores a removed entry with its active version. The restore does not create a version.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @return the restored entry
     * @throws DataStoreResourceNotFoundException if no removed entry has the name
     */
    public DataStore restoreRemoved(String type, String name) {
        try {
            lock.lock();
            DataStore entry = find(removedType(type), name);
            if (entry == null) {
                throw new DataStoreResourceNotFoundException("No removed %s named '%s'".formatted(type, name));
            }
            Map<String, String> labels = new HashMap<>(entry.getLabels());
            labels.put(TYPE_LABEL, type);
            labels.remove(REMOVED_AT_LABEL);
            return relabel(entry, labels);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Permanently deletes the removed entries of a type that were removed at or before the cutoff, together
     * with their versions. The audit trail keeps the purge events.
     *
     * @param type {@code catalog} or {@code template}
     * @param cutoff the latest removal time to purge (inclusive)
     * @return the number of purged entries
     */
    public int purgeRemovedBefore(String type, Instant cutoff) {
        int purged = 0;
        try {
            lock.lock();
            for (DataStore entry : removed(type)) {
                Instant removedAt = removedAt(entry);
                if (removedAt == null || removedAt.isAfter(cutoff)) {
                    continue;
                }
                String name = ServiceCatalogIndex.fromBase64(entry.getData()).getName();
                records(type, name).forEach(record -> versions().remove(record.key()));
                repository.deleteById(entry.getId());
                purged++;
                AuditEvent event = AuditEvent.administrative(
                        "service_%s.purge".formatted(type),
                        AuditEvent.DECISION_ALLOW,
                        "purged",
                        "The removed item and its versions were deleted.");
                event.setProtocol("scheduler");
                event.setTargetType("service_" + type);
                event.setTarget(name);
                auditStore.record(event);
                LOG.infof("Purged removed %s '%s'", type, name);
            }
        } finally {
            lock.unlock();
        }
        return purged;
    }

    /** Purges removed entries when {@code wanaku.catalog.purge-after} is set. Disabled by default. */
    @Scheduled(every = "${wanaku.catalog.purge-interval:1h}", delayed = "${wanaku.catalog.purge-interval:1h}")
    void purgeExpired() {
        if (purgeAfter.isEmpty()) {
            return;
        }
        Instant cutoff = Instant.now().minus(purgeAfter.get());
        for (String type : List.of(ServiceCatalogBean.LABEL_TYPE_VALUE, ServiceTemplateBean.LABEL_TYPE_VALUE)) {
            try {
                purgeRemovedBefore(type, cutoff);
            } catch (RuntimeException e) {
                LOG.errorf("Failed to purge removed %s entries: %s", type, e.getMessage());
            }
        }
    }

    /**
     * Returns the removal time of a removed entry.
     *
     * @param entry the entry
     * @return the removal time, or {@code null} if the entry is not removed
     */
    public static Instant removedAt(DataStore entry) {
        String value = entry.getLabels() == null ? null : entry.getLabels().get(REMOVED_AT_LABEL);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String removedType(String type) {
        return type + REMOVED_SUFFIX;
    }

    /** Replaces the labels of an entry. The content does not change, so no version is created. */
    private DataStore relabel(DataStore entry, Map<String, String> labels) {
        DataStore replacement = new DataStore(entry.getId(), entry.getName(), entry.getData());
        replacement.setLabels(labels);
        Long revision = entry instanceof DataStoreRecord current ? current.getRevision() : null;
        DataStore stored = repository.update(entry.getId(), replacement, revision);
        if (stored == null) {
            throw new DataStoreResourceNotFoundException("The entry %s does not exist".formatted(entry.getId()));
        }
        return stored;
    }

    /**
     * Returns the active version number of an entry.
     *
     * @param entry the catalog or template entry, can be null
     * @return the version, or 0 when the entry is null or has no version
     */
    public static long activeVersion(DataStore entry) {
        if (entry == null || entry.getLabels() == null || entry.getLabels().get(VERSION_LABEL) == null) {
            return 0;
        }
        try {
            return Long.parseLong(entry.getLabels().get(VERSION_LABEL));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @param knownEntry the entry to update, for a migration of an entry without the catalog name label
     */
    private DataStore deploy(
            String type,
            DataStore incoming,
            String origin,
            Long expectedVersion,
            Long restoredFrom,
            DataStore knownEntry) {
        byte[] archive = SafeZip.decodeArchive(incoming.getData());
        ServiceCatalogIndex index = ServiceCatalogIndex.fromZipBytes(archive);
        String name = index.getName();
        try {
            lock.lock();
            DataStore existing = knownEntry != null ? knownEntry : find(type, name);
            if (existing == null && find(removedType(type), name) != null) {
                throw new EntityAlreadyExistsException(
                        "The %s '%s' was removed. Restore it before you deploy it again".formatted(type, name));
            }
            if (incoming.getId() != null
                    && (existing == null || !incoming.getId().equals(existing.getId()))) {
                rejectImmutable(repository.findById(incoming.getId()));
            }
            long active = activeVersion(existing);
            if (expectedVersion != null && expectedVersion != active) {
                throw new RevisionConflictException(name, expectedVersion, active);
            }

            CatalogVersionRecord record = newRecord(type, name, incoming, origin, restoredFrom, archive);
            // Migration only adds the version label: the content of an immutable entry does not change
            if (isImmutable(existing) && !ORIGIN_LEGACY.equals(origin)) {
                reject(record, "immutable");
                throw new WanakuException("Published semantic catalog revisions cannot be overwritten");
            }
            versions().put(record.key(), record);

            Map<String, String> labels = new HashMap<>(incoming.getLabels() == null ? Map.of() : incoming.getLabels());
            labels.put(TYPE_LABEL, isRemoved(existing) ? removedType(type) : type);
            labels.put(CATALOG_NAME_LABEL, name);
            labels.put(VERSION_LABEL, Long.toString(record.getVersion()));
            DataStore stored;
            try {
                stored = activate(existing, incoming, labels);
            } catch (RuntimeException e) {
                reject(record, "activation_failed");
                throw e;
            }

            record.setActivatedAt(now());
            versions().put(record.key(), record);
            trim(type, name, record.getVersion());
            return stored;
        } finally {
            lock.unlock();
        }
    }

    /** Writes the new content to the existing entry (keeping its identifier) or creates the entry. */
    private DataStore activate(DataStore existing, DataStore incoming, Map<String, String> labels) {
        if (existing == null) {
            DataStore created = new DataStore(incoming.getId(), incoming.getName(), incoming.getData());
            created.setLabels(labels);
            return repository.persist(created);
        }
        DataStore replacement = new DataStore(existing.getId(), incoming.getName(), incoming.getData());
        replacement.setLabels(labels);
        Long revision = existing instanceof DataStoreRecord current ? current.getRevision() : null;
        DataStore stored = repository.update(existing.getId(), replacement, revision);
        if (stored == null) {
            throw new DataStoreResourceNotFoundException(
                    "The entry %s was removed during the deploy".formatted(existing.getId()));
        }
        return stored;
    }

    private CatalogVersionRecord newRecord(
            String type, String name, DataStore incoming, String origin, Long restoredFrom, byte[] archive) {
        CatalogVersionRecord record = new CatalogVersionRecord();
        record.setType(type);
        record.setName(name);
        record.setVersion(nextVersion(type, name));
        record.setCreatedAt(now());
        record.setChecksum(sha256(archive));
        record.setOrigin(origin);
        record.setRestoredFrom(restoredFrom);
        record.setDataStoreName(incoming.getName());
        record.setData(incoming.getData());
        if (incoming.getLabels() != null) {
            Map<String, String> labels = new HashMap<>(incoming.getLabels());
            labels.remove(TYPE_LABEL);
            labels.remove(VERSION_LABEL);
            record.setLabels(labels);
        }
        return record;
    }

    /** Stores a rejected version without its content, so invalid packages are not retained. */
    private void reject(CatalogVersionRecord record, String reason) {
        record.setRejected(true);
        record.setFailureReason(reason);
        record.setData(null);
        versions().put(record.key(), record);
        trim(record.getType(), record.getName(), 0);
    }

    /** The version counter survives trimming and removal, so version numbers are never reused. */
    private long nextVersion(String type, String name) {
        String key = type + ":" + name;
        Long last = counters().get(key);
        long next = (last == null ? 0 : last) + 1;
        counters().put(key, next);
        return next;
    }

    /** Keeps the newest {@code maxVersions} versions. Never removes the active version. */
    private void trim(String type, String name, long active) {
        long current = active > 0 ? active : activeVersion(findAny(type, name));
        List<CatalogVersionRecord> older = new ArrayList<>(records(type, name));
        older.sort(Comparator.comparingLong(CatalogVersion::getVersion).reversed());
        int keep = Math.max(1, maxVersions);
        int kept = current > 0 ? 1 : 0;
        for (CatalogVersionRecord record : older) {
            if (record.getVersion() == current) {
                continue;
            }
            if (kept < keep) {
                kept++;
            } else {
                versions().remove(record.key());
            }
        }
    }

    private CatalogVersionRecord record(String type, String name, long version) {
        CatalogVersionRecord record = versions().get(CatalogVersionRecord.key(type, name, version));
        if (record == null) {
            throw new DataStoreResourceNotFoundException(
                    "Version %d of %s '%s' not found".formatted(version, type, name));
        }
        return record;
    }

    private List<CatalogVersionRecord> records(String type, String name) {
        Query<CatalogVersionRecord> query =
                versions().query("from %s v where v.type = :type and v.name = :name".formatted(VERSION_TYPE));
        query.setParameter("type", type);
        query.setParameter("name", name);
        return query.execute().list();
    }

    private static String status(CatalogVersionRecord record, long active) {
        if (record.getVersion() == active) {
            return CatalogVersion.STATUS_ACTIVE;
        }
        // A version without an activation time was never active, for example after an interrupted deploy
        if (record.isRejected() || record.getActivatedAt() == null) {
            return CatalogVersion.STATUS_REJECTED;
        }
        return CatalogVersion.STATUS_SUPERSEDED;
    }

    private static boolean isRemoved(DataStore entry) {
        return entry != null && entry.getLabels() != null && entry.getLabels().containsKey(REMOVED_AT_LABEL);
    }

    private static boolean isImmutable(DataStore entry) {
        return entry != null
                && entry.getLabels() != null
                && "true".equals(entry.getLabels().get("semantic.immutable"));
    }

    private static void rejectImmutable(DataStore entry) {
        if (isImmutable(entry)) {
            throw new WanakuException("Published semantic catalog revisions cannot be overwritten");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new WanakuException(e);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private Cache<String, CatalogVersionRecord> versions() {
        return cacheManager.getCache(VERSIONS_CACHE);
    }

    private Cache<String, Long> counters() {
        return cacheManager.getCache(COUNTERS_CACHE);
    }
}
