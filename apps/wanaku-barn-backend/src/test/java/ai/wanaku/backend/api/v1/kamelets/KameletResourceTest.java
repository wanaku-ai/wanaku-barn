package ai.wanaku.backend.api.v1.kamelets;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.backend.api.v1.kamelets.model.KameletSummary;
import ai.wanaku.backend.api.v1.kamelets.model.KameletUpload;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class KameletResourceTest {
    @Inject
    DataStoreRepository repository;

    private final Set<String> names = new HashSet<>();

    @AfterEach
    void cleanup() throws ai.wanaku.backend.common.util.LabelExpressionParser.LabelExpressionParseException {
        for (String name : names) repository.removeIf("kamelet.name=" + name);
    }

    @Test
    void servesNativeYamlImmediatelyWithExactBytesEtagAndHistoricalPins() {
        String name = name();
        String original = ("# Café😀\n" + KameletTestSupport.actionYaml(name, "Old reply")).replace("\n", "\r\n");
        KameletSummary first = upload(original);
        given().get("/api/v1/kamelets/{name}", name)
                .then()
                .statusCode(200)
                .body("data.yaml", equalTo(original))
                .body("data.source", equalTo("uploaded"));
        byte[] downloaded = given().get("/api/v1/kamelets/{name}.kamelet.yaml", name)
                .then()
                .statusCode(200)
                .contentType("application/yaml")
                .header("ETag", equalTo("\"" + first.sha256 + "\""))
                .extract()
                .asByteArray();
        assertThat(downloaded).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
        KameletSummary second = upload(KameletTestSupport.actionYaml(name, "New reply"));
        given().queryParam("sha256", first.sha256)
                .get("/api/v1/kamelets/{name}", name)
                .then()
                .statusCode(200)
                .body("data.yaml", equalTo(original));
        given().get("/api/v1/kamelets/{name}", name).then().statusCode(200).body("data.sha256", equalTo(second.sha256));
        given().delete("/api/v1/kamelets/{name}", name).then().statusCode(200);
        given().get("/api/v1/kamelets/{name}.kamelet.yaml", name).then().statusCode(404);
        given().queryParam("sha256", first.sha256)
                .get("/api/v1/kamelets/{name}.kamelet.yaml", name)
                .then()
                .statusCode(200)
                .body(equalTo(original));
        given().queryParam("sha256", "bad")
                .get("/api/v1/kamelets/{name}", name)
                .then()
                .statusCode(400);
        given().queryParam("sha256", "0".repeat(64))
                .get("/api/v1/kamelets/{name}", name)
                .then()
                .statusCode(404);
        given().accept("application/json")
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("paths.'/api/v1/kamelets'.post.summary", equalTo("Upload a Kamelet"));
    }

    @Test
    void genericUploadsShowEligibilityWithoutExecutionAndBadUploadsDoNotPersist() {
        String name = name();
        KameletSummary summary = upload(
                KameletTestSupport.actionYaml(name, "Reply").replace("kamelet.type: action", "kamelet.type: source"));
        assertThat(summary.semanticEligible).isFalse();
        assertThat(summary.semanticEligibilityReason).contains("Source Kamelets");
        given().contentType("application/json")
                .body(Map.of("yaml", "kind: Kamelet"))
                .post("/api/v1/kamelets")
                .then()
                .statusCode(422);
        given().contentType("application/json")
                .body(Map.of("yaml", ""))
                .post("/api/v1/kamelets")
                .then()
                .statusCode(400);
        given().get("/api/v1/kamelets/wsr-billing-action")
                .then()
                .statusCode(200)
                .body("data.source", equalTo("bundled"));
        given().delete("/api/v1/kamelets/wsr-billing-action").then().statusCode(409);
    }

    @Test
    void nativeSinkUploadsAppearImmediatelyInTheSemanticWizardCatalog() {
        String name = name();
        String yaml = KameletTestSupport.sinkYaml(name);
        KameletSummary summary = upload(yaml);
        assertThat(summary.semanticEligible).isTrue();
        var actions = given().get("/api/v1/semantic-routers/actions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("data", ai.wanaku.backend.api.v1.semanticrouter.model.SemanticAction.class);
        assertThat(actions).anySatisfy(action -> {
            assertThat(action.id).isEqualTo(name);
            assertThat(action.type).isEqualTo("sink");
            assertThat(action.sha256).isEqualTo(summary.sha256);
            assertThat(action.outputSchema).containsEntry("type", "string");
        });
        given().queryParam("sha256", summary.sha256)
                .get("/api/v1/kamelets/{name}.kamelet.yaml", name)
                .then()
                .statusCode(200)
                .body(equalTo(yaml));
    }

    @Test
    void genericDataStoreAndCatalogTemplateUploadsCannotMutateOrRemoveManagedRecords() throws Exception {
        String name = name();
        KameletSummary uploaded = upload(KameletTestSupport.actionYaml(name, "Original"));
        for (String id : new String[] {KameletBean.currentId(name), KameletBean.revisionId(name, uploaded.sha256)}) {
            DataStore existing = repository.findById(id);
            String before = existing.getData();
            DataStore replacement = new DataStore();
            replacement.setId(id);
            replacement.setName("replace-" + name);
            replacement.setData("changed");
            replacement.setLabels(Map.of());
            for (String method : new String[] {"POST", "PUT"})
                given().contentType("application/json")
                        .body(replacement)
                        .request(method, "/api/v1/data-store")
                        .then()
                        .statusCode(409);
            given().delete("/api/v1/data-store/{id}", id).then().statusCode(409);
            given().queryParam("name", existing.getName())
                    .delete("/api/v1/data-store")
                    .then()
                    .statusCode(409);
            given().queryParam("labelExpression", "kamelet.name=" + name)
                    .delete("/api/v1/data-store/labels")
                    .then()
                    .statusCode(409);
            replacement.setData(catalogZip(name));
            given().contentType("application/json")
                    .body(replacement)
                    .post("/api/v1/service-catalog")
                    .then()
                    .statusCode(409);
            given().contentType("application/json")
                    .body(replacement)
                    .post("/api/v1/service-template/deploy")
                    .then()
                    .statusCode(409);
            assertThat(repository.findById(id).getData()).isEqualTo(before);
        }
        DataStore forged = new DataStore();
        forged.setId(KameletBean.currentId("forged-" + name));
        forged.setName("forged-" + name);
        forged.setData("changed");
        forged.setLabels(Map.of());
        given().contentType("application/json")
                .body(forged)
                .post("/api/v1/data-store")
                .then()
                .statusCode(409);
        assertThat(repository.findById(forged.getId())).isNull();
        given().get("/api/v1/kamelets/{name}", name)
                .then()
                .statusCode(200)
                .body("data.sha256", equalTo(uploaded.sha256));
    }

    @Test
    void actualRepositoryCreatesImmutableIdentifiersAtomicallyAcrossConcurrentCalls() throws Exception {
        String name = name();
        String id = "atomic-" + name;
        DataStore first = new DataStore();
        first.setId(id);
        first.setName(id);
        first.setData("first");
        first.setLabels(Map.of("kamelet.name", name));
        DataStore second = new DataStore();
        second.setId(id);
        second.setName(id);
        second.setData("second");
        second.setLabels(Map.of("kamelet.name", name));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> repository.persistIfAbsent(first));
            var b = executor.submit(() -> repository.persistIfAbsent(second));
            DataStore existingA = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            DataStore existingB = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.stream.Stream.of(existingA, existingB)
                            .filter(java.util.Objects::isNull)
                            .count())
                    .isEqualTo(1);
            String winner = existingA == null ? first.getData() : second.getData();
            assertThat(repository.findById(id).getData()).isEqualTo(winner);
        }
    }

    private String name() {
        String name = "remote-" + java.util.UUID.randomUUID();
        names.add(name);
        return name;
    }

    private static KameletSummary upload(String yaml) {
        KameletUpload upload = new KameletUpload();
        upload.yaml = yaml;
        return given().contentType("application/json")
                .body(upload)
                .post("/api/v1/kamelets")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("data", KameletSummary.class);
    }

    private static String catalogZip(String name) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("index.properties"));
            zip.write(("catalog.name=" + name
                            + "\ncatalog.description=Managed Kamelet guard test\ncatalog.services=service\ncatalog.routes.service=service/router.camel.yaml\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("service/router.camel.yaml"));
            zip.write("- route: {from: {uri: direct:start, steps: [{log: hello}]}}\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
