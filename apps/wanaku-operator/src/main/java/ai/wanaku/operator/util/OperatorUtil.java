package ai.wanaku.operator.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.Serialization;
import ai.wanaku.operator.wanaku.WanakuTypes;

/**
 * Shared operator utilities for condition management, image pull policy resolution,
 * and router URL construction.
 */
public final class OperatorUtil {
    private static final Logger LOG = Logger.getLogger(OperatorUtil.class);
    public static final String READY_CONDITION = "Ready";
    public static final String CONDITION_STATUS_TRUE = "True";
    public static final String CONDITION_STATUS_FALSE = "False";
    public static final String CONDITION_REASON_READY = "ReconciliationSucceeded";

    /** Label set on every workload the operator manages; used to scope the operator's informers. */
    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";

    public static final String MANAGED_BY_VALUE = "wanaku-operator";
    public static final String DESIRED_HASH_ANNOTATION = "wanaku.ai/desired-hash";

    public static final String DEFAULT_PULL_POLICY = "IfNotPresent";
    public static final Set<String> VALID_PULL_POLICIES = Set.of("Always", "IfNotPresent", "Never");

    /** Optional comma-separated allowlist of permitted container-image prefixes. */
    public static final String ALLOWED_IMAGE_PREFIXES = "wanaku.operator.allowed-image-prefixes";

    private OperatorUtil() {}

    /**
     * Validates a custom-resource-supplied container image against the optional allowlist
     * {@value #ALLOWED_IMAGE_PREFIXES} (comma-separated registry/repository prefixes). When the
     * allowlist is empty (the default) any image is permitted; when configured, the image must
     * start with one of the prefixes, otherwise reconciliation fails. This prevents a principal
     * able to create/patch a the {@code WanakuRouter} from scheduling an
     * arbitrary, untrusted image.
     *
     * @param image the image reference from the custom resource (may be null or blank)
     * @throws IllegalArgumentException if the image is not in the configured allowlist
     */
    public static void validateImageAllowed(String image) {
        String allowlist = ConfigProvider.getConfig()
                .getOptionalValue(ALLOWED_IMAGE_PREFIXES, String.class)
                .orElse("");
        validateImageAllowed(image, allowlist);
    }

    /**
     * Package-private variant taking the allowlist explicitly, so the matching logic can be unit
     * tested without configuration.
     *
     * @param image the image reference from the custom resource (may be null or blank)
     * @param allowlist comma-separated allowed prefixes (empty disables the check)
     * @throws IllegalArgumentException if the image is not in the allowlist
     */
    static void validateImageAllowed(String image, String allowlist) {
        if (allowlist == null || allowlist.isBlank() || image == null || image.isBlank()) {
            return;
        }
        boolean allowed = Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .anyMatch(image::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Container image '%s' is not in the allowed registries (%s)".formatted(image, allowlist));
        }
    }

    /**
     * Creates a Ready condition for a custom resource status.
     *
     * @param generation the observed generation
     * @param previousCondition the previous condition (may be null)
     * @param message the status message
     * @return a new Ready condition
     */
    public static Condition readyCondition(Long generation, Condition previousCondition, String message) {
        return condition(READY_CONDITION, true, CONDITION_REASON_READY, message, generation, previousCondition);
    }

    /**
     * Creates a condition, keeping the previous transition time when the status did not change.
     *
     * @param type the condition type
     * @param status the condition status
     * @param reason the machine-readable reason
     * @param message the human-readable message
     * @param generation the observed generation
     * @param previousCondition the previous condition of the same type (may be null)
     * @return the new condition
     */
    public static Condition condition(
            String type, boolean status, String reason, String message, Long generation, Condition previousCondition) {
        final String statusValue = status ? CONDITION_STATUS_TRUE : CONDITION_STATUS_FALSE;
        final boolean unchanged = previousCondition != null
                && statusValue.equals(previousCondition.getStatus())
                && previousCondition.getLastTransitionTime() != null;
        final String lastTransitionTime = unchanged ? previousCondition.getLastTransitionTime() : now();

        return new ConditionBuilder()
                .withType(type)
                .withStatus(statusValue)
                .withObservedGeneration(generation)
                .withLastTransitionTime(lastTransitionTime)
                .withReason(reason)
                .withMessage(message)
                .build();
    }

    /**
     * Converts an environment variable of a custom resource into a container environment variable. A variable
     * with {@code valueFrom.secretKeyRef} reads its value from that Secret key.
     *
     * @param env the environment variable from the custom resource
     * @return the container environment variable
     */
    public static EnvVar toEnvVar(WanakuTypes.EnvVar env) {
        final WanakuTypes.SecretKeyRef secret =
                env.getValueFrom() != null ? env.getValueFrom().getSecretKeyRef() : null;
        if (secret == null) {
            return new EnvVarBuilder()
                    .withName(env.getName())
                    .withValue(env.getValue())
                    .build();
        }
        return new EnvVarBuilder()
                .withName(env.getName())
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secret.getName())
                .withKey(secret.getKey())
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    /**
     * Returns the current time as an RFC 3339 timestamp with second precision, as Kubernetes expects in
     * condition and event times.
     *
     * @return the current UTC time, for example {@code 2026-10-15T09:13:00Z}
     */
    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Builds the Ready condition: True when every given condition is True, otherwise False with the reason
     * and message of the first condition that is not True.
     */
    public static Condition aggregateReady(List<Condition> conditions, Long generation, List<Condition> previous) {
        final Condition previousReady = findCondition(previous, READY_CONDITION);
        for (Condition condition : conditions) {
            if (!isTrue(condition)) {
                return condition(
                        READY_CONDITION,
                        false,
                        condition.getReason(),
                        condition.getType() + ": " + condition.getMessage(),
                        generation,
                        previousReady);
            }
        }
        return readyCondition(generation, previousReady, "All required components are available");
    }

    public static boolean isTrue(Condition condition) {
        return condition != null && CONDITION_STATUS_TRUE.equals(condition.getStatus());
    }

    /**
     * Stores a hash of the desired resource spec in an annotation, so drift in any field can be detected
     * by comparing it with the annotation of the existing resource.
     *
     * @param desired the desired resource
     * @param spec the desired spec to hash
     */
    public static void stampDesiredHash(HasMetadata desired, Object spec) {
        final Map<String, String> annotations = new HashMap<>();
        if (desired.getMetadata().getAnnotations() != null) {
            annotations.putAll(desired.getMetadata().getAnnotations());
        }
        annotations.put(DESIRED_HASH_ANNOTATION, sha256(Serialization.asJson(spec)));
        desired.getMetadata().setAnnotations(annotations);
    }

    /**
     * Checks whether the existing resource carries the same desired-spec hash as the desired one.
     *
     * @param desired the desired resource (may be unstamped, which counts as a match)
     * @param existing the existing resource
     * @return true when the hashes match or the desired resource has no hash
     */
    public static boolean sameDesiredHash(HasMetadata desired, HasMetadata existing) {
        final String desiredHash = annotation(desired, DESIRED_HASH_ANNOTATION);
        return desiredHash == null || desiredHash.equals(annotation(existing, DESIRED_HASH_ANNOTATION));
    }

    private static String annotation(HasMetadata resource, String key) {
        if (resource.getMetadata() == null || resource.getMetadata().getAnnotations() == null) {
            return null;
        }
        return resource.getMetadata().getAnnotations().get(key);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Finds a condition by type in a list of conditions.
     *
     * @param conditions the list of conditions (may be null or empty)
     * @param type the condition type to find
     * @return the matching condition, or null if not found
     */
    public static Condition findCondition(List<Condition> conditions, String type) {
        if (conditions == null || conditions.isEmpty() || type == null) {
            return null;
        }

        return conditions.stream()
                .filter(condition -> type.equals(condition.getType()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolves image pull policy with priority:
     * 1. Component-specific policy (router.imagePullPolicy or server.imagePullPolicy)
     * 2. Global policy (spec.imagePullPolicy)
     * 3. Default (IfNotPresent)
     *
     * @param componentPolicy the component-specific policy (may be null)
     * @param globalPolicy the global policy from spec (may be null)
     * @return the resolved policy, validated against allowed values
     */
    public static String resolveImagePullPolicy(String componentPolicy, String globalPolicy) {
        String resolved =
                componentPolicy != null ? componentPolicy : globalPolicy != null ? globalPolicy : DEFAULT_PULL_POLICY;

        if (!VALID_PULL_POLICIES.contains(resolved)) {
            LOG.warnf("Invalid imagePullPolicy '%s', using default '%s'", resolved, DEFAULT_PULL_POLICY);
            return DEFAULT_PULL_POLICY;
        }

        return resolved;
    }

    /**
     * Constructs the internal base URL for a router.
     *
     * @param routerRef the router reference name
     * @return the base URL in the format "http://barn-{routerRef}:8080"
     */
    public static String getRouterBaseUrl(String routerRef) {
        return "http://barn-" + routerRef + ":8080";
    }
}
