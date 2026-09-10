package ai.wanaku.core.services.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * Service interface for Agent Builder operations via REST API.
 * The Agent Builder generates a Service Catalog (or Service Template) package containing
 * a ready-to-run AI agent wired to the selected tools and resources, backed by the
 * configured LLM provider.
 */
@Path("/api/v1/agent-builder")
public interface AgentBuilderService {

    /**
     * Build an agent from the given request and deploy the generated package.
     * Depending on {@link AgentBuildRequest#isDeployAsTemplate()}, the package is deployed either
     * as a service catalog or as a service template.
     *
     * @param request the agent build request describing identity, LLM configuration, tools, resources and memory
     * @return response with the created data store entry containing the Base64-encoded ZIP
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<DataStore> build(AgentBuildRequest request);

    /**
     * List the LLM providers supported by the agent builder.
     *
     * @return response with the list of supported providers and their metadata
     */
    @Path("/providers")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    WanakuResponse<List<LlmProviderInfo>> listProviders();
}
