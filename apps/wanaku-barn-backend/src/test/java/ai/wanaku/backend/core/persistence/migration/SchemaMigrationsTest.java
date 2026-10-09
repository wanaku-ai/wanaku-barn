package ai.wanaku.backend.core.persistence.migration;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.infinispan.manager.EmbeddedCacheManager;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class SchemaMigrationsTest {

    @Inject
    SchemaMigrations migrations;

    @Inject
    EmbeddedCacheManager cacheManager;

    // Other tests can clear all caches, including the stored version
    @BeforeEach
    @AfterEach
    void restoreVersion() {
        cacheManager
                .<String, Long>getCache(SchemaMigrations.METADATA_CACHE)
                .put(SchemaMigrations.VERSION_KEY, SchemaMigrations.CURRENT_VERSION);
    }

    @Test
    void startupStoresTheCurrentVersionAndReportsReadiness() {
        assertThat(migrations.isComplete()).isTrue();
        assertThat(migrations.steps().get(migrations.steps().size() - 1).version())
                .isEqualTo(SchemaMigrations.CURRENT_VERSION);
        given().get("/q/health/ready").then().statusCode(200).body("checks.name", hasItem("Persistence schema"));
    }

    @Test
    void stepsRunOnceInOrder() {
        List<Long> ran = new ArrayList<>();
        List<SchemaMigrations.Step> steps = List.of(
                new SchemaMigrations.Step(1, "one", () -> ran.add(1L)),
                new SchemaMigrations.Step(2, "two", () -> ran.add(2L)),
                new SchemaMigrations.Step(3, "three", () -> ran.add(3L)));

        migrations.run(1, steps);
        migrations.run(migrations.storedVersion(), steps);

        assertThat(ran).containsExactly(2L, 3L);
        assertThat(migrations.storedVersion()).isEqualTo(3);
    }

    @Test
    void anInterruptedMigrationContinuesWithTheFailedStep() {
        List<Long> ran = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        List<SchemaMigrations.Step> steps = List.of(
                new SchemaMigrations.Step(1, "one", () -> ran.add(1L)),
                new SchemaMigrations.Step(2, "two", () -> {
                    if (fail.get()) {
                        throw new IllegalStateException("interrupted");
                    }
                    ran.add(2L);
                }),
                new SchemaMigrations.Step(3, "three", () -> ran.add(3L)));

        assertThatThrownBy(() -> migrations.run(0, steps)).hasMessage("interrupted");
        assertThat(migrations.storedVersion()).isEqualTo(1);

        fail.set(false);
        migrations.run(migrations.storedVersion(), steps);
        assertThat(ran).containsExactly(1L, 2L, 3L);
        assertThat(migrations.storedVersion()).isEqualTo(3);
    }

    @Test
    void newerDataIsRejectedWithoutChanges() {
        List<Long> ran = new ArrayList<>();
        List<SchemaMigrations.Step> steps = List.of(new SchemaMigrations.Step(1, "one", () -> ran.add(1L)));

        assertThatThrownBy(() -> migrations.run(5, steps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version 5");
        assertThat(ran).isEmpty();
        assertThat(migrations.storedVersion()).isEqualTo(SchemaMigrations.CURRENT_VERSION);
    }
}
