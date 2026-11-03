package ai.wanaku.operator.wanaku;

import java.util.List;

public class WanakuIntegrationSpec {
    private String routerRef;
    private String image;
    private String imagePullPolicy;
    private Routes routes;
    private List<String> dependencies;
    private List<String> repositories;
    private Mcp mcp;
    private Registration registration;
    private List<WanakuTypes.EnvVar> env;

    /** The WanakuRouter that the integration registers with. */
    public String getRouterRef() {
        return routerRef;
    }

    public void setRouterRef(String routerRef) {
        this.routerRef = routerRef;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    /** Where the Camel routes come from. Set exactly one source. */
    public Routes getRoutes() {
        return routes;
    }

    public void setRoutes(Routes routes) {
        this.routes = routes;
    }

    /** Extra Maven dependencies (group:artifact:version). */
    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }

    /** Extra Maven repositories. */
    public List<String> getRepositories() {
        return repositories;
    }

    public void setRepositories(List<String> repositories) {
        this.repositories = repositories;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public void setMcp(Mcp mcp) {
        this.mcp = mcp;
    }

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    public List<WanakuTypes.EnvVar> getEnv() {
        return env;
    }

    public void setEnv(List<WanakuTypes.EnvVar> env) {
        this.env = env;
    }

    public static class Routes {
        private WanakuTypes.ConfigMapSource configMapRef;
        private String routesRef;
        private ServiceCatalog serviceCatalog;

        /** Route files in a ConfigMap. */
        public WanakuTypes.ConfigMapSource getConfigMapRef() {
            return configMapRef;
        }

        public void setConfigMapRef(WanakuTypes.ConfigMapSource configMapRef) {
            this.configMapRef = configMapRef;
        }

        /** A routes reference that the capability downloads, for example datastore://orders.camel.yaml. */
        public String getRoutesRef() {
            return routesRef;
        }

        public void setRoutesRef(String routesRef) {
            this.routesRef = routesRef;
        }

        /** A service catalog in Wanaku Barn. */
        public ServiceCatalog getServiceCatalog() {
            return serviceCatalog;
        }

        public void setServiceCatalog(ServiceCatalog serviceCatalog) {
            this.serviceCatalog = serviceCatalog;
        }
    }

    public static class ServiceCatalog {
        private String name;
        private String system;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSystem() {
            return system;
        }

        public void setSystem(String system) {
            this.system = system;
        }
    }

    public static class Mcp {
        private Integer port;
        private String tags;

        /** MCP server port (default 9090); the health endpoints use the same port. */
        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        /** Route tags exposed as MCP tools (default: the capability default, wanaku). */
        public String getTags() {
            return tags;
        }

        public void setTags(String tags) {
            this.tags = tags;
        }
    }

    public static class Registration {
        private Boolean enabled;
        private String name;
        private String namespace;
        private WanakuTypes.SecretKeyRef tokenSecretRef;

        /** Registers the integration with the router as an MCP forward (default true). */
        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

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

        /** Bearer token for the router management API, when that API requires one. */
        public WanakuTypes.SecretKeyRef getTokenSecretRef() {
            return tokenSecretRef;
        }

        public void setTokenSecretRef(WanakuTypes.SecretKeyRef tokenSecretRef) {
            this.tokenSecretRef = tokenSecretRef;
        }
    }
}
