package ai.wanaku.backend.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;

/**
 * Gives every HTTP request a correlation identifier.
 * <p>
 * A valid {@code x-request-id} header from the caller is kept. Otherwise, a new identifier is generated.
 * The identifier is returned in the {@code x-request-id} response header, including on error responses,
 * and is available in the {@code requestId} MDC key for log output.
 * </p>
 * <p>
 * Caller-supplied identity headers are never read: Barn has no trusted identity source.
 * </p>
 */
@ApplicationScoped
public class RequestCorrelation {

    /** The request and response header that carries the correlation identifier. */
    public static final String HEADER = "x-request-id";

    /** The MDC key that holds the correlation identifier. */
    public static final String MDC_KEY = "requestId";

    /** Runs before the other HTTP filters, so that CORS and error responses also carry the header. */
    private static final int FILTER_PRIORITY = 1000;

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    void register(@Observes Filters filters) {
        filters.register(RequestCorrelation::correlate, FILTER_PRIORITY);
    }

    static void correlate(RoutingContext rc) {
        String id = accept(rc.request().getHeader(HEADER));
        rc.response().putHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        rc.addEndHandler(ignored -> MDC.remove(MDC_KEY));
        rc.next();
    }

    /**
     * Returns the supplied identifier if it is valid, or a new identifier.
     *
     * @param supplied the identifier from the caller, can be null
     * @return a printable identifier with at most 128 characters
     */
    static String accept(String supplied) {
        if (supplied != null && VALID_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the correlation identifier of the current request.
     *
     * @return the identifier, or {@code null} outside an HTTP request
     */
    public String requestId() {
        Object id = MDC.get(MDC_KEY);
        return id == null ? null : id.toString();
    }

    /**
     * Returns the trace identifier of the current span.
     *
     * @return the trace identifier, or {@code null} when tracing is disabled or no span is active
     */
    public String traceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }
}
