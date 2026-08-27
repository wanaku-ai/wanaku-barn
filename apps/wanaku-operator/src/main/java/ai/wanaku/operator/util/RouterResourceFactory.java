package ai.wanaku.operator.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.TLSConfig;
import io.javaoperatorsdk.operator.ReconcilerUtilsInternal;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import ai.wanaku.core.util.StringHelper;
import ai.wanaku.operator.wanaku.WanakuRouter;
import ai.wanaku.operator.wanaku.WanakuRouterReconciler;
import ai.wanaku.operator.wanaku.WanakuRouterSpec;
import ai.wanaku.operator.wanaku.WanakuTypes;
import ai.wanaku.operator.wanaku.WanakuTypes.InsecureEdgeTerminationPolicy;
import ai.wanaku.operator.wanaku.WanakuTypes.TlsTermination;

public final class RouterResourceFactory {
    private static final Logger LOG = Logger.getLogger(RouterResourceFactory.class);

    public static final String ROUTER_BACKEND_DEPLOYMENT_FILE = "wanaku-router-deployment.yaml";
    public static final String ROUTER_BACKEND_INTERNAL_SERVICE_FILE = "wanaku-router-service-internal.yaml";
    public static final String ROUTER_BACKEND_EXTERNAL_SERVICE_FILE = "wanaku-router-service-external.yaml";
    public static final String ROUTER_INGRESS_FILE = "wanaku-router-ingress.yaml";
    public static final String PRAXIS_DEPLOYMENT_FILE = "wanaku-praxis-deployment.yaml";
    public static final String PRAXIS_INTERNAL_SERVICE_FILE = "wanaku-praxis-service-internal.yaml";
    public static final String OAUTH2_PROXY_DEPLOYMENT_FILE = "wanaku-oauth2-proxy-deployment.yaml";
    public static final String OAUTH2_PROXY_SERVICE_FILE = "wanaku-oauth2-proxy-service.yaml";
    public static final String SERVICES_VOLUME_PVC_FILE = "services-volume-pvc.yaml";
    public static final String ROUTER_VOLUME_CLAIM = "router-volume-claim";
    public static final String PRAXIS_VOLUME_CLAIM = "praxis-volume-claim";

    public static final String DEFAULT_OAUTH2_PROXY_CLIENT_ID = "wanaku-mcp-router";
    public static final String OAUTH2_PROXY_CLIENT_SECRET_KEY = "client-secret";
    public static final String OAUTH2_PROXY_COOKIE_SECRET_KEY = "cookie-secret";

    private RouterResourceFactory() {}

    public static Deployment makeDesiredRouterBackendDeployment(WanakuRouter resource, Context<WanakuRouter> context) {
        Deployment desiredDeployment = ReconcilerUtilsInternal.loadYaml(
                Deployment.class, WanakuRouterReconciler.class, ROUTER_BACKEND_DEPLOYMENT_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        desiredDeployment.getMetadata().setName(routerName(deploymentName));
        desiredDeployment.getMetadata().setNamespace(ns);

        final DeploymentSpec serviceSpec = desiredDeployment.getSpec();

        serviceSpec.getSelector().getMatchLabels().put("app", routerName(deploymentName));
        serviceSpec.getSelector().getMatchLabels().put("component", "wanaku-barn-backend");
        serviceSpec.getTemplate().getMetadata().getLabels().put("app", routerName(deploymentName));
        serviceSpec.getTemplate().getMetadata().getLabels().put("component", "wanaku-barn-backend");

        setupBackendContainer(resource, serviceSpec);

        desiredDeployment.addOwnerReference(resource);
        return desiredDeployment;
    }

    public static Service makeRouterInternalService(WanakuRouter resource) {
        Service service = ReconcilerUtilsInternal.loadYaml(
                Service.class, WanakuRouterReconciler.class, ROUTER_BACKEND_INTERNAL_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        LOG.infof("Creating internal service for barn-backend: %s", deploymentName);
        service.getMetadata().setName("internal-" + deploymentName);
        service.getMetadata().setNamespace(ns);
        service.getMetadata().getLabels().put("app", routerName(deploymentName));
        service.getMetadata().getLabels().put("component", "wanaku-barn-backend");

        ServiceSpec serviceSpec = service.getSpec();
        serviceSpec.setSelector(Map.of("app", routerName(deploymentName), "component", "wanaku-barn-backend"));

        service.addOwnerReference(resource);
        return service;
    }

    public static Route makePraxisExternalRoute(WanakuRouter resource) {
        Route route = ReconcilerUtilsInternal.loadYaml(
                Route.class, WanakuRouterReconciler.class, ROUTER_BACKEND_EXTERNAL_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        LOG.infof("Creating external route for Praxis: %s", deploymentName);
        route.getMetadata().setName(deploymentName);
        route.getMetadata().setNamespace(ns);
        route.getMetadata().getLabels().put("app", praxisName(deploymentName));
        route.getMetadata().getLabels().put("component", "wanaku-praxis");
        if (isAuthEnabled(resource)) {
            route.getSpec().getTo().setName(oauth2ProxyServiceName(deploymentName));
            route.getSpec().getPort().setTargetPort(new io.fabric8.kubernetes.api.model.IntOrString("4180-tcp"));
        } else {
            route.getSpec().getTo().setName("praxis-" + deploymentName);
            route.getSpec().getPort().setTargetPort(new io.fabric8.kubernetes.api.model.IntOrString("8081-tcp"));
        }

        applyRouteTls(route, resource.getSpec().getExposure());
        route.addOwnerReference(resource);
        return route;
    }

    public static Ingress makePraxisIngress(WanakuRouter resource, String host) {
        Ingress ingress =
                ReconcilerUtilsInternal.loadYaml(Ingress.class, WanakuRouterReconciler.class, ROUTER_INGRESS_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        LOG.infof("Creating ingress for Praxis: %s", deploymentName);
        ingress.getMetadata().setName(deploymentName);
        ingress.getMetadata().setNamespace(ns);
        ingress.getMetadata().getLabels().put("app", praxisName(deploymentName));
        ingress.getMetadata().getLabels().put("component", "wanaku-praxis");

        String backendServiceName =
                isAuthEnabled(resource) ? oauth2ProxyServiceName(deploymentName) : "praxis-" + deploymentName;
        int backendServicePort = isAuthEnabled(resource) ? 4180 : 8081;

        ingress.getSpec().getRules().getFirst().setHost(host);
        ingress.getSpec()
                .getRules()
                .getFirst()
                .getHttp()
                .getPaths()
                .getFirst()
                .getBackend()
                .getService()
                .setName(backendServiceName);
        ingress.getSpec()
                .getRules()
                .getFirst()
                .getHttp()
                .getPaths()
                .getFirst()
                .getBackend()
                .getService()
                .getPort()
                .setNumber(backendServicePort);

        applyIngressExtras(ingress, resource.getSpec().getExposure(), host);
        ingress.addOwnerReference(resource);
        return ingress;
    }

    private static void applyRouteTls(Route route, WanakuTypes.ExposureSpec ingressSpec) {
        if (ingressSpec == null) {
            return;
        }
        TLSConfig tlsConfig = new TLSConfig();
        if (ingressSpec.getTls() == null) {
            tlsConfig.setTermination(TlsTermination.EDGE.toValue());
            tlsConfig.setInsecureEdgeTerminationPolicy(InsecureEdgeTerminationPolicy.REDIRECT.toValue());

        } else {
            WanakuTypes.TlsSpec tlsSpec = ingressSpec.getTls();
            if (tlsSpec.getTermination() == null) {
                return;
            }
            tlsConfig.setTermination(tlsSpec.getTermination().toValue());
            if (StringHelper.isNotEmpty(tlsSpec.getCertificate())) {
                tlsConfig.setCertificate(tlsSpec.getCertificate());
            }
            if (StringHelper.isNotEmpty(tlsSpec.getKey())) {
                tlsConfig.setKey(tlsSpec.getKey());
            }
            if (StringHelper.isNotEmpty(tlsSpec.getCaCertificate())) {
                tlsConfig.setCaCertificate(tlsSpec.getCaCertificate());
            }
            if (StringHelper.isNotEmpty(tlsSpec.getDestinationCACertificate())) {
                tlsConfig.setDestinationCACertificate(tlsSpec.getDestinationCACertificate());
            }
            if (tlsSpec.getInsecureEdgeTerminationPolicy() != null) {
                tlsConfig.setInsecureEdgeTerminationPolicy(
                        tlsSpec.getInsecureEdgeTerminationPolicy().toValue());
            }
        }
        route.getSpec().setTls(tlsConfig);
    }

    private static void applyIngressExtras(Ingress ingress, WanakuTypes.ExposureSpec exposureSpec, String host) {
        if (exposureSpec == null) {
            return;
        }
        if (StringHelper.isNotEmpty(exposureSpec.getIngressClassName())) {
            ingress.getSpec().setIngressClassName(exposureSpec.getIngressClassName());
        }
        if (exposureSpec.getAnnotations() != null
                && !exposureSpec.getAnnotations().isEmpty()) {
            Map<String, String> merged = new HashMap<>();
            if (ingress.getMetadata().getAnnotations() != null) {
                merged.putAll(ingress.getMetadata().getAnnotations());
            }
            merged.putAll(exposureSpec.getAnnotations());
            ingress.getMetadata().setAnnotations(merged);
        }
        IngressTLS ingressTls = new IngressTLS();
        ingressTls.setHosts(List.of(host));
        if (exposureSpec.getTls() != null
                && StringHelper.isNotEmpty(exposureSpec.getTls().getSecretName())) {
            ingressTls.setSecretName(exposureSpec.getTls().getSecretName());
        }
        ingress.getSpec().setTls(List.of(ingressTls));
    }

    public static PersistentVolumeClaim makeRouterVolumePVC(WanakuRouter resource) {
        PersistentVolumeClaim pvc = ReconcilerUtilsInternal.loadYaml(
                PersistentVolumeClaim.class, WanakuRouterReconciler.class, SERVICES_VOLUME_PVC_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        pvc.getMetadata().setName(ROUTER_VOLUME_CLAIM);
        pvc.getMetadata().setNamespace(ns);
        pvc.getMetadata().getLabels().put("app", routerName(deploymentName));
        pvc.getMetadata().getLabels().put("component", "wanaku-router-storage");

        pvc.addOwnerReference(resource);
        return pvc;
    }

    private static void setupBackendContainer(WanakuRouter resource, DeploymentSpec spec) {
        final List<Container> containers = spec.getTemplate().getSpec().getContainers();

        final Container service = containers.stream()
                .filter(c -> c.getName().equals("wanaku-mcp-router"))
                .findFirst()
                .get();

        List<EnvVar> envVars = new java.util.ArrayList<>();

        final WanakuRouterSpec.RouterSpec routerSpec = resource.getSpec().getRouter();

        String componentPolicy = routerSpec != null ? routerSpec.getImagePullPolicy() : null;
        String globalPolicy = resource.getSpec().getImagePullPolicy();
        String resolvedPolicy = OperatorUtil.resolveImagePullPolicy(componentPolicy, globalPolicy);
        service.setImagePullPolicy(resolvedPolicy);

        if (routerSpec != null) {
            final String image = routerSpec.getImage();
            if (image != null) {
                OperatorUtil.validateImageAllowed(image);
                service.setImage(image);
            }

            if (routerSpec.getEnv() != null && !routerSpec.getEnv().isEmpty()) {
                for (WanakuTypes.EnvVar env : routerSpec.getEnv()) {
                    envVars.add(new EnvVarBuilder()
                            .withName(env.getName())
                            .withValue(env.getValue())
                            .build());
                }
            }
        }

        final List<EnvVar> templateEnvs = service.getEnv();
        for (EnvVar templateVar : templateEnvs) {
            final Optional<EnvVar> override = envVars.stream()
                    .filter(envVar -> envVar.getName().equals(templateVar.getName()))
                    .findFirst();

            if (override.isEmpty()) {
                envVars.add(templateVar);
            }
        }

        EnvironmentVariableHelper.applyAnnotationEnvVars(
                envVars, resource.getMetadata().getAnnotations());

        service.setEnv(envVars);
    }

    public static PersistentVolumeClaim makePraxisVolumePVC(WanakuRouter resource) {
        PersistentVolumeClaim pvc = ReconcilerUtilsInternal.loadYaml(
                PersistentVolumeClaim.class, WanakuRouterReconciler.class, SERVICES_VOLUME_PVC_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        pvc.getMetadata().setName(PRAXIS_VOLUME_CLAIM);
        pvc.getMetadata().setNamespace(ns);
        pvc.getMetadata().getLabels().put("app", praxisName(deploymentName));
        pvc.getMetadata().getLabels().put("component", "wanaku-praxis-storage");

        pvc.addOwnerReference(resource);
        return pvc;
    }

    public static Deployment makeDesiredPraxisDeployment(WanakuRouter resource, Context<WanakuRouter> context) {
        Deployment desiredDeployment = ReconcilerUtilsInternal.loadYaml(
                Deployment.class, WanakuRouterReconciler.class, PRAXIS_DEPLOYMENT_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        desiredDeployment.getMetadata().setName(praxisName(deploymentName));
        desiredDeployment.getMetadata().setNamespace(ns);

        final DeploymentSpec serviceSpec = desiredDeployment.getSpec();
        serviceSpec.getSelector().getMatchLabels().put("app", praxisName(deploymentName));
        serviceSpec.getSelector().getMatchLabels().put("component", "wanaku-praxis");
        serviceSpec.getTemplate().getMetadata().getLabels().put("app", praxisName(deploymentName));
        serviceSpec.getTemplate().getMetadata().getLabels().put("component", "wanaku-praxis");

        final Container praxisContainer = serviceSpec.getTemplate().getSpec().getContainers().stream()
                .filter(c -> c.getName().equals("wanaku-praxis"))
                .findFirst()
                .get();

        String classicUrl = "http://internal-" + deploymentName + ":8080";
        List<EnvVar> envVars = new java.util.ArrayList<>();
        envVars.add(new EnvVarBuilder()
                .withName("WANAKU_CLASSIC_URL")
                .withValue(classicUrl)
                .build());

        final WanakuRouterSpec.PraxisSpec praxisSpec = resource.getSpec().getPraxis();
        if (praxisSpec != null) {
            if (praxisSpec.getImage() != null && !praxisSpec.getImage().isEmpty()) {
                OperatorUtil.validateImageAllowed(praxisSpec.getImage());
                praxisContainer.setImage(praxisSpec.getImage());
            }

            String componentPolicy = praxisSpec.getImagePullPolicy();
            String globalPolicy = resource.getSpec().getImagePullPolicy();
            praxisContainer.setImagePullPolicy(OperatorUtil.resolveImagePullPolicy(componentPolicy, globalPolicy));

            if (praxisSpec.getEnv() != null) {
                for (WanakuTypes.EnvVar env : praxisSpec.getEnv()) {
                    envVars.add(new EnvVarBuilder()
                            .withName(env.getName())
                            .withValue(env.getValue())
                            .build());
                }
            }
        }

        final List<EnvVar> templateEnvs = praxisContainer.getEnv();
        for (EnvVar templateVar : templateEnvs) {
            final Optional<EnvVar> override = envVars.stream()
                    .filter(envVar -> envVar.getName().equals(templateVar.getName()))
                    .findFirst();
            if (override.isEmpty()) {
                envVars.add(templateVar);
            }
        }
        praxisContainer.setEnv(envVars);

        desiredDeployment.addOwnerReference(resource);
        return desiredDeployment;
    }

    public static Service makePraxisInternalService(WanakuRouter resource) {
        Service service = ReconcilerUtilsInternal.loadYaml(
                Service.class, WanakuRouterReconciler.class, PRAXIS_INTERNAL_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        service.getMetadata().setName("praxis-" + deploymentName);
        service.getMetadata().setNamespace(ns);
        service.getMetadata().getLabels().put("app", praxisName(deploymentName));
        service.getMetadata().getLabels().put("component", "wanaku-praxis");

        ServiceSpec serviceSpec = service.getSpec();
        serviceSpec.setSelector(Map.of("app", praxisName(deploymentName), "component", "wanaku-praxis"));

        service.addOwnerReference(resource);
        return service;
    }

    public static boolean isAuthEnabled(WanakuRouter resource) {
        final WanakuRouterSpec.AuthSpec auth =
                resource.getSpec() != null ? resource.getSpec().getAuth() : null;
        return auth != null && auth.isEnabled();
    }

    public static Service makeOauth2ProxyService(WanakuRouter resource) {
        Service service = ReconcilerUtilsInternal.loadYaml(
                Service.class, WanakuRouterReconciler.class, OAUTH2_PROXY_SERVICE_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        service.getMetadata().setName(oauth2ProxyServiceName(deploymentName));
        service.getMetadata().setNamespace(ns);
        service.getMetadata().getLabels().put("app", oauth2ProxyName(deploymentName));
        service.getMetadata().getLabels().put("component", "wanaku-oauth2-proxy");

        ServiceSpec serviceSpec = service.getSpec();
        serviceSpec.setSelector(Map.of("app", oauth2ProxyName(deploymentName), "component", "wanaku-oauth2-proxy"));

        service.addOwnerReference(resource);
        return service;
    }

    public static Deployment makeOauth2ProxyDeployment(WanakuRouter resource, String host) {
        Deployment desiredDeployment = ReconcilerUtilsInternal.loadYaml(
                Deployment.class, WanakuRouterReconciler.class, OAUTH2_PROXY_DEPLOYMENT_FILE);

        String deploymentName = resource.getMetadata().getName();
        String ns = resource.getMetadata().getNamespace();

        desiredDeployment.getMetadata().setName(oauth2ProxyName(deploymentName));
        desiredDeployment.getMetadata().setNamespace(ns);

        final DeploymentSpec deploymentSpec = desiredDeployment.getSpec();
        deploymentSpec.getSelector().getMatchLabels().put("app", oauth2ProxyName(deploymentName));
        deploymentSpec.getSelector().getMatchLabels().put("component", "wanaku-oauth2-proxy");
        deploymentSpec.getTemplate().getMetadata().getLabels().put("app", oauth2ProxyName(deploymentName));
        deploymentSpec.getTemplate().getMetadata().getLabels().put("component", "wanaku-oauth2-proxy");

        final WanakuRouterSpec.AuthSpec authSpec = resource.getSpec().getAuth();
        final String redirectUrl = "https://%s/oauth2/callback".formatted(host);

        for (Container container : deploymentSpec.getTemplate().getSpec().getContainers()) {
            if (authSpec.getImage() != null && !authSpec.getImage().isEmpty()) {
                OperatorUtil.validateImageAllowed(authSpec.getImage());
                container.setImage(authSpec.getImage());
            }
            container.setImagePullPolicy(OperatorUtil.resolveImagePullPolicy(
                    authSpec.getImagePullPolicy(), resource.getSpec().getImagePullPolicy()));

            List<EnvVar> envVars = new java.util.ArrayList<>();
            if (authSpec.getEnv() != null) {
                for (WanakuTypes.EnvVar env : authSpec.getEnv()) {
                    envVars.add(new EnvVarBuilder()
                            .withName(env.getName())
                            .withValue(env.getValue())
                            .build());
                }
            }
            List<EnvVar> defaults = "oauth2-proxy-mcp".equals(container.getName())
                    ? oauth2ProxyMcpEnv(authSpec, deploymentName, redirectUrl)
                    : oauth2ProxyMgmtEnv(authSpec, deploymentName, redirectUrl);
            for (EnvVar defaultVar : defaults) {
                final Optional<EnvVar> override = envVars.stream()
                        .filter(envVar -> envVar.getName().equals(defaultVar.getName()))
                        .findFirst();
                if (override.isEmpty()) {
                    envVars.add(defaultVar);
                }
            }
            container.setEnv(envVars);
        }

        desiredDeployment.addOwnerReference(resource);
        return desiredDeployment;
    }

    private static List<EnvVar> oauth2ProxyMcpEnv(
            WanakuRouterSpec.AuthSpec authSpec, String deploymentName, String redirectUrl) {
        List<EnvVar> env = oauth2ProxySharedEnv(authSpec, redirectUrl);
        env.add(envVar("OAUTH2_PROXY_HTTP_ADDRESS", "0.0.0.0:4180"));
        env.add(envVar("OAUTH2_PROXY_UPSTREAMS", "http://praxis-%s:8081".formatted(deploymentName)));
        env.add(envVar(
                "OAUTH2_PROXY_SKIP_AUTH_ROUTES",
                "^/.well-known/.*,^/public/.*,^/authorize$,^/token$,^/register$,OPTIONS=^/.*"));
        env.add(envVar("OAUTH2_PROXY_API_ROUTES", "^/mcp.*"));
        env.add(envVar("OAUTH2_PROXY_UPSTREAM_TIMEOUT", "3600s"));
        return env;
    }

    private static List<EnvVar> oauth2ProxyMgmtEnv(
            WanakuRouterSpec.AuthSpec authSpec, String deploymentName, String redirectUrl) {
        List<EnvVar> env = oauth2ProxySharedEnv(authSpec, redirectUrl);
        env.add(envVar("OAUTH2_PROXY_HTTP_ADDRESS", "0.0.0.0:4181"));
        env.add(envVar("OAUTH2_PROXY_UPSTREAMS", "http://praxis-%s:9090".formatted(deploymentName)));
        env.add(envVar("OAUTH2_PROXY_SKIP_AUTH_ROUTES", "^/healthz$,^/health$"));
        return env;
    }

    private static List<EnvVar> oauth2ProxySharedEnv(WanakuRouterSpec.AuthSpec authSpec, String redirectUrl) {
        String clientId = StringHelper.isNotEmpty(authSpec.getClientId())
                ? authSpec.getClientId()
                : DEFAULT_OAUTH2_PROXY_CLIENT_ID;

        String issuerUrl = authSpec.getIssuerUrl();
        String issuerBase = issuerUrl != null && issuerUrl.endsWith("/")
                ? issuerUrl.substring(0, issuerUrl.length() - 1)
                : issuerUrl;

        List<EnvVar> env = new java.util.ArrayList<>();
        env.add(envVar("OAUTH2_PROXY_PROVIDER", "keycloak-oidc"));
        env.add(envVar("OAUTH2_PROXY_OIDC_ISSUER_URL", issuerUrl));
        // The Keycloak public and in-cluster URLs may differ, so skip the OIDC discovery and derive the
        // endpoints from the issuer; each one can be overridden through spec.auth.env
        env.add(envVar("OAUTH2_PROXY_SKIP_OIDC_DISCOVERY", "true"));
        env.add(envVar("OAUTH2_PROXY_LOGIN_URL", issuerBase + "/protocol/openid-connect/auth"));
        env.add(envVar("OAUTH2_PROXY_REDEEM_URL", issuerBase + "/protocol/openid-connect/token"));
        env.add(envVar("OAUTH2_PROXY_OIDC_JWKS_URL", issuerBase + "/protocol/openid-connect/certs"));
        env.add(envVar("OAUTH2_PROXY_CLIENT_ID", clientId));
        env.add(secretEnvVar("OAUTH2_PROXY_CLIENT_SECRET", authSpec.getSecretName(), OAUTH2_PROXY_CLIENT_SECRET_KEY));
        env.add(secretEnvVar("OAUTH2_PROXY_COOKIE_SECRET", authSpec.getSecretName(), OAUTH2_PROXY_COOKIE_SECRET_KEY));
        env.add(envVar("OAUTH2_PROXY_REDIRECT_URL", redirectUrl));
        env.add(envVar("OAUTH2_PROXY_EMAIL_DOMAINS", "*"));
        env.add(envVar("OAUTH2_PROXY_CODE_CHALLENGE_METHOD", "S256"));
        env.add(envVar("OAUTH2_PROXY_SKIP_JWT_BEARER_TOKENS", "true"));
        env.add(envVar("OAUTH2_PROXY_PASS_AUTHORIZATION_HEADER", "true"));
        env.add(envVar("OAUTH2_PROXY_PASS_USER_HEADERS", "true"));
        env.add(envVar("OAUTH2_PROXY_SET_XAUTHREQUEST", "true"));
        env.add(envVar("OAUTH2_PROXY_OIDC_EXTRA_AUDIENCES", "mcp-client,wanaku-mcp-client"));
        env.add(envVar("OAUTH2_PROXY_INSECURE_OIDC_ALLOW_UNVERIFIED_EMAIL", "true"));
        return env;
    }

    private static EnvVar envVar(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    private static EnvVar secretEnvVar(String name, String secretName, String key) {
        return new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secretName)
                .withKey(key)
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    private static String routerName(String deploymentName) {
        return deploymentName + "-mcp-router";
    }

    private static String praxisName(String deploymentName) {
        return deploymentName + "-praxis";
    }

    private static String oauth2ProxyName(String deploymentName) {
        return deploymentName + "-oauth2-proxy";
    }

    public static String oauth2ProxyServiceName(String deploymentName) {
        return "oauth2-proxy-" + deploymentName;
    }
}
