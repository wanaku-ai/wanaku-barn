package ai.wanaku.backend.audit;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * Read-only access to the administrative audit trail.
 * Base path: /api/v1/audit
 */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditStore store;

    /**
     * Lists audit events, newest first.
     * {@code GET /api/v1/audit/events?operation=...&decision=...&offset=0&limit=100}
     *
     * @return one page of events
     */
    @GET
    @Path("/events")
    public WanakuResponse<AuditPage> list(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("operation") String operation,
            @QueryParam("target_type") String targetType,
            @QueryParam("target") String target,
            @QueryParam("decision") String decision,
            @QueryParam("reason_code") String reasonCode,
            @QueryParam("actor") String actor,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return new WanakuResponse<>(store.query(new AuditFilterCriteria(
                from, to, operation, targetType, target, decision, reasonCode, actor, offset, limit)));
    }

    /**
     * Returns one audit event.
     *
     * @param id the event identifier
     * @return the event
     */
    @GET
    @Path("/events/{id}")
    public WanakuResponse<AuditEvent> get(@PathParam("id") String id) {
        AuditEvent event = store.get(id);
        if (event == null) {
            throw new NotFoundException("Audit event not found");
        }
        return new WanakuResponse<>(event);
    }

    /**
     * Returns the audit schema version.
     *
     * @return the schema version
     */
    @GET
    @Path("/schema")
    public WanakuResponse<Map<String, String>> schema() {
        return new WanakuResponse<>(Map.of("schema_version", AuditEvent.SCHEMA_VERSION));
    }

    /**
     * Returns the health of the audit store.
     *
     * @return the health
     */
    @GET
    @Path("/health")
    public WanakuResponse<AuditHealth> health() {
        return new WanakuResponse<>(store.health());
    }
}
