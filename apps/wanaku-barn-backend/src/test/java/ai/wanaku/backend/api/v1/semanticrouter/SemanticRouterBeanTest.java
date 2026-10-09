package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.ws.rs.NotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogValidator;
import ai.wanaku.backend.api.v1.servicecatalog.ServiceCatalogBean;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticRouterBeanTest {
    SemanticRouterBean bean;
    Map<String, DataStore> stored;
    Map<String, DataStore> catalogs;

    @BeforeEach
    void setup() {
        stored = new LinkedHashMap<>();
        catalogs = new LinkedHashMap<>();
        DataStoreRepository repository = mock(DataStoreRepository.class);
        when(repository.findById(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(repository.persist(any())).thenAnswer(call -> {
            DataStore data = call.getArgument(0);
            if (data.getId() == null) data.setId(java.util.UUID.randomUUID().toString());
            stored.put(data.getId(), data);
            return data;
        });
        when(repository.persistIfAbsent(any())).thenAnswer(call -> {
            DataStore data = call.getArgument(0);
            return stored.putIfAbsent(data.getId(), data);
        });
        when(repository.deleteById(anyString())).thenAnswer(call -> stored.remove(call.getArgument(0)) != null);
        when(repository.findAllFilterByLabelExpression(anyString())).thenAnswer(call -> {
            String value = ((String) call.getArgument(0)).substring("wanaku.type=".length());
            return stored.values().stream()
                    .filter(ds -> value.equals(ds.getLabels().get("wanaku.type")))
                    .toList();
        });
        ServiceCatalogBean service = mock(ServiceCatalogBean.class);
        when(service.get(anyString())).thenAnswer(call -> catalogs.get(call.getArgument(0)));
        when(service.deploy(any())).thenAnswer(call -> {
            DataStore data = call.getArgument(0);
            catalogs.put(data.getName(), data);
            return data;
        });
        SemanticActionCatalog catalog = new SemanticActionCatalog();
        catalog.kamelets = ai.wanaku.backend.api.v1.kamelets.KameletTestSupport.catalog(repository);
        catalog.parser = new ai.wanaku.backend.api.v1.kamelets.KameletParser();
        catalog.expertsFile = Optional.empty();
        catalog.init();
        SemanticDefinitionValidator validator = new SemanticDefinitionValidator();
        validator.catalog = catalog;
        SemanticCatalogGenerator generator = new SemanticCatalogGenerator();
        generator.catalog = catalog;
        bean = new SemanticRouterBean();
        bean.repository = repository;
        bean.mapper = new ObjectMapper();
        bean.catalog = catalog;
        bean.validator = validator;
        bean.generator = generator;
        bean.serviceCatalog = service;
        bean.catalogValidator = new CatalogValidator();
        bean.previewClient = mock(SemanticPreviewClient.class);
        bean.publicationResolver = new SemanticPublicationResolver();
        bean.publicationResolver.repository = repository;
        bean.publicationResolver.mapper = bean.mapper;
        bean.publicationResolver.serviceCatalog = service;
        bean.publicationResolver.catalog = catalog;
    }

    @Test
    void fileInspectionReturnsActualGeneratedCatalogWithoutPersistenceOrInference() {
        var definition = SemanticCatalogTest.definition();
        var expected = ai.wanaku.backend.api.v1.servicecatalog.CatalogZipReader.readEntriesAsText(
                bean.generator.generate(definition, "preview", "semantic-preview"));
        assertThat(bean.files(definition)).isEqualTo(expected);
        assertThat(definition.id).isNull();
        assertThat(stored).isEmpty();
        assertThat(catalogs).isEmpty();
        verifyNoInteractions(bean.repository, bean.serviceCatalog, bean.previewClient);
    }

    @Test
    void invalidFileInspectionDoesNotStoreOrEvaluateTheDraft() {
        var definition = SemanticCatalogTest.definition();
        definition.instructions = "";
        assertThatThrownBy(() -> bean.files(definition))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("instructions");
        verifyNoInteractions(bean.repository, bean.serviceCatalog, bean.previewClient);
        assertThatThrownBy(() -> bean.files(null)).isInstanceOf(InvalidPayloadException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"support route", " ", "9support", "support.route"})
    void invalidNamesNeverReachDraftStorageOrPublication(String name) throws Exception {
        var invalid = SemanticCatalogTest.definition();
        invalid.name = name;
        assertThatThrownBy(() -> bean.save(null, invalid)).isInstanceOf(InvalidPayloadException.class);
        var invalidTool = SemanticCatalogTest.definition();
        invalidTool.toolName = name;
        assertThatThrownBy(() -> bean.save(null, invalidTool)).isInstanceOf(InvalidPayloadException.class);
        assertThat(stored).isEmpty();
        var saved = bean.save(null, SemanticCatalogTest.definition());
        saved.name = name;
        stored.get(saved.id).setData(bean.mapper.writeValueAsString(saved));
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(InvalidPayloadException.class);
        saved.name = "Support";
        saved.toolName = name;
        stored.get(saved.id).setData(bean.mapper.writeValueAsString(saved));
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(InvalidPayloadException.class);
        assertThat(catalogs).isEmpty();
        verifyNoInteractions(bean.previewClient, bean.serviceCatalog);
    }

    @Test
    void uppercaseAndHyphenToolNamesPublishAndResolveWithoutChangingCase() {
        var definition = SemanticCatalogTest.definition();
        definition.name = "Support-Route";
        definition.toolName = "Route-Support_2";
        var saved = bean.save(null, definition);
        var publication = bean.publish(saved.id);
        assertThat(publication.toolName).isEqualTo("Route-Support_2");
        assertThat(bean.resolve(saved.name, null).toolName).isEqualTo("Route-Support_2");
    }

    @Test
    void existingStoredDisplayNamesRemainResolvableWithoutMigration() throws Exception {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        var publication = bean.publish(saved.id);
        saved.name = "Legacy Support Route";
        stored.get(saved.id).setData(bean.mapper.writeValueAsString(saved));
        assertThat(bean.resolve(saved.name, null).revision).isEqualTo(publication.revision);
    }

    @Test
    void draftsPersistAndPublishImmutableRevisionIdempotently() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        assertThat(bean.get(saved.id).examples).hasSize(1);
        var first = bean.publish(saved.id);
        var repeated = bean.publish(saved.id);
        assertThat(repeated.revision).isEqualTo(first.revision);
        assertThat(repeated.sha256).isEqualTo(first.sha256);
        assertThat(repeated.status).isEqualTo("published");
        assertThat(catalogs).hasSize(1);
        saved.description = "An updated description";
        bean.save(saved.id, saved);
        var changed = bean.publish(saved.id);
        assertThat(changed.revision).isNotEqualTo(first.revision);
        assertThat(bean.revisions(saved.id)).hasSize(2);
        assertThat(catalogs).hasSize(2);
        bean.remove(saved.id);
        assertThat(bean.list()).isEmpty();
        assertThat(catalogs).hasSize(2);
    }

    @Test
    void resolutionKeepsPublishedExpertAndToolUntilSuccessfulPublication() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        String originalDescription = saved.description;
        var first = bean.publish(saved.id);
        saved.expertId = "unconfigured-new-expert";
        saved.toolName = "changed_tool";
        bean.save(saved.id, saved);
        var resolved = bean.resolve(saved.name, null);
        assertThat(resolved.expert.id).isEqualTo("support");
        assertThat(resolved.expert.bean).isEqualTo("supportExpert");
        assertThat(resolved.expert.dependency).isEqualTo("org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT");
        assertThat(resolved.toolName).isEqualTo(first.toolName);
        assertThat(resolved.sha256).isEqualTo(first.sha256);
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(InvalidPayloadException.class);
        assertThat(bean.resolve(saved.name, null).revision).isEqualTo(first.revision);
        saved.expertId = "support";
        saved.toolName = first.toolName;
        saved.description = "Second published description";
        bean.save(saved.id, saved);
        var second = bean.publish(saved.id);
        assertThat(bean.resolve(saved.name, null).revision).isEqualTo(second.revision);
        assertThat(bean.resolve(saved.name, first.revision).sha256).isEqualTo(first.sha256);
        saved.description = originalDescription;
        bean.save(saved.id, saved);
        assertThat(bean.publish(saved.id).revision).isEqualTo(first.revision);
        assertThat(bean.resolve(saved.name, null).revision).isEqualTo(first.revision);
    }

    @Test
    void exactNameResolutionRejectsDuplicatesAndMissingPublications() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        assertThatThrownBy(() -> bean.resolve(saved.name, null)).isInstanceOf(NotFoundException.class);
        var publication = bean.publish(saved.id);
        assertThatThrownBy(() -> bean.resolve(saved.name.toLowerCase(), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> bean.resolve(saved.name, "rmissing")).isInstanceOf(NotFoundException.class);
        bean.save(null, SemanticCatalogTest.definition());
        assertThatThrownBy(() -> bean.resolve(saved.name, publication.revision))
                .isInstanceOf(SemanticResolutionConflictException.class);
    }

    @Test
    void legacySinglePublicationRecoversExpertFromVerifiedArchiveWithoutDraftExpert() throws Exception {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        var publication = bean.publish(saved.id);
        DataStore metadata = stored.values().stream()
                .filter(row ->
                        SemanticRouterBean.PUBLICATION.equals(row.getLabels().get("wanaku.type")))
                .findFirst()
                .orElseThrow();
        var legacy = bean.mapper.readTree(metadata.getData());
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy)
                .remove(java.util.List.of("expert", "toolName", "camelBuild"));
        metadata.setData(bean.mapper.writeValueAsString(legacy));
        stored.remove(SemanticPublicationResolver.currentId(saved.id));
        saved.expertId = "different-draft-expert";
        bean.save(saved.id, saved);
        var resolved = bean.resolve(saved.name, null);
        assertThat(resolved.revision).isEqualTo(publication.revision);
        assertThat(resolved.toolName).isEqualTo(publication.toolName);
        assertThat(resolved.camelBuild).isEqualTo(SemanticCatalogGenerator.CAMEL_BUILD);
        assertThat(resolved.expert.bean).isEqualTo("supportExpert");
        bean.catalog.experts().getFirst().bean = "differentConfiguredBean";
        assertThat(bean.resolve(saved.name, null).expert).isNull();
    }

    @Test
    void legacySeveralPublicationsRequireExplicitRevisionOrRepublish() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        var first = bean.publish(saved.id);
        saved.description = "Changed description";
        bean.save(saved.id, saved);
        var second = bean.publish(saved.id);
        stored.remove(SemanticPublicationResolver.currentId(saved.id));
        assertThatThrownBy(() -> bean.resolve(saved.name, null))
                .isInstanceOf(SemanticResolutionConflictException.class);
        assertThat(bean.resolve(saved.name, first.revision).sha256).isEqualTo(first.sha256);
        assertThat(bean.resolve(saved.name, second.revision).sha256).isEqualTo(second.sha256);
        bean.publish(saved.id);
        assertThat(bean.resolve(saved.name, null).revision).isEqualTo(second.revision);
    }

    @Test
    void resolverRejectsArchiveTamperingAndDoesNotAdvancePointerAfterFailedPublication() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        var first = bean.publish(saved.id);
        saved.description = "Fail to deploy this change";
        bean.save(saved.id, saved);
        doThrow(new IllegalStateException("Storage unavailable"))
                .when(bean.serviceCatalog)
                .deploy(any());
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(IllegalStateException.class);
        assertThat(bean.resolve(saved.name, null).revision).isEqualTo(first.revision);
        catalogs.get(first.catalogName).setData(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        assertThatThrownBy(() -> bean.resolve(saved.name, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void publicationMetadataFailureAfterDeploymentPreservesPreviousSelection() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        var first = bean.publish(saved.id);
        saved.description = "New revision with failed metadata write";
        bean.save(saved.id, saved);
        doAnswer(call -> {
                    DataStore data = call.getArgument(0);
                    if (SemanticRouterBean.PUBLICATION.equals(data.getLabels().get("wanaku.type")))
                        throw new IllegalStateException("Metadata storage unavailable");
                    return data;
                })
                .when(bean.repository)
                .persist(any());
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(IllegalStateException.class);
        assertThat(catalogs).hasSize(2);
        assertThat(bean.resolve(saved.name, null).sha256).isEqualTo(first.sha256);
    }

    @Test
    void failedFirstPublicationPointerWriteRequiresExplicitRevisionUntilSuccessfulRetry() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        doThrow(new IllegalStateException("Selection storage unavailable"))
                .when(bean.repository)
                .persist(argThat(row -> row != null
                        && SemanticPublicationResolver.CURRENT.equals(
                                row.getLabels().get("wanaku.type"))));
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(IllegalStateException.class);
        var persisted = bean.revisions(saved.id).getFirst();
        assertThat(stored).doesNotContainKey(SemanticPublicationResolver.currentId(saved.id));
        assertThat(catalogs).hasSize(1);
        assertThatThrownBy(() -> bean.resolve(saved.name, null))
                .isInstanceOf(SemanticResolutionConflictException.class);
        assertThat(bean.resolve(saved.name, persisted.revision).sha256).isEqualTo(persisted.sha256);
        doAnswer(call -> {
                    DataStore row = call.getArgument(0);
                    stored.put(row.getId(), row);
                    return row;
                })
                .when(bean.repository)
                .persist(argThat(row -> row != null
                        && SemanticPublicationResolver.CURRENT.equals(
                                row.getLabels().get("wanaku.type"))));
        assertThat(bean.publish(saved.id).revision).isEqualTo(persisted.revision);
        assertThat(bean.resolve(saved.name, null).sha256).isEqualTo(persisted.sha256);
        assertThat(bean.revisions(saved.id)).hasSize(1);
        assertThat(catalogs).hasSize(1);
    }

    @Test
    void pointerWriteFailurePreservesPreviouslySelectedPublication() {
        var saved = bean.save(null, SemanticCatalogTest.definition());
        var first = bean.publish(saved.id);
        saved.description = "New revision with failed pointer write";
        bean.save(saved.id, saved);
        doThrow(new IllegalStateException("Selection storage unavailable"))
                .when(bean.repository)
                .persist(argThat(row -> row != null
                        && SemanticPublicationResolver.CURRENT.equals(
                                row.getLabels().get("wanaku.type"))));
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(IllegalStateException.class);
        assertThat(bean.revisions(saved.id)).hasSize(2);
        assertThat(catalogs).hasSize(2);
        assertThat(bean.resolve(saved.name, null).sha256).isEqualTo(first.sha256);
    }

    @Test
    void incompleteDraftCanBeSavedButCannotBePublished() {
        var draft = new ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition();
        draft.name = "Incomplete";
        var saved = bean.save(null, draft);
        assertThat(bean.get(saved.id).name).isEqualTo("Incomplete");
        assertThatThrownBy(() -> bean.publish(saved.id)).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void credentialValuesNeverReachDraftStorage() {
        var definition = SemanticCatalogTest.definition();
        definition.actions.getFirst().configuration = Map.of("credentialRef", "raw-secret");
        assertThatThrownBy(() -> bean.save(null, definition)).isInstanceOf(InvalidPayloadException.class);
        assertThat(stored).isEmpty();
    }
}
