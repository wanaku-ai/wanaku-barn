package ai.wanaku.backend.audit;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(AuditRetentionTest.SmallRetention.class)
class AuditRetentionTest {

    public static class SmallRetention implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("wanaku.audit.max-records", "3");
        }
    }

    @Inject
    AuditStore store;

    @Test
    void keepsOnlyTheNewestEvents() {
        store.clear();
        for (int i = 0; i < 5; i++) {
            store.record(AuditEvent.administrative("retention." + i, AuditEvent.DECISION_ALLOW, "completed", "done"));
        }

        AuditPage page = store.query(new AuditFilterCriteria(null, null, null, null, null, null, null, null, 0, 10));
        assertThat(page.getTotal()).isEqualTo(3);
        List<Long> sequences =
                page.getEvents().stream().map(AuditEvent::getSequence).toList();
        assertThat(sequences).containsExactly(5L, 4L, 3L);
        assertThat(store.health().getRetainedEvents()).isEqualTo(3);
        assertThat(store.health().getCapacity()).isEqualTo(3);

        store.record(AuditEvent.administrative("retention.6", AuditEvent.DECISION_ALLOW, "completed", "done"));
        assertThat(store.query(new AuditFilterCriteria(null, null, null, null, null, null, null, null, 0, 1))
                        .getEvents()
                        .get(0)
                        .getSequence())
                .isEqualTo(6L);
    }
}
