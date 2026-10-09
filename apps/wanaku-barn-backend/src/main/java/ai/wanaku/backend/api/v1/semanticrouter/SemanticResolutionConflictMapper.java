package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/** Maps ambiguous publication resolution to the existing API error envelope. */
@Provider
public class SemanticResolutionConflictMapper implements ExceptionMapper<SemanticResolutionConflictException> {
    private static final Logger LOG = Logger.getLogger(SemanticResolutionConflictMapper.class);

    /** Returns HTTP 409 with a publication selection error. */
    @Override
    public Response toResponse(SemanticResolutionConflictException exception) {
        LOG.warn(exception.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(new WanakuResponse<Void>(exception.getMessage()))
                .build();
    }
}
