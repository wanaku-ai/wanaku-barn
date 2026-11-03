package ai.wanaku.core.services.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * JAX-RS client interface for the router's evaluators management API.
 * <p>
 * Evaluators are WASM- and LLM-backed governance processors that the router runs on MCP traffic.
 * Every configuration change creates an immutable revision, and a prior revision can be activated
 * again to roll back.
 * <p>
 * All endpoints are available under the {@code /api/v1/evaluators} base path.
 */
@Path("/api/v1/evaluators")
public interface EvaluatorsService {

    /**
     * Replaces the active evaluator configuration with a new revision.
     *
     * @param request the evaluator definitions and an optional expected active revision
     * @return a {@link WanakuResponse} containing the new active revision
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<EvaluatorRevision> update(EvaluatorsUpdateRequest request);

    /**
     * Lists the revision history, newest first.
     *
     * @return a {@link WanakuResponse} containing the revision metadata
     */
    @GET
    @Path("/revisions")
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<List<EvaluatorRevisionMetadata>> listRevisions();

    /**
     * Retrieves the active revision.
     *
     * @return a {@link WanakuResponse} containing the active revision
     */
    @GET
    @Path("/revisions/active")
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<EvaluatorRevision> activeRevision();

    /**
     * Retrieves a specific revision.
     *
     * @param id the revision ID
     * @return a {@link WanakuResponse} containing the revision
     */
    @GET
    @Path("/revisions/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<EvaluatorRevision> getRevision(@PathParam("id") long id);

    /**
     * Activates the configuration of a prior revision as a new revision.
     *
     * @param id the revision ID to activate
     * @param request an optional {@code expected_revision} entry for optimistic concurrency
     * @return a {@link WanakuResponse} containing the new active revision
     */
    @POST
    @Path("/revisions/{id}/activate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<EvaluatorRevision> activateRevision(@PathParam("id") long id, Map<String, Long> request);
}
