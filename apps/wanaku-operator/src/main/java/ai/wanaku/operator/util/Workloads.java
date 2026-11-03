package ai.wanaku.operator.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;

/** Derives availability conditions from a Deployment and the state of its pods. */
public final class Workloads {
    private static final Set<String> FAILURE_REASONS = Set.of(
            "ImagePullBackOff",
            "ErrImagePull",
            "InvalidImageName",
            "CrashLoopBackOff",
            "CreateContainerConfigError",
            "CreateContainerError",
            "RunContainerError");

    private Workloads() {}

    /** The availability of a Deployment, with a reason and message for a condition. */
    public static final class WorkloadState {
        public final boolean available;
        public final String reason;
        public final String message;

        WorkloadState(boolean available, String reason, String message) {
            this.available = available;
            this.reason = reason;
            this.message = message;
        }
    }

    public static WorkloadState state(KubernetesClient kubernetesClient, Deployment deployment, String namespace) {
        if (deployment == null) {
            return new WorkloadState(false, "NotFound", "the Deployment does not exist");
        }
        final DeploymentStatus status =
                deployment.getStatus() != null ? deployment.getStatus() : new DeploymentStatus();
        final int desired =
                Optional.ofNullable(deployment.getSpec().getReplicas()).orElse(1);
        final int available = Optional.ofNullable(status.getAvailableReplicas()).orElse(0);
        final long generation =
                Optional.ofNullable(deployment.getMetadata().getGeneration()).orElse(0L);
        final boolean observed = status.getObservedGeneration() != null && status.getObservedGeneration() >= generation;
        final int updated = Optional.ofNullable(status.getUpdatedReplicas()).orElse(0);
        if (observed
                && updated >= desired
                && available >= desired
                && hasDeploymentCondition(status, "Available", "True")) {
            return new WorkloadState(true, "Available", "%d/%d replicas available".formatted(available, desired));
        }
        final WorkloadState podFailure = podFailure(kubernetesClient, deployment, namespace);
        if (podFailure != null) {
            return podFailure;
        }
        for (DeploymentCondition condition : nullSafe(status.getConditions())) {
            if ("Progressing".equals(condition.getType()) && "ProgressDeadlineExceeded".equals(condition.getReason())) {
                return new WorkloadState(false, "ProgressDeadlineExceeded", condition.getMessage());
            }
        }
        return new WorkloadState(false, "Progressing", "%d/%d replicas available".formatted(available, desired));
    }

    private static WorkloadState podFailure(
            KubernetesClient kubernetesClient, Deployment deployment, String namespace) {
        final List<Pod> pods = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withLabels(deployment.getSpec().getSelector().getMatchLabels())
                .list()
                .getItems();
        for (Pod pod : nullSafe(pods)) {
            if (pod.getStatus() == null) {
                continue;
            }
            for (PodCondition condition : nullSafe(pod.getStatus().getConditions())) {
                if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())) {
                    return new WorkloadState(
                            false,
                            Optional.ofNullable(condition.getReason()).orElse("Unschedulable"),
                            condition.getMessage());
                }
            }
            final List<ContainerStatus> statuses =
                    new ArrayList<>(nullSafe(pod.getStatus().getInitContainerStatuses()));
            statuses.addAll(nullSafe(pod.getStatus().getContainerStatuses()));
            for (ContainerStatus containerStatus : statuses) {
                ContainerStateWaiting waiting = containerStatus.getState() != null
                        ? containerStatus.getState().getWaiting()
                        : null;
                if (waiting != null && FAILURE_REASONS.contains(waiting.getReason())) {
                    return new WorkloadState(false, waiting.getReason(), containerMessage(containerStatus, waiting));
                }
            }
        }
        return null;
    }

    private static String containerMessage(ContainerStatus containerStatus, ContainerStateWaiting waiting) {
        String message = "container %s: %s"
                .formatted(
                        containerStatus.getName(),
                        Optional.ofNullable(waiting.getMessage()).orElse(waiting.getReason()));
        if (containerStatus.getLastState() != null
                && containerStatus.getLastState().getTerminated() != null
                && containerStatus.getLastState().getTerminated().getMessage() != null) {
            message += "; last termination: "
                    + containerStatus.getLastState().getTerminated().getMessage();
        }
        return message;
    }

    private static boolean hasDeploymentCondition(DeploymentStatus status, String type, String value) {
        return nullSafe(status.getConditions()).stream()
                .anyMatch(c -> type.equals(c.getType()) && value.equals(c.getStatus()));
    }

    static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }
}
