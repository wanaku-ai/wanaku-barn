package ai.wanaku.operator.util;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;

import static ai.wanaku.operator.assertions.OperatorAssertions.assertCondition;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperatorUtilTest {

    @Test
    void resolveImagePullPolicyUsesComponentPolicyWhenProvided() {
        assertEquals("Always", OperatorUtil.resolveImagePullPolicy("Always", "Never"));
    }

    @Test
    void resolveImagePullPolicyFallsBackToGlobalWhenComponentNull() {
        assertEquals("Never", OperatorUtil.resolveImagePullPolicy(null, "Never"));
    }

    @Test
    void resolveImagePullPolicyUsesDefaultWhenBothNull() {
        assertEquals("IfNotPresent", OperatorUtil.resolveImagePullPolicy(null, null));
    }

    @Test
    void resolveImagePullPolicyUsesDefaultForInvalidPolicy() {
        assertEquals("IfNotPresent", OperatorUtil.resolveImagePullPolicy("InvalidPolicy", null));
    }

    @Test
    void resolveImagePullPolicyUsesDefaultForInvalidGlobalPolicy() {
        assertEquals("IfNotPresent", OperatorUtil.resolveImagePullPolicy(null, "BadValue"));
    }

    @Test
    void resolveImagePullPolicyAcceptsAllValidValues() {
        assertEquals("Always", OperatorUtil.resolveImagePullPolicy("Always", null));
        assertEquals("IfNotPresent", OperatorUtil.resolveImagePullPolicy("IfNotPresent", null));
        assertEquals("Never", OperatorUtil.resolveImagePullPolicy("Never", null));
    }

    @Test
    void readyConditionReusesTransitionTimeWhenAlreadyReady() {
        Condition previous = new ConditionBuilder()
                .withType(OperatorUtil.READY_CONDITION)
                .withStatus(OperatorUtil.CONDITION_STATUS_TRUE)
                .withLastTransitionTime("2024-01-01T00:00:00Z")
                .build();

        Condition current = OperatorUtil.readyCondition(7L, previous, "ready");

        assertEquals("2024-01-01T00:00:00Z", current.getLastTransitionTime());
        assertCondition(current, OperatorUtil.READY_CONDITION, OperatorUtil.CONDITION_STATUS_TRUE, 7L);
    }

    @Test
    void findConditionReturnsNullWhenNoMatchExists() {
        assertNull(OperatorUtil.findCondition(null, OperatorUtil.READY_CONDITION));
    }

    @Test
    void validateImageAllowedAllowsAnyImageWhenAllowlistEmpty() {
        assertDoesNotThrow(() -> OperatorUtil.validateImageAllowed("docker.io/library/anything:latest", ""));
        assertDoesNotThrow(() -> OperatorUtil.validateImageAllowed("docker.io/library/anything:latest", "  "));
    }

    @Test
    void validateImageAllowedAcceptsImageMatchingAllowlistedPrefix() {
        assertDoesNotThrow(() ->
                OperatorUtil.validateImageAllowed("quay.io/wanaku/wanaku-tool-service-http:latest", "quay.io/wanaku/"));
        assertDoesNotThrow(() ->
                OperatorUtil.validateImageAllowed("registry.internal/team/x:1", "quay.io/wanaku/, registry.internal/"));
    }

    @Test
    void validateImageAllowedRejectsImageOutsideAllowlist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OperatorUtil.validateImageAllowed("docker.io/evil/payload:latest", "quay.io/wanaku/"));
    }

    @Test
    void validateImageAllowedSkipsBlankImage() {
        assertDoesNotThrow(() -> OperatorUtil.validateImageAllowed(null, "quay.io/wanaku/"));
        assertDoesNotThrow(() -> OperatorUtil.validateImageAllowed("", "quay.io/wanaku/"));
    }
}
