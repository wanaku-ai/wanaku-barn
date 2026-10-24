package ai.wanaku.operator.wanaku;

import java.util.List;
import java.util.Map;

public final class WanakuTypes {

    private WanakuTypes() {}

    public static class EnvVar {
        private String name;
        private String value;
        private EnvVarSource valueFrom;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        /** Reads the value from a Secret instead of {@code value}, so that credentials stay out of the resource. */
        public EnvVarSource getValueFrom() {
            return valueFrom;
        }

        public void setValueFrom(EnvVarSource valueFrom) {
            this.valueFrom = valueFrom;
        }
    }

    public static class EnvVarSource {
        private SecretKeyRef secretKeyRef;

        public SecretKeyRef getSecretKeyRef() {
            return secretKeyRef;
        }

        public void setSecretKeyRef(SecretKeyRef secretKeyRef) {
            this.secretKeyRef = secretKeyRef;
        }
    }

    public static class ExposureSpec {
        private String type;
        private ListenersSpec listeners;
        private String host;
        private String ingressClassName;
        private Map<String, String> annotations;

        /** Auto (default), Route, Ingress or None. */
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        /**
         * Listeners exposed with one host each. When unset, the management and MCP listeners share
         * {@link #getHost()}, with MCP under the {@code /mcp} path.
         */
        public ListenersSpec getListeners() {
            return listeners;
        }

        public void setListeners(ListenersSpec listeners) {
            this.listeners = listeners;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getIngressClassName() {
            return ingressClassName;
        }

        public void setIngressClassName(String ingressClassName) {
            this.ingressClassName = ingressClassName;
        }

        public Map<String, String> getAnnotations() {
            return annotations;
        }

        public void setAnnotations(Map<String, String> annotations) {
            this.annotations = annotations;
        }
    }

    public static class ListenersSpec {
        private ListenerSpec management;
        private ListenerSpec mcp;
        private ListenerSpec inference;
        private ListenerSpec a2a;

        public ListenerSpec getManagement() {
            return management;
        }

        public void setManagement(ListenerSpec management) {
            this.management = management;
        }

        public ListenerSpec getMcp() {
            return mcp;
        }

        public void setMcp(ListenerSpec mcp) {
            this.mcp = mcp;
        }

        public ListenerSpec getInference() {
            return inference;
        }

        public void setInference(ListenerSpec inference) {
            this.inference = inference;
        }

        public ListenerSpec getA2a() {
            return a2a;
        }

        public void setA2a(ListenerSpec a2a) {
            this.a2a = a2a;
        }
    }

    public static class ListenerSpec {
        private Boolean enabled;
        private String host;

        /** Defaults to true for management and MCP, and to false for inference and A2A. */
        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        /** Public host. On OpenShift, an empty host lets the router generate one. */
        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }
    }

    /** A key in a Secret. */
    public static class SecretKeyRef {
        private String name;
        private String key;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    /**
     * Route files in a ConfigMap. Without {@code items}, every key is a file in one directory and
     * {@code main} names the key of the main route file. With {@code items}, each key is placed at its
     * path, and {@code main} names the path of the main route file.
     */
    public static class ConfigMapSource {
        private String name;
        private String main;
        private List<KeyToPath> items;

        /** Places keys at relative paths, for file trees such as a semantic route with Kamelet directories. */
        public List<KeyToPath> getItems() {
            return items;
        }

        public void setItems(List<KeyToPath> items) {
            this.items = items;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMain() {
            return main;
        }

        public void setMain(String main) {
            this.main = main;
        }
    }

    /** How a runtime registers with the router as an MCP forward. */
    public static class ForwardSpec {
        private String name;
        private String namespace;

        /** Forward name; defaults to the custom resource name. Forward names are unique per router. */
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** Wanaku namespace of the forward; defaults to {@code default}. */
        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }

    /** A ConfigMap key and the relative path where the runtime reads it. */
    public static class KeyToPath {
        private String key;
        private String path;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
