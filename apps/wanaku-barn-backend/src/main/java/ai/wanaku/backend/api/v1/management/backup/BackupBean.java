package ai.wanaku.backend.api.v1.management.backup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogLifecycle;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogVersionRecord;
import ai.wanaku.backend.audit.AuditEvent;
import ai.wanaku.backend.audit.AuditStore;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.migration.SchemaMigrations;
import ai.wanaku.capabilities.sdk.api.exceptions.DataStoreResourceNotFoundException;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DataStoreRecord;

/**
 * Exports and imports the Barn data.
 * <p>
 * An import replaces the data stores, the catalog and template versions and the version counters. When the
 * archive contains audit events, it also replaces the audit trail. The import validates the complete archive
 * before it changes anything, and restores the previous data if it fails.
 * </p>
 */
@ApplicationScoped
public class BackupBean {
    private static final Logger LOG = Logger.getLogger(BackupBean.class);

    /** The archive format that this Barn version reads and writes. */
    public static final int FORMAT_VERSION = 1;

    @Inject
    DataStoreRepository repository;

    @Inject
    CatalogLifecycle lifecycle;

    @Inject
    AuditStore auditStore;

    @Inject
    SchemaMigrations migrations;

    /**
     * Exports the Barn data.
     *
     * @param includeAudit true to include the audit events
     * @return the archive
     */
    public BarnArchive export(boolean includeAudit) {
        return lifecycle.exclusive(() -> new BarnArchive(
                FORMAT_VERSION,
                migrations.storedVersion(),
                Instant.now(),
                repository.listAll().stream().map(DataStoreRecord.class::cast).toList(),
                lifecycle.allVersions(),
                lifecycle.versionCounters(),
                includeAudit ? auditStore.allEvents() : null,
                includeAudit ? auditStore.lastSequence() : null));
    }

    /**
     * Imports an archive.
     *
     * @param archive the archive
     * @param replace true to replace existing data; otherwise the import requires an empty Barn
     * @return the number of imported items by kind
     * @throws IllegalArgumentException if the archive is not valid
     * @throws EntityAlreadyExistsException if Barn has data and {@code replace} is false
     */
    public Map<String, Integer> importArchive(BarnArchive archive, boolean replace) {
        validate(archive);
        Map<String, Integer> summary = lifecycle.exclusive(() -> {
            if (!replace && !isEffectivelyEmpty()) {
                throw new EntityAlreadyExistsException("Barn already has data. Import with replace=true to replace it");
            }
            BarnArchive previous = export(archive.auditEvents() != null);
            try {
                apply(archive);
            } catch (RuntimeException e) {
                LOG.errorf("Import failed, restoring the previous data: %s", e.getMessage());
                apply(previous);
                throw e;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("dataStores", archive.dataStores().size());
            counts.put("catalogVersions", archive.catalogVersions().size());
            counts.put(
                    "auditEvents",
                    archive.auditEvents() == null ? 0 : archive.auditEvents().size());
            return counts;
        });
        // Archives from an older Barn version need the later migration steps
        migrations.migrate(archive.schemaVersion());
        LOG.infof("Imported %s", summary);
        return summary;
    }

    private void apply(BarnArchive archive) {
        repository.replaceAll(archive.dataStores());
        lifecycle.replaceVersions(archive.catalogVersions(), archive.versionCounters());
        if (archive.auditEvents() != null) {
            long sequence = archive.auditSequence() == null ? 0 : archive.auditSequence();
            auditStore.replaceEvents(archive.auditEvents(), sequence);
        }
    }

    /**
     * Barn is effectively empty when it contains only the built-in templates that it deployed at startup and
     * that nobody changed.
     */
    boolean isEffectivelyEmpty() {
        for (DataStore entry : repository.listAll()) {
            String type = entry.getLabels() == null ? null : entry.getLabels().get(CatalogLifecycle.TYPE_LABEL);
            if (!"template".equals(type)) {
                return false;
            }
            String name = entry.getLabels().get(CatalogLifecycle.CATALOG_NAME_LABEL);
            if (name == null || !seededOnly(name)) {
                return false;
            }
        }
        return true;
    }

    private boolean seededOnly(String template) {
        try {
            return lifecycle.versions("template", template).stream()
                    .allMatch(version -> CatalogLifecycle.ORIGIN_STARTUP.equals(version.getOrigin()));
        } catch (DataStoreResourceNotFoundException e) {
            return false;
        }
    }

    /** Checks the complete archive before any change. */
    static void validate(BarnArchive archive) {
        if (archive == null) {
            throw new IllegalArgumentException("The archive is empty");
        }
        if (archive.formatVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported archive format %d. This Barn version reads format %d"
                    .formatted(archive.formatVersion(), FORMAT_VERSION));
        }
        if (archive.schemaVersion() > SchemaMigrations.CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "The archive has schema version %d, but this Barn version supports schema version %d or earlier"
                            .formatted(archive.schemaVersion(), SchemaMigrations.CURRENT_VERSION));
        }
        if (archive.dataStores() == null || archive.catalogVersions() == null || archive.versionCounters() == null) {
            throw new IllegalArgumentException(
                    "The archive must contain dataStores, catalogVersions and versionCounters");
        }
        Set<String> ids = new HashSet<>();
        for (DataStoreRecord entry : archive.dataStores()) {
            if (entry == null || entry.getId() == null || !ids.add(entry.getId())) {
                throw new IllegalArgumentException("Each data store entry must have a unique ID");
            }
        }
        Set<String> keys = new HashSet<>();
        for (CatalogVersionRecord version : archive.catalogVersions()) {
            if (version == null
                    || !List.of("catalog", "template").contains(version.getType())
                    || version.getName() == null
                    || version.getVersion() < 1
                    || !keys.add(version.key())) {
                throw new IllegalArgumentException(
                        "Each catalog version must have a type (catalog or template), a name and a unique version");
            }
        }
        for (Map.Entry<String, Long> counter : archive.versionCounters().entrySet()) {
            if (counter.getValue() == null || counter.getValue() < 0) {
                throw new IllegalArgumentException("Version counters must be 0 or greater");
            }
        }
        if (archive.auditEvents() != null) {
            Set<Long> sequences = new HashSet<>();
            for (AuditEvent event : archive.auditEvents()) {
                if (event == null || event.getSequence() < 1 || !sequences.add(event.getSequence())) {
                    throw new IllegalArgumentException("Each audit event must have a unique sequence number");
                }
            }
        }
    }
}
