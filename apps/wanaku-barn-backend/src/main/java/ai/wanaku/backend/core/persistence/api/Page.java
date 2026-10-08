package ai.wanaku.backend.core.persistence.api;

import java.util.List;

/**
 * One page of query results.
 *
 * @param items the items of the page
 * @param total the number of items that match the query
 * @param <T> the item type
 */
public record Page<T>(List<T> items, long total) {}
