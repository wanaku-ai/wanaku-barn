package ai.wanaku.backend.api.v1.kamelets;

import jakarta.ws.rs.NotFoundException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.api.v1.kamelets.model.KameletUpload;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KameletBeanTest {
    private final Map<String, DataStore> stored = new ConcurrentHashMap<>();
    private final KameletBean bean = KameletTestSupport.catalog(KameletTestSupport.repository(stored));

    @Test
    void uploadReplacementAndRemovalRetainExactRevisionBytes() {
        var first = bean.upload(upload("Old"));
        assertThat(bean.list()).hasSize(3);
        assertThat(bean.upload(upload("Old")).sha256).isEqualTo(first.sha256);
        assertThat(stored).hasSize(2);
        var second = bean.upload(upload("New"));
        assertThat(second.sha256).isNotEqualTo(first.sha256);
        assertThat(bean.get(first.name, null).sha256).isEqualTo(second.sha256);
        assertThat(bean.get(first.name, first.sha256).yaml).contains("Old");
        bean.remove(first.name);
        assertThat(bean.list()).hasSize(2);
        assertThatThrownBy(() -> bean.get(first.name, null)).isInstanceOf(NotFoundException.class);
        assertThat(bean.get(first.name, first.sha256).yaml).contains("Old");
        assertThat(bean.get(first.name, second.sha256).removable).isFalse();
    }

    @Test
    void invalidAndReservedUploadsNeverCreateRecords() {
        var invalid = new KameletUpload();
        invalid.yaml = "kind: Kamelet";
        assertThatThrownBy(() -> bean.upload(invalid)).isInstanceOf(InvalidPayloadException.class);
        invalid.yaml = bean.get("wsr-billing-action", null).yaml;
        assertThatThrownBy(() -> bean.upload(invalid)).isInstanceOf(EntityAlreadyExistsException.class);
        assertThatThrownBy(() -> bean.remove("wsr-billing-action")).isInstanceOf(EntityAlreadyExistsException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void conflictingExistingImmutableRecordIsNeverOverwrittenOrSelected() {
        var definition = bean.parser.parse(upload("Original").yaml, "uploaded");
        String id = KameletBean.revisionId(definition.name, definition.sha256);
        DataStore tampered = new DataStore();
        tampered.setId(id);
        tampered.setName(id);
        tampered.setData("conflicting bytes");
        tampered.setLabels(Map.of());
        stored.put(id, tampered);
        assertThatThrownBy(() -> bean.upload(upload("Original"))).isInstanceOf(IllegalStateException.class);
        assertThat(stored.get(id).getData()).isEqualTo("conflicting bytes");
        assertThat(stored).doesNotContainKey(KameletBean.currentId(definition.name));
    }

    @Test
    void concurrentBeanInstancesShareImmutableBytesAndSelectACompleteRevision() throws Exception {
        KameletBean other = KameletTestSupport.catalog(bean.repository);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> bean.upload(upload("First")));
            var second = executor.submit(() -> other.upload(upload("Second")));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(bean.get(a.name, a.sha256).yaml).contains("First");
            assertThat(other.get(b.name, b.sha256).yaml).contains("Second");
            assertThat(bean.get(a.name, null).sha256).isIn(a.sha256, b.sha256);
            assertThat(stored).hasSize(3);
        }
    }

    @Test
    void retrievalChecksOriginalContentDigestAndPointerMetadata() {
        var first = bean.upload(upload("Original"));
        stored.get(KameletBean.revisionId(first.name, first.sha256)).setData(upload("Changed").yaml);
        assertThatThrownBy(() -> bean.get(first.name, first.sha256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
        stored.get(KameletBean.currentId(first.name)).setData("not-a-digest");
        assertThatThrownBy(() -> bean.get(first.name, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selection");
    }

    @Test
    void listingSurvivesConcurrentRemovalByResolvingThePointerSnapshot() {
        var uploaded = bean.upload(upload("Original"));
        DataStore pointer = stored.get(KameletBean.currentId(uploaded.name));
        org.mockito.Mockito.doAnswer(call -> {
                    stored.remove(pointer.getId());
                    return java.util.List.of(pointer);
                })
                .when(bean.repository)
                .findAllFilterByLabelExpression("wanaku.type=" + KameletBean.CURRENT);
        assertThat(bean.list()).anySatisfy(row -> assertThat(row.sha256).isEqualTo(uploaded.sha256));
    }

    @Test
    void configuredCatalogRevisionsRemainReadableAfterAFileChange(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path file = directory.resolve("configured-action.kamelet.yaml");
        String original = KameletTestSupport.actionYaml("configured-action", "Original configured reply");
        java.nio.file.Files.writeString(file, original);
        KameletBean configured = new KameletBean();
        configured.repository = bean.repository;
        configured.parser = new KameletParser();
        configured.actionsDirectory = java.util.Optional.of(directory.toString());
        configured.init();
        var first = configured.get("configured-action", null);
        assertThat(first.source).isEqualTo("configured");
        assertThat(stored).isEmpty();
        configured.retain(first);
        java.nio.file.Files.writeString(
                file, KameletTestSupport.actionYaml("configured-action", "New configured reply"));
        KameletBean restarted = new KameletBean();
        restarted.repository = bean.repository;
        restarted.parser = new KameletParser();
        restarted.actionsDirectory = java.util.Optional.of(directory.toString());
        restarted.init();
        assertThat(restarted.get("configured-action", first.sha256).yaml).isEqualTo(original);
        assertThat(restarted.get("configured-action", null).sha256).isNotEqualTo(first.sha256);
        KameletUpload conflict = new KameletUpload();
        conflict.yaml = original;
        assertThatThrownBy(() -> restarted.upload(conflict)).isInstanceOf(EntityAlreadyExistsException.class);
    }

    private static KameletUpload upload(String reply) {
        KameletUpload upload = new KameletUpload();
        upload.yaml = KameletTestSupport.actionYaml("remote-action", reply);
        return upload;
    }
}
