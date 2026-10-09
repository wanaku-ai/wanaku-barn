package ai.wanaku.backend.api.v1.kamelets;

import java.util.Map;
import java.util.Optional;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared catalog and repository fixtures for native and semantic authoring tests. */
public final class KameletTestSupport {
    private KameletTestSupport() {}

    public static KameletBean catalog(DataStoreRepository repository) {
        KameletBean bean = new KameletBean();
        bean.repository = repository;
        bean.parser = new KameletParser();
        bean.actionsDirectory = Optional.empty();
        bean.init();
        return bean;
    }

    public static DataStoreRepository repository(Map<String, DataStore> stored) {
        DataStoreRepository repository = mock(DataStoreRepository.class);
        when(repository.findById(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(repository.persist(any())).thenAnswer(call -> {
            DataStore row = call.getArgument(0);
            if (row.getId() == null) row.setId(java.util.UUID.randomUUID().toString());
            stored.put(row.getId(), row);
            return row;
        });
        when(repository.persistIfAbsent(any())).thenAnswer(call -> {
            DataStore row = call.getArgument(0);
            return stored.putIfAbsent(row.getId(), row);
        });
        when(repository.deleteById(anyString())).thenAnswer(call -> stored.remove(call.getArgument(0)) != null);
        when(repository.findByName(anyString())).thenAnswer(call -> stored.values().stream()
                .filter(row -> call.getArgument(0).equals(row.getName()))
                .toList());
        when(repository.findAllFilterByLabelExpression(anyString())).thenAnswer(call -> {
            String expression = call.getArgument(0);
            String[] parts = expression.split("=", 2);
            return stored.values().stream()
                    .filter(row -> row.getLabels() != null
                            && parts[1].equals(row.getLabels().get(parts[0])))
                    .toList();
        });
        return repository;
    }

    /** Returns an ordinary native sink without Barn annotations or body type declarations.
     * @param name native Kamelet name
     * @return exact YAML fixture */
    public static String sinkYaml(String name) {
        return """
                apiVersion: camel.apache.org/v1
                kind: Kamelet
                metadata:
                  name: %s
                  labels:
                    camel.apache.org/kamelet.type: sink
                spec:
                  definition:
                    title: Kafka Sink
                    description: Send the message to a Kafka topic.
                    type: object
                    required: [topic, bootstrapServers]
                    properties:
                      topic:
                        type: string
                        title: Topic
                      bootstrapServers:
                        type: string
                        title: Bootstrap servers
                      password:
                        type: string
                        title: Password
                        format: password
                  dependencies: [camel:core, camel:kamelet, camel:kafka]
                  template:
                    from:
                      uri: kamelet:source
                      steps:
                        - to:
                            uri: kafka:{{topic}}
                            parameters:
                              brokers: "{{bootstrapServers}}"
                """
                .formatted(name);
    }

    public static String actionYaml(String name, String body) {
        try (var stream =
                KameletTestSupport.class.getResourceAsStream("/semantic-actions/wsr-billing-action.kamelet.yaml")) {
            if (stream == null) throw new IllegalStateException("Missing test action");
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("wsr-billing-action", name)
                    .replace("Billing demo response", body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot load test action", e);
        }
    }
}
