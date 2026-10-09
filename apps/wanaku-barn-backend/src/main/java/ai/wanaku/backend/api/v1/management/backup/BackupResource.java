package ai.wanaku.backend.api.v1.management.backup;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import ai.wanaku.backend.audit.Audited;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * Export and import of the Barn data.
 * Base path: /api/v1/management
 */
@Path("/api/v1/management")
@Produces(MediaType.APPLICATION_JSON)
public class BackupResource {

    @Inject
    BackupBean backup;

    /**
     * Exports the Barn data.
     * {@code GET /api/v1/management/export?includeAudit=true}
     *
     * @param includeAudit true (default) to include the audit events
     * @return response with the archive
     */
    @GET
    @Path("/export")
    @Audited(operation = "barn.export", targetType = "barn")
    public WanakuResponse<BarnArchive> export(@QueryParam("includeAudit") @DefaultValue("true") boolean includeAudit) {
        return new WanakuResponse<>(backup.export(includeAudit));
    }

    /**
     * Imports an archive. Without {@code replace=true}, the import requires a Barn without data other than the
     * unchanged built-in templates, and returns 409 otherwise.
     * {@code POST /api/v1/management/import?replace=false}
     *
     * @param replace true to replace the existing data
     * @param archive the archive that an export created
     * @return response with the number of imported items by kind
     */
    @POST
    @Path("/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Audited(operation = "barn.import", targetType = "barn")
    public WanakuResponse<Map<String, Integer>> importArchive(
            @QueryParam("replace") @DefaultValue("false") boolean replace, BarnArchive archive) {
        return new WanakuResponse<>(backup.importArchive(archive, replace));
    }
}
