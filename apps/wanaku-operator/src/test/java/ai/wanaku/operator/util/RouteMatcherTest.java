package ai.wanaku.operator.util;

import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteMatcherTest {
    @Test
    void detectsRouteTargetAndExposureChanges() {
        Route desired = new RouteBuilder()
                .withNewMetadata()
                .withName("wanaku-dev")
                .endMetadata()
                .withNewSpec()
                .withHost("wanaku.apps.example.com")
                .withPath("/")
                .withNewTo()
                .withKind("Service")
                .withName("server-wanaku-dev")
                .endTo()
                .withNewPort()
                .withNewTargetPort(8080)
                .endPort()
                .withNewTls()
                .withTermination("edge")
                .withInsecureEdgeTerminationPolicy("Redirect")
                .endTls()
                .endSpec()
                .build();
        Route existing = new RouteBuilder(desired).build();
        assertTrue(Matchers.match(desired, existing));
        existing.getSpec().getTo().setName("oauth2-proxy-wanaku-dev");
        assertFalse(Matchers.match(desired, existing));
        existing = new RouteBuilder(desired).build();
        existing.getSpec().getPort().setTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(4181));
        assertFalse(Matchers.match(desired, existing));
        existing = new RouteBuilder(desired).build();
        existing.getSpec().setHost("other.apps.example.com");
        assertFalse(Matchers.match(desired, existing));
        existing = new RouteBuilder(desired).build();
        existing.getSpec().setPath("/mcp");
        assertFalse(Matchers.match(desired, existing));
        existing = new RouteBuilder(desired).build();
        existing.getSpec().getTls().setInsecureEdgeTerminationPolicy("Allow");
        assertFalse(Matchers.match(desired, existing));
    }
}
