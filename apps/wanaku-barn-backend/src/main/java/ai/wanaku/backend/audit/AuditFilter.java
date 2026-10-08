package ai.wanaku.backend.audit;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import org.jboss.resteasy.reactive.server.SimpleResourceInfo;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.core.services.api.DataStoreRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Records one audit event for each request to a method annotated with {@link Audited}.
 * <p>
 * The filter runs after the outcome is known, so it also records requests that an exception mapper turned
 * into an error response. It records metadata only: never the request or response body.
 * </p>
 */
public class AuditFilter {
    private static final Logger LOG = Logger.getLogger(AuditFilter.class);

    private static final String START = AuditFilter.class.getName() + ".start";
    private static final int MAX_TARGET_LENGTH = 256;

    @Inject
    AuditStore store;

    @Inject
    ObjectMapper mapper;

    @Inject
    AuditContext context;

    private final ConcurrentHashMap<Method, Optional<Audited>> annotations = new ConcurrentHashMap<>();

    @ServerRequestFilter
    public void start(ContainerRequestContext request) {
        request.setProperty(START, System.nanoTime());
    }

    @ServerResponseFilter
    public void record(
            ContainerRequestContext request, ContainerResponseContext response, SimpleResourceInfo resource) {
        Audited audited = audited(resource);
        if (audited == null) {
            return;
        }
        try {
            store.record(event(audited, request, response));
        } catch (RuntimeException e) {
            // Auditing must never change the management response
            LOG.errorf(
                    "Failed to build an audit event for %s: %s",
                    audited.operation(), e.getClass().getName());
        }
    }

    private AuditEvent event(Audited audited, ContainerRequestContext request, ContainerResponseContext response) {
        int status = response.getStatus();
        AuditEvent event = outcome(audited.operation(), status);
        event.setResponseStatus(status);
        event.setTargetType(audited.targetType());
        event.setTarget(bound(context.getTarget() != null ? context.getTarget() : target(audited, request, response)));
        event.setPolicyRevision(context.getPolicyRevision());

        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            event.getAttributes().put("trace_id", span.getTraceId());
        }
        event.getAttributes().put("http_method", request.getMethod());

        Long revision = revision(response);
        if (revision != null) {
            event.getAttributes().put("revision", revision.toString());
        }
        if (data(response) instanceof Integer count) {
            event.getAttributes().put("count", count.toString());
        }

        Object start = request.getProperty(START);
        if (start instanceof Long nanos) {
            event.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos));
        }
        return event;
    }

    /** Maps the HTTP status to a decision, a stable reason code and a fixed explanation. */
    static AuditEvent outcome(String operation, int status) {
        if (status < 400) {
            return AuditEvent.administrative(
                    operation, AuditEvent.DECISION_ALLOW, "completed", "The operation completed.");
        }
        return switch (status) {
            case 400, 422 ->
                AuditEvent.administrative(
                        operation,
                        AuditEvent.DECISION_REJECT_MALFORMED,
                        "invalid_request",
                        "The request is not valid.");
            case 404 ->
                AuditEvent.administrative(
                        operation, AuditEvent.DECISION_REJECT_MALFORMED, "not_found", "The target does not exist.");
            case 409 ->
                AuditEvent.administrative(
                        operation,
                        AuditEvent.DECISION_REJECT_MALFORMED,
                        "conflict",
                        "The request conflicts with the stored state.");
            default ->
                status < 500
                        ? AuditEvent.administrative(
                                operation,
                                AuditEvent.DECISION_REJECT_MALFORMED,
                                "client_error",
                                "The server rejected the request.")
                        : AuditEvent.administrative(
                                operation, AuditEvent.DECISION_ERROR, "server_error", "The operation failed.");
        };
    }

    /** Uses the id or name parameter of the request, or the configured field of the response data. */
    private String target(Audited audited, ContainerRequestContext request, ContainerResponseContext response) {
        var path = request.getUriInfo().getPathParameters();
        var query = request.getUriInfo().getQueryParameters();
        for (String name : new String[] {"id", "name"}) {
            if (path.getFirst(name) != null) return path.getFirst(name);
        }
        for (String name : new String[] {"name", "id", "labelExpression"}) {
            if (query.getFirst(name) != null) return query.getFirst(name);
        }
        Object data = data(response);
        if (data == null || data instanceof Integer) {
            return null;
        }
        JsonNode node = mapper.valueToTree(data).get(audited.targetField());
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Long revision(ContainerResponseContext response) {
        Object etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        if (etag == null) {
            return null;
        }
        try {
            return DataStoreRecord.parseEntityTag(etag.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Object data(ContainerResponseContext response) {
        return response.getEntity() instanceof WanakuResponse<?> wrapped ? wrapped.data() : null;
    }

    private static String bound(String value) {
        return value == null || value.length() <= MAX_TARGET_LENGTH ? value : value.substring(0, MAX_TARGET_LENGTH);
    }

    private Audited audited(SimpleResourceInfo resource) {
        if (resource == null || resource.getResourceClass() == null) {
            return null;
        }
        try {
            Method method = resource.getResourceClass().getMethod(resource.getMethodName(), resource.parameterTypes());
            return annotations
                    .computeIfAbsent(method, m -> Optional.ofNullable(m.getAnnotation(Audited.class)))
                    .orElse(null);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
