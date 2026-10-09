package ai.wanaku.backend.core.persistence.infinispan;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.core.persistence.api.PromptReferenceRepository;
import ai.wanaku.capabilities.sdk.api.types.PromptReference;

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
    PromptReferenceRepository promptRepository;

    InfinispanPromptReferenceRepository repository;

    @BeforeEach
    void clean() {
        repository = (InfinispanPromptReferenceRepository) promptRepository;
        repository.removeAll();
    }

    @Test
    void updateDoesNotCreateMissingEntities() {
        PromptReference record = new PromptReference();
        record.setId("missing");

        assertFalse(repository.update("missing", record));
        assertFalse(repository.update("missing", (PromptReference r) -> r.setName("updated")));
        assertNull(repository.findById("missing"));
    }

    @Test
    void updateReplacesExistingEntities() {
        repository.upsert("present", r -> r.setName("initial"));

        assertTrue(repository.update("present", (PromptReference r) -> r.setName("updated")));
        assertEquals("updated", repository.findById("present").getName());
    }

    @Test
    void upsertCreatesMissingEntities() {
        repository.upsert("created", r -> r.setName("initial"));

        PromptReference created = repository.findById("created");
        assertNotNull(created);
        assertEquals("created", created.getId());
        assertEquals("initial", created.getName());
    }
}
