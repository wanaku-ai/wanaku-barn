package ai.wanaku.operator.util;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.wanaku.WanakuRouter;
import ai.wanaku.operator.wanaku.WanakuTypes;

/** Reports whether a runtime registered its MCP forward with the router. */
public final class RuntimeRegistration {
    public static final String REGISTERED_CONDITION = "Registered";

    private RuntimeRegistration() {}

    /**
     * Builds the Registered condition from the forward that the router reports.
     *
     * @param ownerLabel the label that the runtime sets on forwards it registers (its instance id)
     * @param available whether the runtime pod is available; the forward is checked only then
     */
    public static Condition condition(
            WanakuForwards.Lookup forwards,
            String managementUrl,
            String forwardName,
            String token,
            String ownerLabel,
            boolean available,
            Long generation,
            List<Condition> previous) {
        final Condition previousCondition = OperatorUtil.findCondition(previous, REGISTERED_CONDITION);
        if (!available) {
            return OperatorUtil.condition(
                    REGISTERED_CONDITION,
                    false,
                    "WaitingForRuntime",
                    "The runtime registers forward %s once it is ready".formatted(forwardName),
                    generation,
                    previousCondition);
        }
        boolean registered = false;
        String reason;
        String message;
        try {
            final WanakuForwards.Forward forward = forwards.get(managementUrl, forwardName, token);
            if (forward == null) {
                reason = "NotRegistered";
                message = "The router has no forward %s".formatted(forwardName);
            } else if (!forward.labels.containsKey(ownerLabel)) {
                reason = "ForeignForward";
                message = "Forward %s has no %s label: the runtime did not register it"
                        .formatted(forwardName, ownerLabel);
            } else if (!forward.available) {
                reason = "ForwardUnavailable";
                message = "Forward %s is unavailable: %s".formatted(forwardName, forward.statusMessage);
            } else {
                registered = true;
                reason = "Registered";
                message = "Forward %s is registered and available".formatted(forwardName);
            }
        } catch (IOException e) {
            reason = "RouterUnreachable";
            message = "Cannot read forward %s from %s: %s".formatted(forwardName, managementUrl, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading forward " + forwardName, e);
        }
        return OperatorUtil.condition(REGISTERED_CONDITION, registered, reason, message, generation, previousCondition);
    }

    /** Reads a token from a Secret key (default key {@code token}); null when the reference is unset or missing. */
    public static String token(KubernetesClient client, String namespace, WanakuTypes.SecretKeyRef ref) {
        if (ref == null || StringHelper.isBlank(ref.getName())) {
            return null;
        }
        final Secret secret =
                client.secrets().inNamespace(namespace).withName(ref.getName()).get();
        final String key = StringHelper.isNotEmpty(ref.getKey()) ? ref.getKey() : "token";
        if (secret == null) {
            return null;
        }
        if (secret.getData() != null && secret.getData().get(key) != null) {
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8).trim();
        }
        return secret.getStringData() != null ? secret.getStringData().get(key) : null;
    }

    /** The router MCP endpoint for a Wanaku namespace, when the router exposes MCP. */
    public static String wanakuEndpoint(WanakuRouter router, String forwardNamespace) {
        final Map<String, String> endpoints =
                router.getStatus() != null ? router.getStatus().getEndpoints() : null;
        final String mcp = endpoints != null ? endpoints.get("mcp") : null;
        if (mcp == null) {
            return null;
        }
        return URI.create(mcp).resolve("/" + forwardNamespace + "/mcp").toString();
    }

    /** Fails when a referenced Secret is missing; returns the error message or null. */
    public static String missingSecret(KubernetesClient client, String namespace, WanakuTypes.SecretKeyRef... refs) {
        for (WanakuTypes.SecretKeyRef ref : refs) {
            if (ref != null
                    && StringHelper.isNotEmpty(ref.getName())
                    && client.secrets()
                                    .inNamespace(namespace)
                                    .withName(ref.getName())
                                    .get()
                            == null) {
                return "Secret %s does not exist".formatted(ref.getName());
            }
        }
        return null;
    }
}
