package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreview;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Calls a separately hosted classification-only Camel service with bounded resource use. */
@ApplicationScoped
public class SemanticPreviewClient {
    private static final Logger LOG = Logger.getLogger(SemanticPreviewClient.class);

    @ConfigProperty(name = "wanaku.semantic.preview-url")
    Optional<String> url;

    @ConfigProperty(name = "wanaku.semantic.preview-token")
    Optional<String> token;

    @ConfigProperty(name = "wanaku.semantic.preview-timeout-seconds", defaultValue = "15")
    int timeoutSeconds;

    @ConfigProperty(name = "wanaku.semantic.preview-max-concurrency", defaultValue = "4")
    int maxConcurrency;

    @Inject
    SemanticActionCatalog catalog;

    @Inject
    ObjectMapper mapper;

    private Semaphore permits;
    private HttpClient client;

    @PostConstruct
    void init() {
        if (timeoutSeconds < 1 || timeoutSeconds > 120 || maxConcurrency < 1 || maxConcurrency > 32)
            throw new IllegalStateException("Invalid semantic preview limits");
        url.ifPresent(value -> {
            URI endpoint = URI.create(value);
            if (!java.util.Set.of("http", "https").contains(endpoint.getScheme())
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null)
                throw new IllegalStateException("Invalid configured preview URL");
        });
        permits = new Semaphore(maxConcurrency);
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Returns native labels and available diagnostics without action resources or action configuration. */
    public SemanticPreview evaluate(SemanticRouterDefinition definition, String message) {
        long start = System.nanoTime();
        SemanticPreview result = new SemanticPreview();
        result.diagnostics = Map.of();
        if (url.isEmpty()) {
            result.error = "Classification preview is not configured";
            return finish(result, start);
        }
        if (!permits.tryAcquire()) {
            result.error = "Classification preview is busy";
            return finish(result, start);
        }
        try {
            Map<String, Object> payload = Map.of(
                    "input",
                    "message",
                    "instructions",
                    definition.instructions,
                    "criteria",
                    SemanticCatalogGenerator.criteria(definition),
                    "expertBean",
                    catalog.expert(definition.expertId).bean,
                    "message",
                    message);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url.get()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            token.ifPresent(value -> request.header("Authorization", "Bearer " + value));
            // Bound the provider response before parsing it or returning diagnostics.
            var pending = client.sendAsync(request.build(), ignored -> new BoundedSubscriber());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                pending.cancel(true);
                throw e;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                pending.cancel(true);
                throw new IOException("Preview response failed or timed out", e);
            }
            byte[] body = response.body();
            if (response.statusCode() != 200) {
                result.error = "Semantic evaluation failed";
                return finish(result, start);
            }
            JsonNode value = mapper.readTree(body);
            String label = value.path("label").asText();
            if (!SemanticCatalogGenerator.criteria(definition).containsKey(label)) {
                result.error = "Semantic evaluation returned an invalid label";
                return finish(result, start);
            }
            result.label = label;
            result.noMatch = "no_match".equals(label);
            result.diagnostics = safeDiagnostics(
                    value.path("diagnostics"),
                    SemanticCatalogGenerator.criteria(definition).keySet());
        } catch (IOException e) {
            LOG.debug("Semantic preview transport failed", e);
            result.error = "Semantic evaluation failed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.error = "Semantic evaluation interrupted";
        } finally {
            permits.release();
        }
        return finish(result, start);
    }

    private static Map<String, Object> safeDiagnostics(JsonNode diagnostics, java.util.Set<String> labels) {
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        JsonNode confidence = diagnostics.path("confidence");
        if (probability(confidence)) safe.put("confidence", confidence.doubleValue());
        JsonNode probabilities = diagnostics.path("probabilities");
        if (probabilities.isObject()) {
            Map<String, Double> values = new java.util.LinkedHashMap<>();
            probabilities.fields().forEachRemaining(entry -> {
                if (labels.contains(entry.getKey()) && probability(entry.getValue()))
                    values.put(entry.getKey(), entry.getValue().doubleValue());
            });
            if (!values.isEmpty()) safe.put("probabilities", values);
        }
        return safe;
    }

    private static boolean probability(JsonNode value) {
        return value.isNumber()
                && Double.isFinite(value.doubleValue())
                && value.doubleValue() >= 0
                && value.doubleValue() <= 1;
    }

    /** Completes only after a bounded response body arrives, so the request deadline covers body reads. */
    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> body =
                new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            subscription.request(1);
        }

        @Override
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (java.nio.ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 64 * 1024) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Preview response exceeds 64 KiB"));
                    return;
                }
                byte[] value = new byte[buffer.remaining()];
                buffer.get(value);
                bytes.writeBytes(value);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    private static SemanticPreview finish(SemanticPreview result, long start) {
        result.durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return result;
    }
}
