package ai.wanaku.core.services.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * Export and import of the Barn data. The archive is kept as JSON text, so clients do not need the archive types.
 */
@Path("/api/v1/management")
public interface BackupService {

    /**
     * Exports the Barn data.
     *
     * @param includeAudit true to include the audit events
     * @return the JSON response; the archive is in the {@code data} field
     */
    @GET
    @Path("/export")
    @Produces(MediaType.APPLICATION_JSON)
    String export(@QueryParam("includeAudit") boolean includeAudit);

    /**
     * Imports an archive that an export created.
     *
     * @param replace true to replace the existing data
     * @param archive the archive as JSON text
     * @return the number of imported items by kind
     */
    @POST
    @Path("/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<Map<String, Integer>> importArchive(@QueryParam("replace") boolean replace, String archive);
}
