package ai.wanaku.backend.core.persistence.infinispan.discovery;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.protostream.SerializationContextInitializer;
import ai.wanaku.backend.audit.AuditEvent;
import ai.wanaku.backend.core.persistence.infinispan.InfinispanDataStoreRepository;
import ai.wanaku.backend.core.persistence.infinispan.codeexecution.InfinispanCodeTaskRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.discovery.ActivityRecord;
import ai.wanaku.capabilities.sdk.api.types.discovery.HealthStatus;
import ai.wanaku.capabilities.sdk.api.types.discovery.ServiceState;
import ai.wanaku.capabilities.sdk.api.types.execution.CodeExecutionRequest;
import ai.wanaku.capabilities.sdk.api.types.execution.CodeExecutionStatus;
import ai.wanaku.capabilities.sdk.api.types.execution.CodeExecutionTask;
import ai.wanaku.core.services.api.DataStoreRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes entities with the file store enabled, stops the cache manager, opens a new one on the same directory
 * and verifies the content. Serialization schemas are registered only through the ServiceLoader file, as in
 * a deployment, so a missing registration fails this test.
 */
class FileStoreRestartTest {

    @TempDir
    Path directory;

    private DefaultCacheManager open() {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder().nonClusteredDefault();
        ServiceLoader.load(SerializationContextInitializer.class)
                .forEach(global.serialization()::addContextInitializer);
        return new DefaultCacheManager(global.build());
    }

    private Configuration fileStore() {
        ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.clustering().cacheMode(CacheMode.LOCAL).memory().storage(StorageType.HEAP);
        builder.persistence()
                .passivation(false)
                .addSoftIndexFileStore()
                .dataLocation(directory.toString())
                .indexLocation(directory.toString())
                .shared(false)
                .preload(true)
                .purgeOnStartup(false);
        return builder.build();
    }

    @Test
    void entitiesSurviveARestart() throws Exception {
        Configuration configuration = fileStore();
        String dataStoreId;
        Instant createdAt;

        try (DefaultCacheManager first = open()) {
            InfinispanDataStoreRepository dataStores = new InfinispanDataStoreRepository(first, configuration);
            DataStore entry = new DataStore(null, "restart.service.zip", "content");
            entry.setLabels(Map.of("wanaku.type", "catalog", "wanaku.catalog-name", "restart"));
            DataStoreRecord stored = (DataStoreRecord) dataStores.persist(entry);
            DataStore changed = new DataStore(stored.getId(), entry.getName(), "content v2");
            changed.setLabels(entry.getLabels());
            dataStores.update(stored.getId(), changed, 1L);
            dataStoreId = stored.getId();
            createdAt = stored.getCreatedAt();

            InfinispanServiceRecordRepository activity = new InfinispanServiceRecordRepository(first, configuration);
            activity.upsert("service-1", record -> {
                record.setHealthStatus(HealthStatus.HEALTHY);
                record.getStates().add(ServiceState.newHealthy());
            });

            InfinispanCodeTaskRepository tasks = new InfinispanCodeTaskRepository(first, configuration);
            CodeExecutionTask task =
                    new CodeExecutionTask("task-1", new CodeExecutionRequest("print(1)"), "jvm", "java");
            task.setStatus(CodeExecutionStatus.RUNNING);
            tasks.store(task);

            first.defineConfiguration("audit-event", configuration);
            Cache<Long, AuditEvent> audit = first.getCache("audit-event");
            AuditEvent event =
                    AuditEvent.administrative("data_store.create", AuditEvent.DECISION_ALLOW, "completed", "x");
            event.setSequence(1);
            event.getAttributes().put("revision", "2");
            audit.put(1L, event);
        }

        try (DefaultCacheManager second = open()) {
            InfinispanDataStoreRepository dataStores = new InfinispanDataStoreRepository(second, configuration);
            DataStoreRecord reloaded = (DataStoreRecord) dataStores.findById(dataStoreId);
            assertThat(reloaded.getData()).isEqualTo("content v2");
            assertThat(reloaded.getRevision()).isEqualTo(2);
            assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
            assertThat(reloaded.getLabels()).containsEntry("wanaku.catalog-name", "restart");
            // Queries find the preloaded entry
            assertThat(dataStores.findByTypeAndCatalogName("catalog", "restart"))
                    .extracting(DataStore::getId)
                    .containsExactly(dataStoreId);

            ActivityRecord activity =
                    new InfinispanServiceRecordRepository(second, configuration).findById("service-1");
            assertThat(activity.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(activity.getStates()).hasSize(1);

            CodeExecutionTask task = new InfinispanCodeTaskRepository(second, configuration)
                    .findById("task-1")
                    .orElseThrow();
            assertThat(task.getRequest().getCode()).isEqualTo("print(1)");
            assertThat(task.getStatus()).isEqualTo(CodeExecutionStatus.RUNNING);

            second.defineConfiguration("audit-event", configuration);
            Cache<Long, AuditEvent> audit = second.getCache("audit-event");
            List<AuditEvent> events = audit.<AuditEvent>query(
                            "from ai.wanaku.backend.audit.AuditEvent e where e.operation = :op")
                    .setParameter("op", "data_store.create")
                    .execute()
                    .list();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAttributes()).containsEntry("revision", "2");
        }
    }
}
