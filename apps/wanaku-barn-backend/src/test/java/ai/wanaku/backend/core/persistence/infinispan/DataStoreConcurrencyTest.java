package ai.wanaku.backend.core.persistence.infinispan;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.RevisionConflictException;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DataStoreRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class DataStoreConcurrencyTest {

    @Inject
    DataStoreRepository repository;

    @Inject
    DataStoreRepository otherInjection;

    @BeforeEach
    void clean() {
        ((AbstractInfinispanRepository<?, ?>) repository).deleteALl();
    }

    @Test
    void repositoryIsSharedBetweenInjectionPoints() {
        assertThat(otherInjection).isSameAs(repository);
    }

    @Test
    void staleUpdateConflictsWithoutChangingTheEntry() {
        String id = repository.create(new DataStore(null, "shared", "v1")).getId();
        DataStoreRecord readerA = (DataStoreRecord) repository.findById(id);
        DataStoreRecord readerB = (DataStoreRecord) repository.findById(id);

        readerA.setData("from A");
        repository.update(id, readerA, readerA.getRevision());

        readerB.setData("from B");
        assertThatThrownBy(() -> repository.update(id, readerB, readerB.getRevision()))
                .isInstanceOfSatisfying(RevisionConflictException.class, e -> assertThat(e.getCurrentRevision())
                        .isEqualTo(2));

        DataStoreRecord current = (DataStoreRecord) repository.findById(id);
        assertThat(current.getData()).isEqualTo("from A");
        assertThat(current.getRevision()).isEqualTo(2);
    }

    @Test
    void updateWithoutExpectedRevisionStillSucceeds() {
        String id =
                repository.create(new DataStore(null, "legacy-client", "v1")).getId();

        DataStore stored = repository.update(id, new DataStore(id, "legacy-client", "v2"), null);

        assertThat(((DataStoreRecord) stored).getRevision()).isEqualTo(2);
    }

    @Test
    void updateOfMissingEntryReturnsNullBeforeCheckingTheRevision() {
        assertThat(repository.update("missing", new DataStore("missing", "x", "y"), 5L))
                .isNull();
    }

    @Test
    void staleDeleteConflictsAndKeepsTheEntry() {
        String id = repository.create(new DataStore(null, "delete-me", "v1")).getId();
        repository.update(id, new DataStore(id, "delete-me", "v2"), 1L);

        assertThatThrownBy(() -> repository.deleteById(id, 1L)).isInstanceOf(RevisionConflictException.class);
        assertThat(repository.findById(id)).isNotNull();

        assertThat(repository.deleteById(id, 2L)).isTrue();
        assertThat(repository.findById(id)).isNull();
    }

    @Test
    void createRejectsAnExistingIdentifier() {
        repository.create(new DataStore("fixed", "first", "v1"));

        assertThatThrownBy(() -> repository.create(new DataStore("fixed", "second", "v1")))
                .isInstanceOf(EntityAlreadyExistsException.class);
        assertThat(repository.findById("fixed").getName()).isEqualTo("first");
    }

    @Test
    void renameToAnExistingNameConflicts() {
        repository.create(new DataStore("a", "alpha", "v1"));
        repository.create(new DataStore("b", "beta", "v1"));

        assertThatThrownBy(() -> repository.update("b", new DataStore("b", "alpha", "v2"), null))
                .isInstanceOf(EntityAlreadyExistsException.class);
        assertThat(repository.findById("b").getName()).isEqualTo("beta");

        assertThat(repository.update("b", new DataStore("b", "beta", "v2"), null))
                .isNotNull();
    }

    @Test
    void concurrentCreatesWithTheSameNameStoreOneEntry() throws Exception {
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String data = "writer-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        repository.create(new DataStore(null, "contended", data));
                        return true;
                    } catch (EntityAlreadyExistsException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            int created = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(repository.findByName("contended")).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentUpdatesFromTheSameRevisionAllowOneWinner() throws Exception {
        String id = repository.create(new DataStore(null, "race", "v1")).getId();
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String data = "writer-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        repository.update(id, new DataStore(id, "race", data), 1L);
                        return true;
                    } catch (RevisionConflictException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(((DataStoreRecord) repository.findById(id)).getRevision())
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }
}
