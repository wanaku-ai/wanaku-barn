package ai.wanaku.backend.core.persistence.migration;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.List;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.logging.Logger;
import io.quarkus.runtime.StartupEvent;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogLifecycle;

/**
 * Keeps a version number for the persisted data and runs the migration steps at startup.
 * <p>
 * Each step has a version. At startup, the steps with a version greater than the stored version run in order.
 * The stored version is updated after each successful step, so an interrupted migration continues with the
 * failed step at the next startup. Steps must be idempotent. When the stored version is newer than
 * {@link #CURRENT_VERSION}, the startup fails without changing the data.
 * </p>
 */
@ApplicationScoped
public class SchemaMigrations {
    private static final Logger LOG = Logger.getLogger(SchemaMigrations.class);

    /** The schema version of the data that this Barn version writes. */
    public static final long CURRENT_VERSION = 1;

    static final String METADATA_CACHE = "barn-metadata";
    static final String VERSION_KEY = "schema-version";

    /** One migration step. */
    public record Step(long version, String description, Runnable action) {}

    @Inject
    EmbeddedCacheManager cacheManager;

    @Inject
    Configuration configuration;

    @Inject
    CatalogLifecycle lifecycle;

    private volatile boolean complete;

    /** Runs before the other startup observers, so they see migrated data. */
    void onStartup(@Observes @Priority(0) StartupEvent event) {
        migrate(storedVersion());
    }

    /**
     * Runs the steps after the given version, in order.
     *
     * @param fromVersion the version of the data
     * @throws IllegalStateException if the data is newer than this Barn version
     */
    public void migrate(long fromVersion) {
        run(fromVersion, steps());
    }

    /** The migration steps, in version order. */
    List<Step> steps() {
        return List.of(new Step(
                1,
                "Create version history and catalog name labels for catalogs and templates",
                lifecycle::migrateLegacyEntries));
    }

    void run(long fromVersion, List<Step> steps) {
        long newest =
                steps.isEmpty() ? CURRENT_VERSION : steps.get(steps.size() - 1).version();
        if (fromVersion > newest) {
            throw new IllegalStateException(("The persisted data has schema version %d, but this Barn version supports"
                            + " schema version %d or earlier. Use a newer Barn version or restore a backup.")
                    .formatted(fromVersion, newest));
        }
        for (Step step : steps) {
            if (step.version() <= fromVersion) {
                continue;
            }
            LOG.infof("Migrating persisted data to schema version %d: %s", step.version(), step.description());
            step.action().run();
            metadata().put(VERSION_KEY, step.version());
        }
        complete = true;
    }

    /**
     * Returns the stored schema version.
     *
     * @return the version, or 0 when no version is stored
     */
    public long storedVersion() {
        Long version = metadata().get(VERSION_KEY);
        return version == null ? 0 : version;
    }

    /** Tells whether the startup migration finished. */
    public boolean isComplete() {
        return complete;
    }

    private Cache<String, Long> metadata() {
        if (cacheManager.getCacheConfiguration(METADATA_CACHE) == null) {
            cacheManager.defineConfiguration(METADATA_CACHE, configuration);
        }
        return cacheManager.getCache(METADATA_CACHE);
    }
}
