package ai.wanaku.backend.api.v1.semanticrouter;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticPreviewClientTest {
    @Test
    void sendsOnlyClassificationDataAndValidatesNativeLabels() throws Exception {
        var seen = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/preview", request -> {
            seen.set(new String(request.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response =
                    "{\"label\":\"billing\",\"diagnostics\":{\"providerToken\":\"sensitive\",\"confidence\":0.8,\"probabilities\":{\"billing\":0.8,\"technical\":0.2,\"injected\":1}}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, response.length);
            request.getResponseBody().write(response);
            request.close();
        });
        server.start();
        try {
            SemanticPreviewClient client = client(server);
            var result = client.evaluate(SemanticCatalogTest.definition(), "Invoice question");
            assertThat(result.label).isEqualTo("billing");
            assertThat(result.error).isNull();
            assertThat(result.diagnostics).containsEntry("confidence", 0.8).doesNotContainKey("providerToken");
            assertThat(result.diagnostics.get("probabilities")).isEqualTo(Map.of("billing", 0.8, "technical", 0.2));
            assertThat(seen.get())
                    .contains("supportExpert", "instructions", "criteria", "no_match", "message")
                    .doesNotContain("configuration", "prefix", "actionId", "kamelet", "dispatch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureAndMalformedLabelsRemainErrors() throws Exception {
        for (String response : java.util.List.of("{\"label\":\"unconfigured\"}", "not JSON")) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/preview", request -> {
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, bytes.length);
                request.getResponseBody().write(bytes);
                request.close();
            });
            server.start();
            try {
                var result = client(server).evaluate(SemanticCatalogTest.definition(), "Message");
                assertThat(result.error).isNotBlank();
                assertThat(result.label).isNull();
                assertThat(result.noMatch).isFalse();
            } finally {
                server.stop(0);
            }
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/preview", request -> {
            byte[] bytes = "provider secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            request.sendResponseHeaders(502, bytes.length);
            request.getResponseBody().write(bytes);
            request.close();
        });
        server.start();
        try {
            var result = client(server).evaluate(SemanticCatalogTest.definition(), "Message");
            assertThat(result.error).isEqualTo("Semantic evaluation failed").doesNotContain("secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitNoMatchHasNoError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/preview", request -> {
            byte[] bytes = "{\"label\":\"no_match\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes);
            request.close();
        });
        server.start();
        try {
            var result = client(server).evaluate(SemanticCatalogTest.definition(), "Unrelated");
            assertThat(result.noMatch).isTrue();
            assertThat(result.label).isEqualTo("no_match");
            assertThat(result.error).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void previewConcurrencyAndResponseReadsAreBounded() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/preview", request -> {
            entered.countDown();
            try {
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            request.sendResponseHeaders(200, 0);
            request.close();
        });
        server.start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            SemanticPreviewClient client = client(server);
            var pending = executor.submit(() -> client.evaluate(SemanticCatalogTest.definition(), "Waiting"));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var busy = client.evaluate(SemanticCatalogTest.definition(), "Concurrent");
            assertThat(busy.error).isEqualTo("Classification preview is busy");
            var timedOut = pending.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(timedOut.error).isEqualTo("Semantic evaluation failed");
            assertThat(timedOut.durationMillis).isLessThan(2000);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void oversizedResponsesAreRejectedBeforeParsing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/preview", request -> {
            byte[] bytes = new byte[70 * 1024];
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes);
            request.close();
        });
        server.start();
        try {
            var result = client(server).evaluate(SemanticCatalogTest.definition(), "Message");
            assertThat(result.error).isEqualTo("Semantic evaluation failed");
            assertThat(result.label).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static SemanticPreviewClient client(HttpServer server) {
        SemanticActionCatalog catalog = new SemanticActionCatalog();
        catalog.kamelets = ai.wanaku.backend.api.v1.kamelets.KameletTestSupport.catalog(
                ai.wanaku.backend.api.v1.kamelets.KameletTestSupport.repository(new java.util.LinkedHashMap<>()));
        catalog.parser = new ai.wanaku.backend.api.v1.kamelets.KameletParser();
        catalog.expertsFile = Optional.empty();
        catalog.init();
        SemanticPreviewClient client = new SemanticPreviewClient();
        client.catalog = catalog;
        client.mapper = new ObjectMapper();
        client.timeoutSeconds = 1;
        client.maxConcurrency = 1;
        client.url = Optional.of("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/preview");
        client.token = Optional.empty();
        client.init();
        return client;
    }
}
