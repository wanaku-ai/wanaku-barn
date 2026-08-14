package ai.wanaku.core.services.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import ai.wanaku.capabilities.sdk.api.types.ToolReference;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

@Path("/api/v1/tools")
public interface ToolsService {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<List<ToolReference>> list(@QueryParam("labelFilter") String labelFilter);

    default WanakuResponse<List<ToolReference>> list() {
        return list(null);
    }

    @Path("/{name}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<ToolReference> getByName(@PathParam("name") String name);
}
