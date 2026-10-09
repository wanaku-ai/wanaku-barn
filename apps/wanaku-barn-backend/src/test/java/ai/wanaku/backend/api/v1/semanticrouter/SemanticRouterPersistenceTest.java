package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPublication;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogZipReader;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.DataStoreRecord;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises actual Infinispan publication and both existing artifact retrieval APIs. */
@QuarkusTest
class SemanticRouterPersistenceTest {
    @Inject
    DataStoreRepository repository;

    @Inject
    SemanticCatalogGenerator generator;

    private String definitionId;

    @AfterEach
    void removeTestRecords() {
        if (definitionId == null) return;
        repository.listAll().stream()
                .filter(row -> definitionId.equals(row.getId())
                        || row.getLabels() != null
                                && definitionId.equals(row.getLabels().get("semantic.definition")))
                .map(DataStore::getId)
                .forEach(repository::deleteById);
    }

    @Test
    void generatedFileInspectionReadsActualResourcesWithoutCreatingStoredArtifacts() {
        var definition = SemanticCatalogTest.definition();
        var before = repository.listAll().stream().map(DataStore::getId).toList();
        Map<String, String> expected =
                CatalogZipReader.readEntriesAsText(generator.generate(definition, "preview", "semantic-preview"));
        Map<String, String> actual = given().contentType("application/json")
                .body(definition)
                .post("/api/v1/semantic-routers/files")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("data");
        assertThat(actual).isEqualTo(expected);
        assertThat(repository.listAll().stream().map(DataStore::getId).toList())
                .containsExactlyInAnyOrderElementsOf(before);
        definition.expertId = "not-configured";
        given().contentType("application/json")
                .body(definition)
                .post("/api/v1/semantic-routers/files")
                .then()
                .statusCode(422);
        assertThat(repository.listAll().stream().map(DataStore::getId).toList())
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void publishedArchiveIsPersistedAndRetrievedWithItsOriginalDigest() {
        String id = given().contentType("application/json")
                .body(SemanticCatalogTest.definition())
                .post("/api/v1/semantic-routers")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.id");
        definitionId = id;
        assertThat(repository.findById(id)).isNotNull();

        SemanticPublication publication = given().contentType("application/json")
                .post("/api/v1/semantic-routers/{id}/publish", id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("data", SemanticPublication.class);

        List<DataStore> artifacts = repository.findByName(publication.catalogName);
        assertThat(artifacts).hasSize(1);
        DataStore artifact = artifacts.getFirst();
        assertThat(artifact.getId()).isNotBlank();
        assertThat(artifact.getLabels())
                .containsEntry("wanaku.type", "catalog")
                .containsEntry("semantic.immutable", "true")
                .containsEntry("semantic.sha256", publication.sha256);
        assertThat(repository.findById(artifact.getId()).getData()).isEqualTo(artifact.getData());

        DataStore retrieved = given().get("/api/v1/data-store/{id}", artifact.getId())
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("data", DataStoreRecord.class);
        assertThat(retrieved.getId()).isEqualTo(artifact.getId());
        assertThat(retrieved.getName()).isEqualTo(publication.catalogName);
        assertThat(retrieved.getData()).isEqualTo(artifact.getData());

        DataStore downloaded = given().queryParam("name", publication.catalogName)
                .get("/api/v1/service-catalog/download")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("data", DataStoreRecord.class);
        byte[] archive = Base64.getDecoder().decode(downloaded.getData());
        assertThat(SemanticCatalogGenerator.digest(archive)).isEqualTo(publication.sha256);
        assertThat(CatalogZipReader.readEntries(archive))
                .containsKeys(
                        "service/router.camel.yaml",
                        "service/kamelets/wsr-billing-action.kamelet.yaml",
                        "service/kamelets/wsr-technical-action.kamelet.yaml");

        List<DataStoreRecord> listed = given().get("/api/v1/data-store")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("data", DataStoreRecord.class);
        assertThat(listed).anySatisfy(row -> {
            assertThat(row.getId()).isEqualTo(artifact.getId());
            assertThat(row.getData()).isEqualTo(artifact.getData());
        });

        DataStore draft = repository.findById(id);
        assertThat(draft.getLabels()).containsEntry("wanaku.type", "semantic-definition");
        assertThat(draft.getData()).startsWith("{");
        List<DataStore> metadata = repository.findByName(publication.catalogName + "-publication");
        assertThat(metadata).hasSize(1);
        DataStore publicationRecord = metadata.getFirst();
        assertThat(publicationRecord.getId()).isNotBlank();
        assertThat(publicationRecord.getLabels()).containsEntry("wanaku.type", "semantic-publication");
        assertThat(publicationRecord.getData()).startsWith("{");

        given().contentType("application/json")
                .post("/api/v1/semantic-routers/{id}/publish", id)
                .then()
                .statusCode(200)
                .body("data.sha256", equalTo(publication.sha256));
        assertThat(repository.findByName(publication.catalogName)).hasSize(1);
        given().delete("/api/v1/semantic-routers/{id}", id).then().statusCode(200);
        given().get("/api/v1/data-store/{id}", artifact.getId()).then().statusCode(200);
    }
}
