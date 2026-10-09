package ai.wanaku.backend.api.v1.semanticrouter;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.api.v1.kamelets.KameletTestSupport;
import ai.wanaku.backend.api.v1.kamelets.model.KameletUpload;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogZipReader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class SemanticKameletPinningTest {
    private SemanticRouterBeanTest fixture;
    private SemanticRouterBean bean;

    @BeforeEach
    void setup() {
        fixture = new SemanticRouterBeanTest();
        fixture.setup();
        bean = fixture.bean;
    }

    @Test
    void savedSelectionsKeepExactMetadataAndBytesAfterReplacementAndRemoval() {
        var original = bean.catalog.kamelets.upload(upload("Original"));
        SemanticRouterDefinition definition = SemanticCatalogTest.definition();
        definition.actions.getFirst().actionId = original.name;
        var saved = bean.save(null, definition);
        assertThat(saved.actions.getFirst().sha256).isEqualTo(original.sha256);
        var first = bean.publish(saved.id);
        var changed = upload("Replacement");
        changed.yaml =
                changed.yaml.replace("type: string\n        default: Support", "type: integer\n        default: 42");
        var newer = bean.catalog.kamelets.upload(changed);
        var actions = bean.actions(saved.id);
        assertThat(actions).anySatisfy(a -> {
            assertThat(a.id).isEqualTo(original.name);
            assertThat(a.sha256).isEqualTo(original.sha256);
            assertThat(a.current).isFalse();
        });
        assertThat(actions).anySatisfy(a -> {
            assertThat(a.id).isEqualTo(original.name);
            assertThat(a.sha256).isEqualTo(newer.sha256);
            assertThat(a.current).isTrue();
        });
        assertThat(bean.validate(saved).valid).isTrue();
        assertThat(bean.publish(saved.id).sha256).isEqualTo(first.sha256);
        assertThat(bean.files(saved).get("service/kamelets/remote-action.kamelet.yaml"))
                .contains("Original");
        bean.catalog.kamelets.remove(original.name);
        assertThat(bean.publish(saved.id).sha256).isEqualTo(first.sha256);
        assertThat(bean.actions(saved.id)).anySatisfy(a -> assertThat(a.sha256).isEqualTo(original.sha256));
        assertThat(bean.actions(null)).noneMatch(a -> a.id.equals(original.name));
        saved.actions.getFirst().sha256 = newer.sha256;
        saved.actions.getFirst().configuration = java.util.Map.of("prefix", "42");
        bean.save(saved.id, saved);
        assertThat(bean.publish(saved.id).sha256).isNotEqualTo(first.sha256);
    }

    @Test
    void savedNativeSinkKeepsExactBytesAndAdapterAfterReplacementAndRemoval() {
        KameletUpload upload = new KameletUpload();
        upload.yaml = KameletTestSupport.sinkYaml("remote-sink");
        String originalYaml = upload.yaml;
        var original = bean.catalog.kamelets.upload(upload);
        var definition = SemanticCatalogTest.definition();
        var selection = definition.actions.getFirst();
        selection.actionId = original.name;
        selection.configuration = java.util.Map.of("topic", "support", "bootstrapServers", "kafka:9092");
        var saved = bean.save(null, definition);
        assertThat(saved.actions.getFirst().sha256).isEqualTo(original.sha256);
        var publication = bean.publish(saved.id);
        upload.yaml = upload.yaml.replace("Send the message", "Forward the message");
        var newer = bean.catalog.kamelets.upload(upload);
        assertThat(newer.sha256).isNotEqualTo(original.sha256);
        bean.catalog.kamelets.remove(original.name);
        assertThat(bean.validate(saved).valid).isTrue();
        assertThat(bean.publish(saved.id).sha256).isEqualTo(publication.sha256);
        assertThat(bean.files(saved).get("service/kamelets/remote-sink.kamelet.yaml"))
                .isEqualTo(originalYaml);
        assertThat(bean.actions(saved.id)).anySatisfy(action -> {
            assertThat(action.id).isEqualTo(original.name);
            assertThat(action.type).isEqualTo("sink");
            assertThat(action.sha256).isEqualTo(original.sha256);
            assertThat(action.current).isFalse();
        });
    }

    @Test
    void twoPassPublicationDoesNotFollowAConcurrentCurrentSelectionChange() {
        var original = bean.catalog.kamelets.upload(upload("Original"));
        var definition = SemanticCatalogTest.definition();
        definition.actions.getFirst().actionId = original.name;
        var saved = bean.save(null, definition);
        bean.generator = spy(bean.generator);
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
                    byte[] archive = (byte[]) call.callRealMethod();
                    if (changed.compareAndSet(false, true))
                        bean.catalog.kamelets.upload(upload("Concurrent replacement"));
                    return archive;
                })
                .when(bean.generator)
                .generate(any(), anyString(), anyString());
        var published = bean.publish(saved.id);
        var archive = Base64.getDecoder()
                .decode(fixture.catalogs.get(published.catalogName).getData());
        assertThat(CatalogZipReader.readEntriesAsText(archive).get("service/kamelets/remote-action.kamelet.yaml"))
                .contains("Original")
                .doesNotContain("Concurrent replacement");
        assertThat(bean.get(saved.id).actions.getFirst().sha256).isEqualTo(original.sha256);
    }

    @Test
    void filesAndValidationBindInMemoryWithoutRetainingLocalRevisions() {
        var definition = SemanticCatalogTest.definition();
        assertThat(bean.validate(definition).valid).isTrue();
        assertThat(definition.actions.getFirst().sha256).hasSize(64);
        assertThat(bean.files(definition)).containsKey(SemanticCatalogGenerator.MAIN);
        assertThat(fixture.stored).isEmpty();
        assertThat(fixture.catalogs).isEmpty();
    }

    @Test
    void onePublicationCannotSelectDifferentRevisionsForTheSameNativeFilename() {
        var first = bean.catalog.kamelets.upload(upload("First"));
        var second = bean.catalog.kamelets.upload(upload("Second"));
        var definition = SemanticCatalogTest.definition();
        definition.actions.getFirst().actionId = first.name;
        definition.actions.getFirst().sha256 = first.sha256;
        definition.actions.getLast().actionId = first.name;
        definition.actions.getLast().sha256 = second.sha256;
        assertThat(bean.validate(definition).errors).anySatisfy(e -> {
            assertThat(e.field).isEqualTo("actions[1].sha256");
            assertThat(e.message).contains("same revision");
        });
        assertThatThrownBy(() -> bean.save(null, definition)).isInstanceOf(InvalidPayloadException.class);
    }

    private static KameletUpload upload(String body) {
        KameletUpload upload = new KameletUpload();
        upload.yaml = KameletTestSupport.actionYaml("remote-action", body);
        return upload;
    }
}
