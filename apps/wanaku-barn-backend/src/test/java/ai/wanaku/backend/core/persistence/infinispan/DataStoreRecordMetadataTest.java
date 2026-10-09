package ai.wanaku.backend.core.persistence.infinispan;

import jakarta.inject.Inject;

import java.time.Instant;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DataStoreRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataStoreRecordMetadataTest {

    @Inject
    DataStoreRepository repository;

    @BeforeEach
    void clean() {
        ((AbstractInfinispanRepository<?, ?>) repository).deleteALl();
    }

    private InfinispanDataStoreRepository infinispan() {
        return (InfinispanDataStoreRepository) repository;
    }

    @Test
    void createAssignsInitialMetadata() {
        DataStoreRecord stored = (DataStoreRecord) repository.persist(new DataStore(null, "meta", "content"));

        assertEquals(1, stored.getRevision());
        assertNotNull(stored.getCreatedAt());
        assertEquals(stored.getCreatedAt(), stored.getUpdatedAt());
        assertNull(stored.getCreatedBy(), "No identity source exists, so the actor stays empty");
    }

    @Test
    void writesIncrementRevisionAndKeepCreationMetadata() {
        DataStoreRecord created = (DataStoreRecord) repository.persist(new DataStore(null, "meta", "v1"));

        assertTrue(repository.update(created.getId(), new DataStore(created.getId(), "meta", "v2")));
        DataStoreRecord replaced = (DataStoreRecord) repository.persist(new DataStore(created.getId(), "meta", "v3"));

        DataStoreRecord current = (DataStoreRecord) repository.findById(created.getId());
        assertEquals(3, current.getRevision());
        assertEquals(replaced, current);
        assertEquals(created.getCreatedAt(), current.getCreatedAt());
        assertFalse(current.getUpdatedAt().isBefore(current.getCreatedAt()));
        assertEquals("v3", current.getData());
    }

    @Test
    void clientSuppliedMetadataIsIgnored() {
        DataStoreRecord forged = new DataStoreRecord();
        forged.setName("meta");
        forged.setData("content");
        forged.setRevision(42);
        forged.setCreatedAt(Instant.EPOCH);
        forged.setCreatedBy("someone");

        DataStoreRecord stored = (DataStoreRecord) repository.persist(forged);

        assertEquals(1, stored.getRevision());
        assertNull(stored.getCreatedBy());
        assertFalse(Instant.EPOCH.equals(stored.getCreatedAt()));
    }

    @Test
    void updateOfMissingEntryDoesNotCreateIt() {
        assertFalse(repository.update("missing", new DataStore("missing", "meta", "content")));
        assertFalse(infinispan().update("missing", data -> data.setData("changed")));

        assertNull(repository.findById("missing"));
        assertEquals(0, repository.size());
    }

    @Test
    void updateWithConsumerChangesCopyAndIncrementsRevision() {
        DataStore created = repository.persist(new DataStore(null, "meta", "v1"));

        assertTrue(infinispan().update(created.getId(), data -> data.setData("v2")));

        DataStoreRecord current = (DataStoreRecord) repository.findById(created.getId());
        assertEquals("v2", current.getData());
        assertEquals(2, current.getRevision());
    }

    @Test
    void readsReturnDetachedCopies() {
        DataStore created = repository.persist(new DataStore(null, "meta", "original"));

        repository.findById(created.getId()).setData("changed outside the repository");
        repository.listAll().get(0).getLabels().put("leaked", "true");
        repository.findByName("meta").get(0).setName("renamed");

        DataStoreRecord current = (DataStoreRecord) repository.findById(created.getId());
        assertEquals("original", current.getData());
        assertEquals("meta", current.getName());
        assertFalse(current.getLabels().containsKey("leaked"));
        assertEquals(1, current.getRevision());
    }

    @Test
    void persistIfAbsentCreatesOnce() {
        DataStore first = new DataStore("fixed-id", "meta", "first");

        assertNull(repository.persistIfAbsent(first));
        DataStoreRecord existing =
                (DataStoreRecord) repository.persistIfAbsent(new DataStore("fixed-id", "meta", "second"));

        assertEquals("first", existing.getData());
        assertEquals(1, existing.getRevision());
    }
}
