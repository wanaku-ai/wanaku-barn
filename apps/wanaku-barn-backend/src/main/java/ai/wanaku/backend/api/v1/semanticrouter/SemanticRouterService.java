package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticAction;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticExpert;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreview;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreviewRequest;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticResolvedPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticValidation;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/** Contract for semantic router authoring and immutable publication. */
@Path("/api/v1/semantic-routers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Semantic routers", description = "Author, classify, and publish native Camel integrations")
public interface SemanticRouterService {
    /** List eligible curated Kamelet actions. */
    @GET
    @Path("/actions")
    @Operation(
            summary = "List eligible curated Kamelet actions",
            description =
                    "List current eligible native Kamelets. With definitionId, include exact historical revisions selected by that saved definition. The current flag identifies current catalog selections.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<List<SemanticAction>> actions(@QueryParam("definitionId") String definitionId);

    /** List configured expert instances. */
    @GET
    @Path("/experts")
    @Operation(
            summary = "List configured expert instances",
            description = "List configured expert instances. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<List<SemanticExpert>> experts();

    /** List saved router drafts. */
    @GET
    @Operation(
            summary = "List saved router drafts",
            description = "List saved router drafts. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<List<SemanticRouterDefinition>> list();

    /** Resolve an exact saved router name to its current or explicitly selected immutable publication. */
    @GET
    @Path("/resolve")
    @Operation(
            summary = "Resolve a published semantic router by name",
            description =
                    "Resolve an exact saved name to immutable publication pins and a published expert snapshot. Draft edits do not select a new publication. An explicit revision overrides the current pointer. Legacy records can omit the expert snapshot.")
    @APIResponse(responseCode = "200", description = "Published selection resolved")
    @APIResponse(responseCode = "400", description = "Name or revision is invalid")
    @APIResponse(responseCode = "404", description = "Router, publication, revision, or catalog not found")
    @APIResponse(
            responseCode = "409",
            description = "Name is ambiguous or several legacy publications have no current selection")
    @APIResponse(responseCode = "500", description = "Stored publication and archive are inconsistent")
    WanakuResponse<SemanticResolvedPublication> resolve(
            @QueryParam("name") @NotBlank @Size(max = 120) String name,
            @QueryParam("revision") @Size(min = 1, max = 64) String revision);

    /** Save a router draft. */
    @POST
    @Operation(
            summary = "Save a router draft",
            description = "Save a router draft. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<SemanticRouterDefinition> create(@Valid SemanticRouterDefinition definition);

    /** Read a router draft. */
    @GET
    @Path("/{id}")
    @Operation(
            summary = "Read a router draft",
            description = "Read a router draft. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<SemanticRouterDefinition> get(@PathParam("id") String id);

    /** Update a router draft. */
    @PUT
    @Path("/{id}")
    @Operation(
            summary = "Update a router draft",
            description = "Update a router draft. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<SemanticRouterDefinition> update(
            @PathParam("id") String id, @Valid SemanticRouterDefinition definition);

    /** Remove a router draft. */
    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a router draft",
            description = "Remove a router draft. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<Void> remove(@PathParam("id") String id);

    /** Validate configuration without inference. */
    @POST
    @Path("/validate")
    @Operation(
            summary = "Validate configuration without inference",
            description = "Validate configuration without inference. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<SemanticValidation> validate(SemanticRouterDefinition definition);

    /**
     * Generates catalog files for inspection without saving a draft, publishing, or running inference.
     *
     * @param definition the complete draft configuration to inspect
     * @return generated file paths and UTF-8 contents with preview catalog and revision placeholders
     * @throws ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException if the draft configuration is invalid
     */
    @POST
    @Path("/files")
    @Operation(
            summary = "Inspect generated semantic router files",
            description =
                    "Generate all catalog files from a complete draft. The catalog name is semantic-preview and the revision is preview. This operation does not save a draft, publish a catalog, activate a runtime, or call an expert.")
    @APIResponse(responseCode = "200", description = "Generated file paths and contents")
    @APIResponse(responseCode = "400", description = "Name or tool name is invalid")
    @APIResponse(responseCode = "422", description = "Definition is incomplete or structurally invalid")
    WanakuResponse<Map<String, String>> files(@Valid SemanticRouterDefinition definition);

    /** Classify without executing actions. */
    @POST
    @Path("/{id}/preview")
    @Operation(
            summary = "Classify without executing actions",
            description = "Classify without executing actions. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    @APIResponse(responseCode = "422", description = "Definition is invalid")
    WanakuResponse<SemanticPreview> preview(@PathParam("id") String id, @Valid SemanticPreviewRequest request);

    /** Publish an immutable catalog revision. */
    @POST
    @Path("/{id}/publish")
    @Operation(
            summary = "Publish an immutable catalog revision",
            description = "Publish an immutable catalog revision. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    @APIResponse(responseCode = "422", description = "Definition is invalid")
    WanakuResponse<SemanticPublication> publish(@PathParam("id") String id);

    /** List published revisions. */
    @GET
    @Path("/{id}/revisions")
    @Operation(
            summary = "List published revisions",
            description = "List published revisions. Publication does not activate a runtime.")
    @APIResponse(responseCode = "200", description = "Operation completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Definition not found")
    WanakuResponse<List<SemanticPublication>> revisions(@PathParam("id") String id);
}
