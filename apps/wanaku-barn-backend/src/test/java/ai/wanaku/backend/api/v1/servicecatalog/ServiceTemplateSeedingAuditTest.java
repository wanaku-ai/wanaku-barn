package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ServiceTemplateSeedingAuditTest {

    @Inject
    ServiceTemplateInitializer initializer;

    @Inject
    CatalogLifecycle lifecycle;

    private static JsonPath newestSeed() {
        return given().queryParam("operation", "service_template.seed")
                .queryParam("limit", 1)
                .get("/api/v1/audit/events")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void reseedingAPurgedBuiltInTemplateIsRecorded() {
        // Other test classes can remove the built-in templates; seeding restores them
        initializer.loadBuiltInTemplates(null);
        Long newest = newestSeed().getObject("data.events[0].sequence", Long.class);
        long before = newest == null ? 0 : newest;
        given().queryParam("name", "ftp-resource")
                .delete("/api/v1/service-template/remove")
                .then()
                .statusCode(200);
        // A removed built-in template is not seeded again; a purged one is
        lifecycle.purgeRemovedBefore("template", java.time.Instant.now());

        initializer.loadBuiltInTemplates(null);

        JsonPath seeded = newestSeed();
        assertThat(seeded.getLong("data.events[0].sequence")).isGreaterThan(before);
        assertThat(seeded.getString("data.events[0].target")).isEqualTo("ftp-resource");
        assertThat(seeded.getString("data.events[0].protocol")).isEqualTo("startup");
        assertThat(seeded.getString("data.events[0].decision")).isEqualTo("allow");
    }
}
