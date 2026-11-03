package ai.wanaku.cli.main.commands.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import ai.wanaku.core.util.WanakuHome;

/**
 * The stored cloud target: the Kubernetes context and namespace, and the WanakuRouter that runtimes
 * register with. It lives in {@code $WANAKU_HOME/cloud.properties}.
 */
public final class CloudTargetConfig {
    static final String CONTEXT = "kube.context";
    static final String NAMESPACE = "kube.namespace";
    static final String ROUTER = "router";

    private String context;
    private String namespace;
    private String router;

    public static Path defaultPath() {
        return Path.of(WanakuHome.get(), "cloud.properties");
    }

    /** Loads the stored target; a missing file gives an empty target. */
    public static CloudTargetConfig load(Path path) throws IOException {
        final CloudTargetConfig config = new CloudTargetConfig();
        if (Files.exists(path)) {
            final Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
            }
            config.context = properties.getProperty(CONTEXT);
            config.namespace = properties.getProperty(NAMESPACE);
            config.router = properties.getProperty(ROUTER);
        }
        return config;
    }

    public void save(Path path) throws IOException {
        final Properties properties = new Properties();
        if (context != null) {
            properties.setProperty(CONTEXT, context);
        }
        if (namespace != null) {
            properties.setProperty(NAMESPACE, namespace);
        }
        if (router != null) {
            properties.setProperty(ROUTER, router);
        }
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            properties.store(out, "Wanaku cloud target");
        }
    }

    /** Returns this target with the non-null overrides applied. */
    public CloudTargetConfig withOverrides(String context, String namespace, String router) {
        final CloudTargetConfig merged = new CloudTargetConfig();
        merged.context = context != null ? context : this.context;
        merged.namespace = namespace != null ? namespace : this.namespace;
        merged.router = router != null ? router : this.router;
        return merged;
    }

    /** The Kubernetes context; null means the current context of the kubeconfig. */
    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    /** The namespace; null means the namespace of the Kubernetes context. */
    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getRouter() {
        return router;
    }

    public void setRouter(String router) {
        this.router = router;
    }
}
