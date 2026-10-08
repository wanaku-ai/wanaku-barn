package ai.wanaku.backend.core.persistence.infinispan;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.core.persistence.api.ForwardReferenceRepository;
import ai.wanaku.backend.core.persistence.api.PromptReferenceRepository;
import ai.wanaku.backend.core.persistence.api.ToolCallRecordRepository;

/**
 * Produces the repositories. Each repository is a singleton, so all callers share one repository lock.
 */
public class InfinispanPersistenceConfiguration {

    @Inject
    EmbeddedCacheManager cacheManager;

    @Inject
    Configuration configuration;

    @Produces
    @Singleton
    ForwardReferenceRepository forwardReferenceRepository() {
        return new InfinispanForwardReferenceRepository(cacheManager, configuration);
    }

    @Produces
    @Singleton
    PromptReferenceRepository promptReferenceRepository() {
        return new InfinispanPromptReferenceRepository(cacheManager, configuration);
    }

    @Produces
    @Singleton
    DataStoreRepository dataStoreRepository() {
        return new InfinispanDataStoreRepository(cacheManager, configuration);
    }

    @Produces
    @Singleton
    ToolCallRecordRepository toolCallRecordRepository() {
        return new InfinispanToolCallRecordRepository(cacheManager, configuration);
    }
}
