package ai.wanaku.operator.wanaku;

import java.util.List;
import java.util.Map;

public class WanakuSemanticRouterSpec {
    private String routerRef;
    private String image;
    private String imagePullPolicy;
    private Source source;
    private List<String> dependencies;
    private WanakuTypes.ForwardSpec forward;
    private WanakuTypes.SecretKeyRef wanakuTokenSecretRef;
    private WanakuTypes.SecretKeyRef barnTokenSecretRef;
    private List<WanakuTypes.EnvVar> env;
    private Map<String, String> properties;

    /** The WanakuRouter that the semantic router registers with. */
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

    /** Where the semantic route comes from. Set exactly one source. */
    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    /** Extra Maven dependencies (group:artifact:version). */
    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }

    public WanakuTypes.ForwardSpec getForward() {
        return forward;
    }

    public void setForward(WanakuTypes.ForwardSpec forward) {
        this.forward = forward;
    }

    /** Bearer token for the router management API, when that API requires one. */
    public WanakuTypes.SecretKeyRef getWanakuTokenSecretRef() {
        return wanakuTokenSecretRef;
    }

    public void setWanakuTokenSecretRef(WanakuTypes.SecretKeyRef wanakuTokenSecretRef) {
        this.wanakuTokenSecretRef = wanakuTokenSecretRef;
    }

    /** Bearer token for the Barn API, when that API requires one. */
    public WanakuTypes.SecretKeyRef getBarnTokenSecretRef() {
        return barnTokenSecretRef;
    }

    public void setBarnTokenSecretRef(WanakuTypes.SecretKeyRef barnTokenSecretRef) {
        this.barnTokenSecretRef = barnTokenSecretRef;
    }

    /**
     * Deployment and Camel component properties, passed to the runtime as {@code -p KEY=VALUE}. Use
     * {@code {{env:NAME}}} references for credentials and set {@code NAME} through {@code env} from a Secret.
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public List<WanakuTypes.EnvVar> getEnv() {
        return env;
    }

    public void setEnv(List<WanakuTypes.EnvVar> env) {
        this.env = env;
    }

    public static class Source {
        private SemanticRouteSource semanticRoute;
        private CatalogSource catalog;
        private WanakuTypes.ConfigMapSource configMapRef;

        /** A semantic route published in Barn. */
        public SemanticRouteSource getSemanticRoute() {
            return semanticRoute;
        }

        public void setSemanticRoute(SemanticRouteSource semanticRoute) {
            this.semanticRoute = semanticRoute;
        }

        /** A pinned service catalog revision in Barn. */
        public CatalogSource getCatalog() {
            return catalog;
        }

        public void setCatalog(CatalogSource catalog) {
            this.catalog = catalog;
        }

        /** Route files in a ConfigMap, including semantic-router.properties. */
        public WanakuTypes.ConfigMapSource getConfigMapRef() {
            return configMapRef;
        }

        public void setConfigMapRef(WanakuTypes.ConfigMapSource configMapRef) {
            this.configMapRef = configMapRef;
        }
    }

    public static class SemanticRouteSource {
        private String name;

        /** The semantic route name in Barn; the runtime resolves its current revision. */
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class CatalogSource {
        private String name;
        private String service;
        private String revision;
        private String sha256;
        private String main;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String sha256) {
            this.sha256 = sha256;
        }

        public String getMain() {
            return main;
        }

        public void setMain(String main) {
            this.main = main;
        }
    }
}
