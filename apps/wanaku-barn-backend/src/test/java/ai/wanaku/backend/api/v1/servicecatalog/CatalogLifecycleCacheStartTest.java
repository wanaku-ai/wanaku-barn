package ai.wanaku.backend.api.v1.servicecatalog;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.health.CacheHealth;
import org.infinispan.health.HealthStatus;
import org.infinispan.manager.DefaultCacheManager;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A defined cache that is not started is reported as FAILED by Infinispan, which keeps Barn readiness DOWN after a
 * restart on an existing store (wanaku-barn#287). The version caches must be started when the bean initializes.
 */
class CatalogLifecycleCacheStartTest {

    @Test
    void initStartsTheVersionCaches() throws Exception {
        try (DefaultCacheManager cacheManager = new DefaultCacheManager()) {
            CatalogLifecycle lifecycle = new CatalogLifecycle();
            lifecycle.cacheManager = cacheManager;
            lifecycle.configuration = new ConfigurationBuilder().build();

            lifecycle.init();

            assertThat(cacheManager.getHealth().getCacheHealth())
                    .filteredOn(health -> health.getCacheName().startsWith(CatalogLifecycle.VERSIONS_CACHE))
                    .extracting(CacheHealth::getStatus)
                    .hasSize(2)
                    .doesNotContain(HealthStatus.FAILED);
        }
    }
}
