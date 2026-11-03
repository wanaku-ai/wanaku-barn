package ai.wanaku.cli.main.commands.cloud;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.support.WanakuPrinter;

/** Submits runtimes to a cluster that runs the Wanaku operator, and follows them until they are ready. */
public class CloudRunSupport {
    public static final int EXIT_INVALID_INPUT = 2;
    public static final int EXIT_TARGET_UNREACHABLE = 3;
    public static final int EXIT_REGISTRATION_FAILED = 4;

    /** ConfigMaps hold at most 1 MiB; keep headroom for metadata. */
    static final long MAX_INPUT_BYTES = 900L * 1024;

    static final String API_VERSION = "wanaku.ai/v1alpha1";

    /** Kubernetes image pull policies, spelled as the API expects them. */
    public enum PullPolicy {
        Always,
        IfNotPresent,
        Never
    }

    static final ResourceDefinitionContext ROUTERS = wanakuContext("WanakuRouter", "wanakurouters");

    /** Ready reasons that do not resolve without a change: stop waiting. */
    private static final Set<String> FAILED_REASONS = Set.of(
            "ValidationError",
            "ReconcileError",
            "ImagePullBackOff",
            "ErrImagePull",
            "InvalidImageName",
            "CrashLoopBackOff",
            "CreateContainerConfigError",
            "CreateContainerError",
            "RunContainerError",
            "ProgressDeadlineExceeded",
            "ForeignForward");

    private static final Set<String> REGISTRATION_REASONS =
            Set.of("NotRegistered", "ForeignForward", "ForwardUnavailable", "RouterUnreachable");

    /** A failure with the exit code to return. */
    public static class CloudException extends Exception {
        private final int exitCode;

        public CloudException(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private final KubernetesClient client;
    private final WanakuPrinter printer;
    private Duration pollInterval = Duration.ofSeconds(2);

    public CloudRunSupport(KubernetesClient client, WanakuPrinter printer) {
        this.client = client;
        this.printer = printer;
    }

    void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    static ResourceDefinitionContext wanakuContext(String kind, String plural) {
        return new ResourceDefinitionContext.Builder()
                .withGroup("wanaku.ai")
                .withVersion("v1alpha1")
                .withKind(kind)
                .withPlural(plural)
                .withNamespaced(true)
                .build();
    }

    /** Derives a Kubernetes resource name from a file name, for example orders.camel.yaml gives orders. */
    static String resourceName(String fileName) {
        String base = fileName.toLowerCase(Locale.ROOT).replaceAll("(\\.camel)?\\.(yaml|yml)$", "");
        base = base.replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        // Leave room for the -routes and -cic/-wsr suffixes in a 63 character name
        return base.length() > 50 ? base.substring(0, 50).replaceAll("-$", "") : base;
    }

    String namespace(CloudTargetConfig target) {
        if (target.getNamespace() != null) {
            return target.getNamespace();
        }
        return client.getNamespace() != null ? client.getNamespace() : "default";
    }

    /** Fails unless the router exists and reports Ready=True. */
    GenericKubernetesResource requireReadyRouter(String namespace, String router) throws CloudException {
        if (router == null) {
            throw new CloudException(
                    EXIT_INVALID_INPUT,
                    "No router is set. Use --router, or run: wanaku cloud target set --router <name>");
        }
        final GenericKubernetesResource resource;
        try {
            resource = client.genericKubernetesResources(ROUTERS)
                    .inNamespace(namespace)
                    .withName(router)
                    .get();
        } catch (KubernetesClientException e) {
            throw new CloudException(
                    EXIT_TARGET_UNREACHABLE,
                    "Cannot read WanakuRouter %s in namespace %s: %s".formatted(router, namespace, e.getMessage()));
        }
        if (resource == null) {
            throw new CloudException(
                    EXIT_TARGET_UNREACHABLE,
                    "WanakuRouter %s does not exist in namespace %s".formatted(router, namespace));
        }
        final Map<String, Object> ready = condition(resource, "Ready");
        if (ready == null || !"True".equals(ready.get("status"))) {
            throw new CloudException(
                    EXIT_TARGET_UNREACHABLE, "WanakuRouter %s is not ready: %s".formatted(router, describe(ready)));
        }
        return resource;
    }

    /**
     * Creates or updates the files ConfigMap and the runtime resource. The ConfigMap is written first, so
     * the operator never sees a resource that refers to a missing ConfigMap; it is then owned by the
     * resource, so deleting the resource also deletes it.
     */
    GenericKubernetesResource submit(
            RuntimeKind kind, String namespace, ConfigMap files, GenericKubernetesResource resource)
            throws CloudException {
        try {
            client.configMaps().inNamespace(namespace).resource(files).createOr(NonDeletingOperation::update);
            final GenericKubernetesResource submitted = client.genericKubernetesResources(kind.context())
                    .inNamespace(namespace)
                    .resource(resource)
                    .createOr(NonDeletingOperation::update);
            files.getMetadata()
                    .setOwnerReferences(List.of(new OwnerReferenceBuilder()
                            .withApiVersion(API_VERSION)
                            .withKind(kind.kind())
                            .withName(submitted.getMetadata().getName())
                            .withUid(submitted.getMetadata().getUid())
                            .build()));
            client.configMaps().inNamespace(namespace).resource(files).update();
            return submitted;
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                throw new CloudException(
                        EXIT_TARGET_UNREACHABLE,
                        "The cluster does not serve %s resources. Install or upgrade the Wanaku operator"
                                .formatted(kind.kind()));
            }
            throw new CloudException(
                    EXIT_TARGET_UNREACHABLE,
                    "Cannot submit %s %s: %s"
                            .formatted(kind.kind(), resource.getMetadata().getName(), e.getMessage()));
        }
    }

    /**
     * Polls the resource until Ready is True, a failure that needs a change appears, or the timeout
     * passes. Prints each change of the Ready condition.
     *
     * @return the exit code
     */
    int waitForReady(RuntimeKind kind, String namespace, String name, Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        String lastState = null;
        Map<String, Object> ready = null;
        while (System.nanoTime() < deadline) {
            final GenericKubernetesResource resource = client.genericKubernetesResources(kind.context())
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
            if (resource == null) {
                printer.printErrorMessage("%s %s was deleted".formatted(kind.kind(), name));
                return BaseCommand.EXIT_ERROR;
            }
            ready = condition(resource, "Ready");
            final String state = describe(ready);
            if (!Objects.equals(state, lastState)) {
                printer.printInfoMessage(state);
                lastState = state;
            }
            if (ready != null && "True".equals(ready.get("status"))) {
                printReady(kind, resource);
                return BaseCommand.EXIT_OK;
            }
            if (ready != null && FAILED_REASONS.contains(String.valueOf(ready.get("reason")))) {
                printer.printErrorMessage("%s %s failed: %s".formatted(kind.kind(), name, state));
                printPodDiagnosis(kind, namespace, name);
                printHints(kind, name);
                return exitCodeFor(ready);
            }
            Thread.sleep(pollInterval.toMillis());
        }
        printer.printErrorMessage("%s %s is not ready after %d seconds: %s"
                .formatted(kind.kind(), name, timeout.toSeconds(), describe(ready)));
        printPodDiagnosis(kind, namespace, name);
        printHints(kind, name);
        return exitCodeFor(ready);
    }

    private static int exitCodeFor(Map<String, Object> ready) {
        return ready != null && REGISTRATION_REASONS.contains(String.valueOf(ready.get("reason")))
                ? EXIT_REGISTRATION_FAILED
                : BaseCommand.EXIT_ERROR;
    }

    private void printReady(RuntimeKind kind, GenericKubernetesResource resource) {
        final String name = resource.getMetadata().getName();
        printer.printSuccessMessage("%s %s is ready".formatted(kind.kind(), name));
        final Map<String, Object> status = status(resource);
        if (status.get("forwardName") != null) {
            printer.printInfoMessage("Forward: " + status.get("forwardName"));
        }
        if (status.get("wanakuEndpoint") != null) {
            printer.printInfoMessage("MCP endpoint: " + status.get("wanakuEndpoint"));
        }
        if (status.get("mcpAddress") != null) {
            printer.printInfoMessage("In-cluster MCP address: " + status.get("mcpAddress"));
        }
        printer.printInfoMessage("To stop and remove it, run: wanaku cloud delete %s %s".formatted(kind.id(), name));
    }

    private void printHints(RuntimeKind kind, String name) {
        printer.printInfoMessage("Inspect it with: wanaku cloud status %s %s".formatted(kind.id(), name));
        printer.printInfoMessage("Read its logs with: kubectl logs deployment/%s".formatted(workloadName(kind, name)));
    }

    static String workloadName(RuntimeKind kind, String name) {
        return name + (kind == RuntimeKind.INTEGRATION ? "-cic" : "-wsr");
    }

    /**
     * Prints why the runtime pods are not ready: the waiting reason of each container (for example
     * ImagePullBackOff), the reason of its last termination, and the latest Warning event of the pod (for example a
     * failed readiness probe). The Ready condition alone only reports the replica count.
     */
    void printPodDiagnosis(RuntimeKind kind, String namespace, String name) {
        final String workload = workloadName(kind, name);
        final List<Pod> pods;
        final List<Event> events;
        try {
            pods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("app", workload)
                    .list()
                    .getItems();
            events = client.v1().events().inNamespace(namespace).list().getItems();
        } catch (KubernetesClientException e) {
            printer.printWarningMessage("Cannot read the pods of %s: %s".formatted(workload, e.getMessage()));
            return;
        }
        if (pods.isEmpty()) {
            printer.printWarningMessage("No pod of %s exists yet".formatted(workload));
            return;
        }
        for (Pod pod : pods) {
            final String podName = pod.getMetadata().getName();
            final List<ContainerStatus> statuses =
                    pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
                            ? pod.getStatus().getContainerStatuses()
                            : List.of();
            for (ContainerStatus status : statuses) {
                if (status.getState() != null && status.getState().getWaiting() != null) {
                    printer.printWarningMessage("Pod %s: %s"
                            .formatted(
                                    podName,
                                    reasonAndMessage(
                                            status.getState().getWaiting().getReason(),
                                            status.getState().getWaiting().getMessage())));
                }
                if (status.getLastState() != null && status.getLastState().getTerminated() != null) {
                    printer.printWarningMessage("Pod %s last stopped: %s (exit code %d)"
                            .formatted(
                                    podName,
                                    reasonAndMessage(
                                            status.getLastState()
                                                    .getTerminated()
                                                    .getReason(),
                                            status.getLastState()
                                                    .getTerminated()
                                                    .getMessage()),
                                    status.getLastState().getTerminated().getExitCode()));
                }
            }
            events.stream()
                    .filter(e -> "Warning".equals(e.getType())
                            && e.getInvolvedObject() != null
                            && podName.equals(e.getInvolvedObject().getName()))
                    .max(Comparator.comparing(CloudRunSupport::eventTime))
                    .ifPresent(e -> printer.printWarningMessage(
                            "Pod %s: %s".formatted(podName, reasonAndMessage(e.getReason(), e.getMessage()))));
        }
    }

    private static String eventTime(Event event) {
        if (event.getLastTimestamp() != null) {
            return event.getLastTimestamp();
        }
        if (event.getEventTime() != null && event.getEventTime().getTime() != null) {
            return event.getEventTime().getTime();
        }
        return Objects.requireNonNullElse(event.getMetadata().getCreationTimestamp(), "");
    }

    private static String reasonAndMessage(String reason, String message) {
        if (message == null || message.isBlank()) {
            return String.valueOf(reason);
        }
        return "%s: %s".formatted(reason, message.strip());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> status(GenericKubernetesResource resource) {
        final Object status = resource.getAdditionalProperties().get("status");
        return status instanceof Map ? (Map<String, Object>) status : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> conditions(GenericKubernetesResource resource) {
        final Object conditions = status(resource).get("conditions");
        return conditions instanceof List ? (List<Map<String, Object>>) conditions : List.of();
    }

    static Map<String, Object> condition(GenericKubernetesResource resource, String type) {
        return conditions(resource).stream()
                .filter(c -> type.equals(c.get("type")))
                .findFirst()
                .orElse(null);
    }

    static String describe(Map<String, Object> condition) {
        if (condition == null) {
            return "waiting for the operator to report status";
        }
        return "Ready=%s (%s): %s"
                .formatted(condition.get("status"), condition.get("reason"), condition.get("message"));
    }
}
