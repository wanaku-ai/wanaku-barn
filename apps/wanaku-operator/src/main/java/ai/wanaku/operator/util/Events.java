package ai.wanaku.operator.util;

import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

/** Records Kubernetes events on custom resources. */
public final class Events {
    private static final Logger LOG = Logger.getLogger(Events.class);

    private Events() {}

    /** Records a Warning event. Events are best effort: a failure is only logged. */
    public static void warn(KubernetesClient kubernetesClient, HasMetadata resource, String reason, String message) {
        final String now = OperatorUtil.now();
        final ObjectMeta meta = resource.getMetadata();
        final Event event = new EventBuilder()
                .withNewMetadata()
                .withGenerateName(meta.getName() + "-")
                .withNamespace(meta.getNamespace())
                .endMetadata()
                .withNewInvolvedObject()
                .withApiVersion(resource.getApiVersion())
                .withKind(resource.getKind())
                .withName(meta.getName())
                .withNamespace(meta.getNamespace())
                .withUid(meta.getUid())
                .endInvolvedObject()
                .withType("Warning")
                .withReason(reason)
                .withMessage(message)
                .withFirstTimestamp(now)
                .withLastTimestamp(now)
                .withCount(1)
                .withNewSource()
                .withComponent(OperatorUtil.MANAGED_BY_VALUE)
                .endSource()
                .build();
        try {
            kubernetesClient
                    .v1()
                    .events()
                    .inNamespace(meta.getNamespace())
                    .resource(event)
                    .create();
        } catch (KubernetesClientException e) {
            // Events are best effort: the condition already carries the failure
            LOG.warnf("Unable to record event for %s '%s': %s", resource.getKind(), meta.getName(), e.getMessage());
        }
    }
}
