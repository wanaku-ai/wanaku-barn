package ai.wanaku.backend.core.persistence.infinispan;

import java.util.List;
import java.util.function.Predicate;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import ai.wanaku.backend.common.util.LabelExpressionParser;
import ai.wanaku.backend.common.util.LabelExpressionParser.LabelExpressionParseException;
import ai.wanaku.backend.core.persistence.api.LabelAwareInfinispanRepository;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.LabelsAwareEntity;
import ai.wanaku.core.util.StringHelper;

public abstract class AbstractLabelAwareInfinispanRepository<A extends LabelsAwareEntity<K>, K>
        extends AbstractInfinispanRepository<A, K> implements LabelAwareInfinispanRepository<A, K> {

    protected AbstractLabelAwareInfinispanRepository(EmbeddedCacheManager cacheManager, Configuration configuration) {
        super(cacheManager, configuration);
    }

    @Override
    public List<A> findAllFilterByLabelExpression(String labelExpression) {
        // If no label expression provided, return all entities
        if (StringHelper.isBlank(labelExpression)) {
            return listAll();
        }
        try {
            // Parse the label expression into a predicate
            @SuppressWarnings("unchecked")
            Predicate<A> predicate = (Predicate<A>) LabelExpressionParser.parse(labelExpression);

            Cache<K, A> c = cacheManager.getCache(entityName());
            return c.values().stream().filter(predicate).toList();

        } catch (LabelExpressionParseException e) {
            throw new WanakuException("Invalid label expression: %s".formatted(labelExpression), e);
        } catch (Exception e) {
            throw new WanakuException("Failed to execute label query: %s".formatted(labelExpression), e);
        }
    }

    @Override
    public int removeIf(String labelExpression) throws LabelExpressionParseException {
        Cache<K, A> cache = cacheManager.getCache(entityName());
        @SuppressWarnings("unchecked")
        Predicate<A> predicate = (Predicate<A>) LabelExpressionParser.parse(labelExpression);
        try {
            lock.lock();
            int removed = 0;
            for (var entry : List.copyOf(cache.entrySet())) {
                if (predicate.test(entry.getValue()) && cache.remove(entry.getKey(), entry.getValue())) {
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }
}
