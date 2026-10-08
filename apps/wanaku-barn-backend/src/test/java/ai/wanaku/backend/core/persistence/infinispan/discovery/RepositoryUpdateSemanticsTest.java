package ai.wanaku.backend.core.persistence.infinispan.discovery;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.capabilities.sdk.api.types.discovery.ActivityRecord;
import ai.wanaku.capabilities.sdk.api.types.discovery.HealthStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the update and upsert contracts of the generic Infinispan repository.
 */
@QuarkusTest
class RepositoryUpdateSemanticsTest {

    @Inject
    InfinispanServiceRecordRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void updateDoesNotCreateMissingEntities() {
        ActivityRecord record = new ActivityRecord();
        record.setId("missing");

        assertFalse(repository.update("missing", record));
        assertFalse(repository.update("missing", (ActivityRecord r) -> r.setHealthStatus(HealthStatus.HEALTHY)));
        assertNull(repository.findById("missing"));
    }

    @Test
    void updateReplacesExistingEntities() {
        repository.upsert("present", r -> r.setHealthStatus(HealthStatus.PENDING));

        assertTrue(repository.update("present", (ActivityRecord r) -> r.setHealthStatus(HealthStatus.HEALTHY)));
        assertEquals(HealthStatus.HEALTHY, repository.findById("present").getHealthStatus());
    }

    @Test
    void upsertCreatesMissingEntities() {
        repository.upsert("created", r -> r.setHealthStatus(HealthStatus.PENDING));

        ActivityRecord created = repository.findById("created");
        assertNotNull(created);
        assertEquals("created", created.getId());
        assertEquals(HealthStatus.PENDING, created.getHealthStatus());
    }
}
