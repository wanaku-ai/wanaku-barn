package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.commons.api.query.Query;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.logging.Logger;
import io.quarkus.runtime.StartupEvent;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.RevisionConflictException;
import ai.wanaku.capabilities.sdk.api.exceptions.DataStoreResourceNotFoundException;
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

    /** The label that holds the active version number of a catalog or template entry. */
    public static final String VERSION_LABEL = "wanaku.version";

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

    @ConfigProperty(name = "wanaku.catalog.max-versions", defaultValue = "50")
    int maxVersions;

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
            for (DataStore entry : list(type)) {
                if (entry.getLabels().containsKey(VERSION_LABEL)) {
                    continue;
                }
                try {
                    ServiceCatalogIndex index = ServiceCatalogIndex.fromBase64(entry.getData());
                    deploy(type, entry, ORIGIN_LEGACY, null, null);
                    LOG.infof("Created version history for existing %s '%s'", type, index.getName());
                } catch (RuntimeException e) {
                    LOG.warnf("Cannot create version history for %s entry %s: %s", type, entry.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Lists the entries of a type.
     *
     * @param type {@code catalog} or {@code template}
     * @return the entries
     */
    public List<DataStore> list(String type) {
        return repository.findAllFilterByLabelExpression(TYPE_LABEL + "=" + type);
    }

    /**
     * Finds the entry with the given name in {@code index.properties}.
     *
     * @param type {@code catalog} or {@code template}
     * @param name the catalog or template name
     * @return the entry, or {@code null}
     */
    public DataStore find(String type, String name) {
        for (DataStore entry : list(type)) {
            try {
                if (name.equals(ServiceCatalogIndex.fromBase64(entry.getData()).getName())) {
                    return entry;
                }
            } catch (WanakuException e) {
                LOG.debugf("Failed to parse the index of %s entry '%s': %s", type, entry.getName(), e.getMessage());
            }
        }
        return null;
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
        return deploy(type, incoming, origin, expectedVersion, null);
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
        return deploy(type, content, ORIGIN_RESTORE, expectedVersion, version);
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
        long active = activeVersion(find(type, name));
        List<CatalogVersion> versions = records(type, name).stream()
                .sorted(Comparator.comparingLong(CatalogVersion::getVersion).reversed())
                .map(record -> record.metadata(status(record, active)))
                .toList();
        if (versions.isEmpty()) {
            throw new DataStoreResourceNotFoundException("No versions found for %s '%s'".formatted(type, name));
        }
        return versions;
    }

    /**
     * Returns the metadata of one version.
     *
     * @throws DataStoreResourceNotFoundException if the version does not exist
     */
    public CatalogVersion version(String type, String name, long version) {
        CatalogVersionRecord record = record(type, name, version);
        return record.metadata(status(record, activeVersion(find(type, name))));
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

    private DataStore deploy(String type, DataStore incoming, String origin, Long expectedVersion, Long restoredFrom) {
        byte[] archive = SafeZip.decodeArchive(incoming.getData());
        ServiceCatalogIndex index = ServiceCatalogIndex.fromZipBytes(archive);
        String name = index.getName();
        try {
            lock.lock();
            DataStore existing = find(type, name);
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
            labels.put(TYPE_LABEL, type);
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
        long current = active > 0 ? active : activeVersion(find(type, name));
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
