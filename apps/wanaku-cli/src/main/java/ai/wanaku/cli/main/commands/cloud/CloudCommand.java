package ai.wanaku.cli.main.commands.cloud;

import java.io.IOException;
import java.nio.file.Path;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import ai.wanaku.cli.main.commands.BaseCommand;
import picocli.CommandLine.Option;

/** Options shared by the cloud commands that talk to the cluster. */
public abstract class CloudCommand extends BaseCommand {
    @Option(
            names = {"--context"},
            description = "Kubernetes context (default: the stored target, then the current context)")
    String context;

    @Option(
            names = {"--namespace", "-n"},
            description = "Kubernetes namespace (default: the stored target, then the context namespace)")
    String namespace;

    @Option(
            names = {"--router"},
            description = "Name of the WanakuRouter that runtimes register with (default: the stored target)")
    String router;

    /** Set by tests; otherwise built from the kubeconfig. */
    KubernetesClient client;

    /** Set by tests; otherwise $WANAKU_HOME/cloud.properties. */
    Path targetFile;

    CloudTargetConfig target() throws IOException {
        return CloudTargetConfig.load(targetFile != null ? targetFile : CloudTargetConfig.defaultPath())
                .withOverrides(context, namespace, router);
    }

    KubernetesClient client(CloudTargetConfig target) {
        if (client == null) {
            client = new KubernetesClientBuilder()
                    .withConfig(Config.autoConfigure(target.getContext()))
                    .build();
        }
        return client;
    }
}
