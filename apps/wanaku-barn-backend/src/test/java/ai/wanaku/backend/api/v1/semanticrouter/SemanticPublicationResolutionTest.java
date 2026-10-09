package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticResolvedPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises publication discovery through REST against the actual Infinispan repository. */
@QuarkusTest
class SemanticPublicationResolutionTest {
    @Inject
    DataStoreRepository repository;

    @Inject
    ObjectMapper mapper;

    private final Set<String> definitionIds = new HashSet<>();

    @AfterEach
    void cleanup() throws Exception {
        for (String id : definitionIds) {
            repository.deleteById(id);
            repository.removeIf("semantic.definition=" + id);
        }
    }

    @Test
    void draftEditsPreservePublishedSelectionAndRevertingPublicationMovesCurrentPointer() {
        SemanticRouterDefinition definition = create();
        String originalDescription = definition.description;
        SemanticPublication first = publish(definition.id);
        definition.expertId = "unconfigured-expert";
        definition.toolName = "draft_only_tool";
        update(definition);
        SemanticResolvedPublication resolved = resolve(definition.name, null);
        assertThat(resolved.expert.bean).isEqualTo("supportExpert");
        assertThat(resolved.expert.id).isEqualTo("support");
        assertThat(resolved.toolName).isEqualTo(first.toolName);
        assertThat(resolved.sha256).isEqualTo(first.sha256);
        assertThat(resolved.service).isEqualTo("service");
        definition.expertId = "support";
        definition.toolName = first.toolName;
        definition.description = "Second published description";
        update(definition);
        SemanticPublication second = publish(definition.id);
        assertThat(resolve(definition.name, null).revision).isEqualTo(second.revision);
        assertThat(resolve(definition.name, first.revision).sha256).isEqualTo(first.sha256);
        definition.description = originalDescription;
        update(definition);
        assertThat(publish(definition.id).revision).isEqualTo(first.revision);
        assertThat(resolve(definition.name, null).revision).isEqualTo(first.revision);
    }

    @Test
    void rejectsAmbiguousAbsentAndUnpublishedNamesAndLegacyMultipleSelections() throws Exception {
        given().get("/api/v1/semantic-routers/resolve").then().statusCode(400);
        given().queryParam("name", "missing-" + java.util.UUID.randomUUID())
                .get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(404);
        SemanticRouterDefinition definition = create();
        given().queryParam("name", definition.name)
                .get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(404);
        SemanticPublication first = publish(definition.id);
        repository.deleteById(SemanticPublicationResolver.currentId(definition.id));
        given().queryParam("name", definition.name)
                .get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(409);
        first.toolName = null;
        first.expert = null;
        DataStore legacyRecord =
                repository.findByName(first.catalogName + "-publication").getFirst();
        legacyRecord.setData(mapper.writeValueAsString(first));
        repository.persist(legacyRecord);
        assertThat(resolve(definition.name, null).revision).isEqualTo(first.revision);
        definition.description = "New legacy revision";
        update(definition);
        publish(definition.id);
        repository.deleteById(SemanticPublicationResolver.currentId(definition.id));
        given().queryParam("name", definition.name)
                .get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(409)
                .body("error.message", org.hamcrest.Matchers.containsString("specify revision"));
        assertThat(resolve(definition.name, first.revision).revision).isEqualTo(first.revision);
        given().queryParam("name", definition.name)
                .queryParam("revision", "rmissing")
                .get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(404);
        SemanticRouterDefinition duplicate = create();
        duplicate.name = definition.name;
        update(duplicate);
        given().queryParam("name", definition.name)
                .queryParam("revision", first.revision)
                .get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(409);
    }

    @Test
    void genericDataStoreEndpointsCannotModifyOrDeleteCurrentPublicationPointer() {
        SemanticRouterDefinition definition = create();
        SemanticPublication publication = publish(definition.id);
        String id = SemanticPublicationResolver.currentId(definition.id);
        DataStore pointer = repository.findById(id);
        String pointerData = pointer.getData();
        assertThat(pointerData).isEqualTo("{\"revision\":\"" + publication.revision + "\"}");
        assertThat(pointer.getLabels()).containsEntry("semantic.immutable", "true");
        DataStore replacement = new DataStore();
        replacement.setId(id);
        replacement.setName("tampered-pointer-" + java.util.UUID.randomUUID());
        replacement.setData("rmissing");
        replacement.setLabels(Map.of());
        given().contentType("application/json")
                .body(replacement)
                .put("/api/v1/data-store")
                .then()
                .statusCode(500);
        given().contentType("application/json")
                .body(replacement)
                .post("/api/v1/data-store")
                .then()
                .statusCode(500);
        given().delete("/api/v1/data-store/{id}", id).then().statusCode(500);
        assertThat(resolve(definition.name, null).sha256).isEqualTo(publication.sha256);
        assertThat(repository.findById(id).getData()).isEqualTo(pointerData);
    }

    private SemanticRouterDefinition create() {
        SemanticRouterDefinition draft = SemanticCatalogTest.definition();
        draft.name = "resolution-" + java.util.UUID.randomUUID();
        SemanticRouterDefinition saved = given().contentType("application/json")
                .body(draft)
                .post("/api/v1/semantic-routers")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("data", SemanticRouterDefinition.class);
        definitionIds.add(saved.id);
        return saved;
    }

    private void update(SemanticRouterDefinition definition) {
        given().contentType("application/json")
                .body(definition)
                .put("/api/v1/semantic-routers/{id}", definition.id)
                .then()
                .statusCode(200);
    }

    private SemanticPublication publish(String id) {
        return given().contentType("application/json")
                .post("/api/v1/semantic-routers/{id}/publish", id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("data", SemanticPublication.class);
    }

    private SemanticResolvedPublication resolve(String name, String revision) {
        var request = given().queryParam("name", name);
        if (revision != null) request.queryParam("revision", revision);
        return request.get("/api/v1/semantic-routers/resolve")
                .then()
                .statusCode(200)
                .body("data.name", equalTo(name))
                .extract()
                .jsonPath()
                .getObject("data", SemanticResolvedPublication.class);
    }
}
