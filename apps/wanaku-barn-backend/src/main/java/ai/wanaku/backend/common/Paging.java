package ai.wanaku.backend.common;

import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * Opt-in pagination for list endpoints.
 * <p>
 * A request without {@code offset} and {@code limit} receives the complete list, as before. A request with
 * either parameter receives one page, and the {@value #TOTAL_HEADER} response header contains the number of
 * matching items. The response body keeps the same shape in both cases.
 * </p>
 */
public final class Paging {

    /** The response header that contains the total number of matching items. */
    public static final String TOTAL_HEADER = "X-Total-Count";

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private Paging() {}

    /**
     * Tells whether the request asks for a page.
     *
     * @param offset the {@code offset} parameter, can be null
     * @param limit the {@code limit} parameter, can be null
     * @return true if either parameter is present
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public static boolean requested(Integer offset, Integer limit) {
        if (offset != null && offset < 0) {
            throw new IllegalArgumentException("'offset' must be 0 or greater");
        }
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException("'limit' must be between 1 and %d".formatted(MAX_LIMIT));
        }
        return offset != null || limit != null;
    }

    public static int offset(Integer offset) {
        return offset == null ? 0 : offset;
    }

    public static int limit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    /**
     * Returns one page of a sorted list.
     *
     * @param sorted the complete, sorted list
     * @param offset the {@code offset} parameter, can be null
     * @param limit the {@code limit} parameter, can be null
     * @return the page
     */
    public static <T> List<T> slice(List<T> sorted, Integer offset, Integer limit) {
        int from = Math.min(offset(offset), sorted.size());
        int to = (int) Math.min((long) from + limit(limit), sorted.size());
        return sorted.subList(from, to);
    }

    /**
     * Builds a list response. The {@value #TOTAL_HEADER} header is added only for a page.
     *
     * @param items the items to return
     * @param total the number of matching items, or {@code null} for a complete list
     * @return the response
     */
    public static <T> RestResponse<WanakuResponse<List<T>>> response(List<T> items, Long total) {
        RestResponse.ResponseBuilder<WanakuResponse<List<T>>> builder =
                RestResponse.ResponseBuilder.ok(new WanakuResponse<>(items));
        if (total != null) {
            builder.header(TOTAL_HEADER, total);
        }
        return builder.build();
    }
}
