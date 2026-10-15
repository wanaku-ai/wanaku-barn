package ai.wanaku.operator.wanaku;

import java.util.List;

public class WanakuRouterSpec {
    private String imagePullPolicy;
    private WanakuTypes.ExposureSpec exposure;
    private RouterSpec router;
    private ServerSpec server;
    private AuthSpec auth;

    public String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    public WanakuTypes.ExposureSpec getExposure() {
        return exposure;
    }

    public void setExposure(WanakuTypes.ExposureSpec exposure) {
        this.exposure = exposure;
    }

    public RouterSpec getRouter() {
        return router;
    }

    public void setRouter(RouterSpec router) {
        this.router = router;
    }

    public ServerSpec getServer() {
        return server;
    }

    public void setServer(ServerSpec server) {
        this.server = server;
    }

    public AuthSpec getAuth() {
        return auth;
    }

    public void setAuth(AuthSpec auth) {
        this.auth = auth;
    }

    /**
     * Authentication via oauth2-proxy instances placed in front of Wanaku Server.
     * Two proxies are deployed: one protecting the MCP port (4180) and one
     * protecting the management port (4181), sharing the same cookie secret for SSO.
     */
    public static class AuthSpec {
        private boolean enabled;
        private String image;
        private String imagePullPolicy;
        private String issuerUrl;
        private String internalIssuerUrl;
        private String clientId;
        private String secretName;
        private List<WanakuTypes.EnvVar> env;
        private String authServer;
        private String authProxy;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

        public String getIssuerUrl() {
            return issuerUrl;
        }

        public void setIssuerUrl(String issuerUrl) {
            this.issuerUrl = issuerUrl;
        }

        /**
         * In-cluster URL of the same OIDC issuer, used for token and JWKS requests when the public
         * issuer URL is not reachable from the cluster.
         */
        public String getInternalIssuerUrl() {
            return internalIssuerUrl;
        }

        public void setInternalIssuerUrl(String internalIssuerUrl) {
            this.internalIssuerUrl = internalIssuerUrl;
        }

        /** Deprecated legacy field. Kept in the schema so the operator can report it instead of pruning it. */
        public String getAuthServer() {
            return authServer;
        }

        public void setAuthServer(String authServer) {
            this.authServer = authServer;
        }

        /** Deprecated legacy field. Kept in the schema so the operator can report it instead of pruning it. */
        public String getAuthProxy() {
            return authProxy;
        }

        public void setAuthProxy(String authProxy) {
            this.authProxy = authProxy;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getSecretName() {
            return secretName;
        }

        public void setSecretName(String secretName) {
            this.secretName = secretName;
        }

        public List<WanakuTypes.EnvVar> getEnv() {
            return env;
        }

        public void setEnv(List<WanakuTypes.EnvVar> env) {
            this.env = env;
        }
    }

    public static class ServerSpec {
        private String image;
        private List<WanakuTypes.EnvVar> env;
        private String imagePullPolicy;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public List<WanakuTypes.EnvVar> getEnv() {
            return env;
        }

        public void setEnv(List<WanakuTypes.EnvVar> env) {
            this.env = env;
        }

        public String getImagePullPolicy() {
            return imagePullPolicy;
        }

        public void setImagePullPolicy(String imagePullPolicy) {
            this.imagePullPolicy = imagePullPolicy;
        }
    }

    public static class RouterSpec {
        private boolean enabled;
        private String image;
        private List<WanakuTypes.EnvVar> env;
        private String imagePullPolicy;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public List<WanakuTypes.EnvVar> getEnv() {
            return env;
        }

        public void setEnv(List<WanakuTypes.EnvVar> env) {
            this.env = env;
        }

        public String getImagePullPolicy() {
            return imagePullPolicy;
        }

        public void setImagePullPolicy(String imagePullPolicy) {
            this.imagePullPolicy = imagePullPolicy;
        }
    }
}
