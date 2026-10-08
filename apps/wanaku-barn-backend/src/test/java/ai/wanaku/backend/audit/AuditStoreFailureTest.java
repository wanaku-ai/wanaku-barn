package ai.wanaku.backend.audit;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that storage failures are counted, reported and never thrown to the caller.
 */
class AuditStoreFailureTest {

    private Cache<Long, AuditEvent> events;
    private SimpleMeterRegistry registry;
    private AuditStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        EmbeddedCacheManager manager = mock(EmbeddedCacheManager.class);
        events = mock(Cache.class);
        Cache<String, Long> state = mock(Cache.class);
        doReturn(events).when(manager).getCache(AuditStore.EVENTS_CACHE);
        doReturn(state).when(manager).getCache(AuditStore.STATE_CACHE);
        when(manager.getCacheConfiguration(any())).thenReturn(mock(Configuration.class));

        registry = new SimpleMeterRegistry();
        store = new AuditStore();
        store.cacheManager = manager;
        store.configuration = mock(Configuration.class);
        store.meterRegistry = registry;
        store.maxRecords = 10;
        store.streamId = "barn";
        store.init();
    }

    private static AuditEvent event() {
        return AuditEvent.administrative("data_store.create", AuditEvent.DECISION_ALLOW, "completed", "done");
    }

    @Test
    void failuresAreCountedAndReportedWithoutThrowing() {
        doThrow(new IllegalStateException("disk full")).when(events).put(anyLong(), any());

        store.record(event());
        store.record(event());

        AuditHealth health = store.health();
        assertThat(health.isHealthy()).isFalse();
        assertThat(health.getDroppedEvents()).isEqualTo(2);
        assertThat(health.getLastError()).isEqualTo("audit storage unavailable").doesNotContain("disk full");
        assertThat(registry.get("wanaku.audit.storage.failures").counter().count())
                .isEqualTo(2);
    }

    @Test
    void theNextStoredEventReportsTheGap() {
        doThrow(new IllegalStateException("disk full")).when(events).put(anyLong(), any());
        store.record(event());

        doReturn(null).when(events).put(anyLong(), any());
        AuditEvent next = event();
        store.record(next);

        assertThat(next.isCoverageComplete()).isFalse();
        assertThat(next.getDroppedEvents()).isEqualTo(1);
        assertThat(store.health().isHealthy()).isTrue();
        assertThat(store.health().getDroppedEvents()).isEqualTo(1);
    }
}
