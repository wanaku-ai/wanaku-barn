package ai.wanaku.backend.api.v1.management.backup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogVersionRecord;
import ai.wanaku.backend.audit.AuditEvent;
import ai.wanaku.core.services.api.DataStoreRecord;

/**
 * A logical export of the Barn data. The archive does not depend on the Infinispan file layout.
 *
 * @param formatVersion the archive format version, {@value BackupBean#FORMAT_VERSION}
 * @param schemaVersion the schema version of the exported data
 * @param exportedAt the export time
 * @param dataStores all data store entries with their metadata, including catalogs, templates, Kamelets and
 *        semantic router records
 * @param catalogVersions all catalog and template versions with their content
 * @param versionCounters the last version number of each catalog and template, keyed by {@code <type>:<name>}
 * @param auditEvents the retained audit events, or {@code null} when the export excludes them
 * @param auditSequence the sequence number of the newest audit event, or {@code null}
 */
public record BarnArchive(
        int formatVersion,
        long schemaVersion,
        Instant exportedAt,
        List<DataStoreRecord> dataStores,
        List<CatalogVersionRecord> catalogVersions,
        Map<String, Long> versionCounters,
        List<AuditEvent> auditEvents,
        Long auditSequence) {}
