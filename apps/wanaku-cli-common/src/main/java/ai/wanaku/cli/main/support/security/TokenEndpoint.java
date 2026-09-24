package ai.wanaku.cli.main.support.security;

/**
 * Utility class for constructing OAuth2 token endpoint URLs.
 * Provides methods for creating endpoint URLs either directly or from base URLs.
 */
public final class TokenEndpoint {

    /**
     * Private constructor to prevent instantiation.
     */
    private TokenEndpoint() {}

    /**
     * Returns the provided URI directly as the token endpoint.
     *
     * @param uri The complete token endpoint URI.
     * @return The same URI.
     */
    public static String direct(String uri) {
        return uri;
    }

    /**
     * Constructs a token endpoint URL by appending the standard OpenID Connect path to a base URL.
     *
     * @param baseUrl The base URL of the authentication server.
     * @return The complete token endpoint URL.
     */
    public static String fromBaseUrl(String baseUrl) {
        return stripTrailingSlash(baseUrl) + "/protocol/openid-connect/token";
    }

    /**
     * Constructs the OIDC issuer URL used for discovery.
     *
     * <p>When the base URL already points at a Keycloak realm (contains {@code /realms/}) it is
     * used as-is, so callers can pass a full issuer URL. Otherwise the Keycloak realm path
     * {@code /realms/<realm>} is appended. A blank realm means the base URL is the issuer.</p>
     *
     * @param baseUrl The base URL of the authentication server (Keycloak) or the issuer URL.
     * @param realm   The authentication realm, or {@code null} when the base URL is the issuer.
     * @return The issuer URL to use for OIDC discovery.
     */
    public static String forDiscovery(String baseUrl, String realm) {
        String url = stripTrailingSlash(baseUrl);
        if (url.contains("/realms/") || realm == null || realm.isBlank()) {
            return url;
        }
        return url + "/realms/" + realm.strip();
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
