package ai.wanaku.backend.api.v1.datastores;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataStoresImmutableTest {
    DataStoresBean bean;
    DataStoreRepository repository;
    DataStore published;

    @BeforeEach
    void setup() {
        repository = mock(DataStoreRepository.class);
        Instance<DataStoreRepository> provider = mock(Instance.class);
        when(provider.get()).thenReturn(repository);
        bean = new DataStoresBean();
        bean.dataStoreRepositoryInstance = provider;
        bean.init();
        published = new DataStore();
        published.setId("immutable-id");
        published.setName("published-revision");
        published.setLabels(Map.of("semantic.immutable", "true", "wanaku.type", "catalog"));
        when(repository.findById("immutable-id")).thenReturn(published);
        when(repository.findByName("published-revision")).thenReturn(List.of(published));
        when(repository.findAllFilterByLabelExpression(anyString())).thenReturn(List.of(published));
    }

    @Test
    void genericUpdateAndDeletionCannotModifyPublishedRevisions() {
        DataStore modified = new DataStore();
        modified.setId("immutable-id");
        modified.setLabels(Map.of());
        assertThatThrownBy(() -> bean.update(modified)).isInstanceOf(WanakuException.class);
        assertThatThrownBy(() -> bean.removeById("immutable-id")).isInstanceOf(WanakuException.class);
        assertThatThrownBy(() -> bean.remove("published-revision")).isInstanceOf(WanakuException.class);
        assertThatThrownBy(() -> bean.removeIf("wanaku.type=catalog")).isInstanceOf(WanakuException.class);
        verify(repository, never()).update(anyString(), any());
        verify(repository, never()).update(anyString(), any(), any());
        verify(repository, never()).deleteById(anyString());
        verify(repository, never()).deleteById(anyString(), any());
    }

    @Test
    void genericAddCannotOverrideKnownImmutableId() {
        DataStore replacement = new DataStore();
        replacement.setId("immutable-id");
        replacement.setName("another-name");
        when(repository.findByName("another-name")).thenReturn(List.of());
        assertThatThrownBy(() -> bean.add(replacement)).isInstanceOf(WanakuException.class);
        verify(repository, never()).persist(any());
        verify(repository, never()).create(any());
    }

    @Test
    void bulkRemovalDeletesOnlyTheGuardedSnapshotWhenAnUploadArrivesConcurrently() throws Exception {
        Map<String, DataStore> stored = new java.util.concurrent.ConcurrentHashMap<>();
        DataStore ordinary = new DataStore();
        ordinary.setId("ordinary");
        ordinary.setName("ordinary");
        ordinary.setLabels(Map.of());
        DataStore uploaded = new DataStore();
        uploaded.setId("kamelet-current-new-action");
        uploaded.setName(uploaded.getId());
        uploaded.setLabels(Map.of("wanaku.type", "kamelet-current"));
        stored.put(ordinary.getId(), ordinary);
        org.mockito.Mockito.doAnswer(call -> {
                    List<DataStore> snapshot = List.of(ordinary);
                    stored.put(uploaded.getId(), uploaded);
                    return snapshot;
                })
                .when(repository)
                .findAllFilterByLabelExpression("category=cleanup");
        when(repository.deleteById(anyString())).thenAnswer(call -> stored.remove(call.getArgument(0)) != null);
        org.assertj.core.api.Assertions.assertThat(bean.removeIf("category=cleanup"))
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(stored).containsOnlyKeys(uploaded.getId());
        verify(repository, never()).removeIf(anyString());
    }

    @Test
    void absentManagedIdentifiersCannotBeDeletedBeforeAConcurrentUpload() {
        for (String reserved : List.of("kamelet-current-new-action", "kamelet-revision-new-action-digest")) {
            assertThatThrownBy(() -> bean.removeById(reserved))
                    .isInstanceOf(ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException.class);
            assertThatThrownBy(() -> bean.remove(reserved))
                    .isInstanceOf(ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException.class);
            verify(repository, never()).findById(reserved);
            verify(repository, never()).deleteById(reserved);
        }
    }
}
