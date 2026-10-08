package ai.wanaku.backend.common;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.MDC;
import io.smallrye.common.annotation.NonBlocking;

/**
 * Test-only endpoint that reports the correlation values seen while handling a request.
 */
@Path("/test/correlation")
@Produces(MediaType.APPLICATION_JSON)
public class CorrelationProbeResource {

    @Inject
    RequestCorrelation correlation;

    @GET
    @Operation(hidden = true)
    @Path("/blocking")
    public Map<String, String> blocking() {
        return snapshot();
    }

    @GET
    @Operation(hidden = true)
    @Path("/non-blocking")
    @NonBlocking
    public Map<String, String> nonBlocking() {
        return snapshot();
    }

    private Map<String, String> snapshot() {
        Map<String, String> values = new HashMap<>();
        values.put("requestId", correlation.requestId());
        values.put("mdc", String.valueOf(MDC.get(RequestCorrelation.MDC_KEY)));
        values.put("traceId", correlation.traceId());
        return values;
    }
}
