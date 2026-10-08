package ai.wanaku.backend.api.v1.exceptions;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/**
 * A provider of a base exception mapper that converts exceptions to standardized responses.
 * <p>
 * This is the catch-all mapper for exceptions not handled by more specific mappers.
 * It returns a generic error message to avoid leaking internal implementation details;
 * the full exception message and stack trace are logged server-side for diagnostics.
 */
@Provider
public class BaseExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger LOG = Logger.getLogger(BaseExceptionMapper.class);

    private static final String FALLBACK_MESSAGE = "Internal server error";

    @APIResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(schema = @Schema(implementation = WanakuResponse.class)))
    @Override
    public Response toResponse(Exception e) {
        // Keep the status of errors that the REST layer already classified, for example 400 for a body that is
        // not valid JSON, 404 for an unknown path or 415 for an unsupported content type
        if (e instanceof WebApplicationException wae && wae.getResponse().getStatus() < 500) {
            LOG.warnf("Request rejected: %s", e.getMessage());
            return Response.status(wae.getResponse().getStatus())
                    .entity(new WanakuResponse<Void>(
                            wae.getResponse().getStatusInfo().getReasonPhrase()))
                    .build();
        }
        LOG.error(e.getMessage(), e);

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new WanakuResponse<Void>(FALLBACK_MESSAGE))
                .build();
    }
}
