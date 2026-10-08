package ai.wanaku.backend.core.persistence.infinispan;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.Page;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DataStoreQueryTest {

    @Inject
    DataStoreRepository repository;

    @BeforeEach
    void clean() {
        ((AbstractInfinispanRepository<?, ?>) repository).deleteALl();
    }

    private void store(String name, String type, String catalogName) {
        DataStore entry = new DataStore(null, name, "content");
        if (type != null) {
            entry.setLabels(
                    catalogName == null
                            ? Map.of("wanaku.type", type)
                            : Map.of("wanaku.type", type, "wanaku.catalog-name", catalogName));
        }
        repository.persist(entry);
    }

    @Test
    void lookupsFilterByDerivedProperties() {
        store("a", "catalog", "alpha");
        store("b", "catalog", "beta");
        store("c", "template", "alpha");
        store("d", null, null);

        assertThat(repository.findByType("catalog"))
                .extracting(DataStore::getName)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(repository.findByTypeAndCatalogName("catalog", "alpha"))
                .extracting(DataStore::getName)
                .containsExactly("a");
        assertThat(repository.findByName("d")).hasSize(1);
    }

    @Test
    void typeLookupMatchesTheLabelExpression() {
        store("a", "catalog", "alpha");
        store("b", "catalog", null);
        store("c", "catalog.removed", "gamma");
        store("d", "template", "delta");
        store("e", null, null);

        List<String> byType = repository.findByType("catalog").stream()
                .map(DataStore::getId)
                .sorted()
                .toList();
        List<String> byExpression = repository.findAllFilterByLabelExpression("wanaku.type=catalog").stream()
                .map(DataStore::getId)
                .sorted()
                .toList();
        assertThat(byType).isEqualTo(byExpression).hasSize(2);
    }

    @Test
    void pagesAreSortedByNameThenId() {
        for (String name : List.of("delta", "alpha", "charlie", "bravo", "alpha")) {
            store(name, null, null);
        }

        Page<DataStore> first = repository.listPage(0, 2);
        Page<DataStore> last = repository.listPage(4, 2);
        Page<DataStore> beyond = repository.listPage(9, 2);

        assertThat(first.total()).isEqualTo(5);
        assertThat(first.items()).extracting(DataStore::getName).containsExactly("alpha", "alpha");
        assertThat(first.items().get(0).getId()).isLessThan(first.items().get(1).getId());
        assertThat(last.items()).extracting(DataStore::getName).containsExactly("delta");
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(5);
    }
}
