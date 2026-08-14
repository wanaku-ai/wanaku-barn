package ai.wanaku.core.services.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import ai.wanaku.capabilities.sdk.api.types.ResourceReference;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

@Path("/api/v1/resources")
public interface ResourcesService {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<List<ResourceReference>> list(@QueryParam("labelFilter") String labelFilter);

    default WanakuResponse<List<ResourceReference>> list() {
        return list(null);
    }

    @Path("/{name}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<ResourceReference> getByName(@PathParam("name") String name);
}
