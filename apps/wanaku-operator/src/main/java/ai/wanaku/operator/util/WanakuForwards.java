package ai.wanaku.operator.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads MCP forwards from the Wanaku Server management API. */
public final class WanakuForwards {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private WanakuForwards() {}

    /** A registered forward, as reported by the Server. */
    public static final class Forward {
        public final boolean available;
        public final String statusMessage;
        public final Map<String, String> labels;

        public Forward(boolean available, String statusMessage, Map<String, String> labels) {
            this.available = available;
            this.statusMessage = statusMessage;
            this.labels = labels;
        }
    }

    /** Looks up forwards; tests replace the HTTP implementation. */
    @FunctionalInterface
    public interface Lookup {
        /**
         * @return the forward, or {@code null} when the Server has no forward with this name
         * @throws IOException when the Server cannot be reached or answers with an error
         */
        Forward get(String managementUrl, String name, String token) throws IOException, InterruptedException;
    }

    /** Calls {@code GET <managementUrl>/api/v1/forwards/<name>}. */
    public static Forward get(String managementUrl, String name, String token)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        managementUrl + "/api/v1/forwards/" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        final HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET forward %s returned HTTP %d".formatted(name, response.statusCode()));
        }
        return parse(response.body());
    }

    static Forward parse(String body) throws IOException {
        final JsonNode root = MAPPER.readTree(body);
        // The management API wraps responses as {"data": ..., "error": ...}
        final JsonNode forward = root.has("data") ? root.get("data") : root;
        final Map<String, String> labels = new HashMap<>();
        forward.path("labels")
                .properties()
                .forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
        final JsonNode message = forward.get("statusMessage");
        return new Forward(
                forward.path("available").asBoolean(true),
                message != null && !message.isNull() ? message.asText() : null,
                labels);
    }
}
