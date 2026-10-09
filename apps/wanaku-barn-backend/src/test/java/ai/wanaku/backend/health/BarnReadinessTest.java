package ai.wanaku.backend.health;

import jakarta.inject.Inject;

import org.infinispan.manager.EmbeddedCacheManager;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that Barn readiness and caches are independent of the service registry. */
@QuarkusTest
class BarnReadinessTest {

    @Inject
    EmbeddedCacheManager cacheManager;

    @Test
    void readinessDoesNotRequireServiceRegistration() {
        given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("checks.name", not(hasItem("service-registry")));

        assertThat(cacheManager.getCacheNames())
                .doesNotContain("capabilities", "activityRecord", "service-lookup-cache");
        assertThat(cacheManager.getCacheConfiguration("capabilities")).isNull();
        assertThat(cacheManager.getCacheConfiguration("activityRecord")).isNull();
        assertThat(cacheManager.getCacheConfiguration("service-lookup-cache")).isNull();
    }
}
