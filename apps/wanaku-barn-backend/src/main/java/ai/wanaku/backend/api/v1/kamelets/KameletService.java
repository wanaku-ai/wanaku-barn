package ai.wanaku.backend.api.v1.kamelets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import ai.wanaku.backend.api.v1.kamelets.model.KameletDefinition;
import ai.wanaku.backend.api.v1.kamelets.model.KameletSummary;
import ai.wanaku.backend.api.v1.kamelets.model.KameletUpload;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/** Contract for a remote native Kamelet catalog. Uploads never execute integrations. */
@Path("/api/v1/kamelets")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Kamelets", description = "Store and serve native Kamelets without running them")
public interface KameletService {
    /** Returns current catalog metadata.
     * @return current bundled, configured, and uploaded Kamelets */
    @GET
    @Operation(
            summary = "List current Kamelets",
            description =
                    "List native Kamelets and their semantic eligibility. Uploaded revisions are available immediately.")
    @APIResponse(responseCode = "200", description = "Current Kamelet metadata")
    WanakuResponse<List<KameletSummary>> list();

    /** Uploads a native definition.
     * @param upload original YAML
     * @return selected revision metadata */
    @POST
    @Operation(
            summary = "Upload a Kamelet",
            description =
                    "Validate and persist original YAML as an immutable SHA-256 revision. Select the uploaded revision for its name. Identical uploads are idempotent. This operation does not execute YAML or contact external services.")
    @APIResponse(responseCode = "200", description = "Uploaded revision selected")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "409", description = "Name is reserved by a bundled or configured Kamelet")
    @APIResponse(responseCode = "422", description = "YAML is invalid or exceeds 1 MiB")
    WanakuResponse<KameletSummary> upload(@Valid KameletUpload upload);

    /** Reads original YAML with metadata.
     * @param name native name
     * @param sha256 optional exact revision
     * @return definition */
    @GET
    @Path("/{name}")
    @Operation(
            summary = "Read a Kamelet",
            description =
                    "Read the current definition or an exact immutable revision. Removed current selections do not remove retained revisions.")
    @APIResponse(responseCode = "200", description = "Original definition with metadata")
    @APIResponse(responseCode = "400", description = "Invalid name or digest")
    @APIResponse(responseCode = "404", description = "Kamelet revision not found")
    WanakuResponse<KameletDefinition> get(
            @PathParam("name") String name, @QueryParam("sha256") @Pattern(regexp = "[a-f0-9]{64}") String sha256);

    /** Serves exact YAML for Camel location lookup.
     * @param name native name
     * @param sha256 optional exact revision
     * @return raw YAML with a digest ETag */
    @GET
    @Path("/{name}.kamelet.yaml")
    @Produces("application/yaml")
    @Operation(
            summary = "Download native Kamelet YAML",
            description =
                    "Return original UTF-8 YAML with a SHA-256 ETag. The filename path supports Camel Kamelet resource lookup.")
    @APIResponse(
            responseCode = "200",
            description = "Original application/yaml bytes",
            content = @Content(mediaType = "application/yaml", schema = @Schema(type = SchemaType.STRING)),
            headers =
                    @Header(
                            name = "ETag",
                            description = "Quoted SHA-256 of the original UTF-8 bytes",
                            schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", description = "Invalid name or digest")
    @APIResponse(responseCode = "404", description = "Kamelet revision not found")
    Response download(
            @PathParam("name") String name, @QueryParam("sha256") @Pattern(regexp = "[a-f0-9]{64}") String sha256);

    /** Removes a current uploaded selection.
     * @param name native name
     * @return empty success response */
    @DELETE
    @Path("/{name}")
    @Operation(
            summary = "Remove a current Kamelet selection",
            description =
                    "Remove an uploaded Kamelet from the current catalog. Retain immutable revisions for saved semantic routes. Bundled and configured definitions cannot be removed.")
    @APIResponse(responseCode = "200", description = "Current uploaded selection removed")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Current Kamelet not found")
    @APIResponse(responseCode = "409", description = "Bundled or configured Kamelet cannot be removed")
    WanakuResponse<Void> remove(@PathParam("name") String name);
}
