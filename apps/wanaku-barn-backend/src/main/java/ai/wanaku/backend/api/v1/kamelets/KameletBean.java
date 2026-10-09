package ai.wanaku.backend.api.v1.kamelets;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import ai.wanaku.backend.api.v1.kamelets.model.KameletDefinition;
import ai.wanaku.backend.api.v1.kamelets.model.KameletSummary;
import ai.wanaku.backend.api.v1.kamelets.model.KameletUpload;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.exceptions.EntityAlreadyExistsException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

/** Stores immutable native Kamelet revisions and a current catalog selection for each uploaded name. */
@ApplicationScoped
public class KameletBean {
    public static final String REVISION = "kamelet-revision";
    public static final String CURRENT = "kamelet-current";
    private final Map<String, KameletDefinition> local = new LinkedHashMap<>();

    @Inject
    DataStoreRepository repository;

    @Inject
    KameletParser parser;

    @ConfigProperty(name = "wanaku.semantic.actions-directory")
    Optional<String> actionsDirectory;

    @PostConstruct
    void init() {
        try {
            for (String name : List.of("wsr-billing-action", "wsr-technical-action")) {
                try (InputStream stream =
                        getClass().getResourceAsStream("/semantic-actions/" + name + ".kamelet.yaml")) {
                    if (stream == null) throw new IOException("Missing bundled Kamelet " + name);
                    addLocal(stream.readAllBytes(), "bundled");
                }
            }
            if (actionsDirectory.isPresent()) {
                try (var files = Files.list(Path.of(actionsDirectory.get()))) {
                    for (Path file : files.filter(
                                    p -> p.getFileName().toString().endsWith(".kamelet.yaml"))
                            .sorted()
                            .toList()) {
                        if (Files.size(file) > KameletParser.MAX_BYTES)
                            throw new IOException("Configured Kamelet exceeds 1 MiB");
                        addLocal(Files.readAllBytes(file), "configured");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load local Kamelet catalog", e);
        }
    }

    private void addLocal(byte[] bytes, String source) throws IOException {
        String yaml = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        KameletDefinition definition = parser.parse(yaml, source);
        if (local.putIfAbsent(definition.name, definition) != null)
            throw new IOException("Duplicate local Kamelet " + definition.name);
    }

    /** Lists current resources in stable name order.
     * @return current definitions without YAML contents */
    public List<KameletSummary> list() {
        List<KameletSummary> result = new ArrayList<>();
        for (KameletDefinition definition : local.values()) result.add(new KameletSummary(definition));
        for (DataStore pointer : repository.findAllFilterByLabelExpression("wanaku.type=" + CURRENT)) {
            if (pointer.getLabels() == null)
                throw new IllegalStateException("Stored Kamelet current selection is inconsistent");
            String name = pointer.getLabels().get("kamelet.name");
            verifyPointer(pointer, name);
            if (local.containsKey(name)) throw new IllegalStateException("Uploaded Kamelet conflicts with local name");
            result.add(new KameletSummary(get(name, pointer.getData())));
        }
        result.sort(Comparator.comparing(d -> d.name));
        return result;
    }

    /** Reads exact original YAML and verifies immutable metadata.
     * @param name native name
     * @param sha256 optional revision pin
     * @return validated definition */
    public KameletDefinition get(String name, String sha256) {
        requireName(name);
        if (sha256 != null && !sha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Use a lowercase SHA-256 digest");
        KameletDefinition localDefinition = local.get(name);
        if (localDefinition != null && (sha256 == null || sha256.equals(localDefinition.sha256)))
            return parser.parse(localDefinition.yaml, localDefinition.source);
        String selected = sha256;
        DataStore pointer = repository.findById(currentId(name));
        if (pointer != null) verifyPointer(pointer, name);
        if (selected == null) {
            if (pointer == null) throw new NotFoundException("Current Kamelet not found");
            selected = pointer.getData();
        }
        DataStore entry = repository.findById(revisionId(name, selected));
        if (entry == null) throw new NotFoundException("Kamelet revision not found");
        KameletDefinition result = verified(entry, name, selected);
        result.removable = "uploaded".equals(result.source) && pointer != null && selected.equals(pointer.getData());
        return result;
    }

    /** Uploads a validated revision and selects it atomically by name.
     * @param upload original YAML
     * @return current revision metadata */
    public synchronized KameletSummary upload(KameletUpload upload) {
        KameletDefinition definition = parser.parse(upload == null ? null : upload.yaml, "uploaded");
        if (local.containsKey(definition.name)) throw EntityAlreadyExistsException.forName(definition.name);
        retain(definition);
        DataStore pointer = new DataStore();
        pointer.setId(currentId(definition.name));
        pointer.setName(currentId(definition.name));
        pointer.setLabels(Map.of("wanaku.type", CURRENT, "kamelet.name", definition.name, "kamelet.immutable", "true"));
        pointer.setData(definition.sha256);
        DataStore previous = repository.findById(pointer.getId());
        if (previous != null) verifyPointer(previous, definition.name);
        repository.persist(pointer);
        definition.removable = true;
        return new KameletSummary(definition);
    }

    /** Retains exact selected local bytes only during accepted save/publication operations.
     * @param definition exact selected definition */
    public void retain(KameletDefinition definition) {
        KameletDefinition checked = parser.parse(definition.yaml, definition.source);
        if (!checked.name.equals(definition.name) || !checked.sha256.equals(definition.sha256))
            throw new IllegalStateException("Kamelet revision bytes do not match their metadata");
        DataStore entry = new DataStore();
        entry.setId(revisionId(definition.name, definition.sha256));
        entry.setName(entry.getId());
        entry.setLabels(Map.of(
                "wanaku.type",
                REVISION,
                "kamelet.name",
                definition.name,
                "kamelet.sha256",
                definition.sha256,
                "kamelet.source",
                definition.source,
                "kamelet.immutable",
                "true"));
        entry.setData(definition.yaml);
        DataStore previous = repository.persistIfAbsent(entry);
        if (previous != null) {
            KameletDefinition existing = verified(previous, definition.name, definition.sha256);
            if (!existing.yaml.equals(definition.yaml))
                throw new IllegalStateException("Immutable Kamelet content conflict");
        }
    }

    /** Removes only the current uploaded selection, retaining historical bytes.
     * @param name native name */
    public synchronized void remove(String name) {
        requireName(name);
        if (local.containsKey(name)) throw EntityAlreadyExistsException.forName(name);
        DataStore pointer = repository.findById(currentId(name));
        if (pointer == null) throw new NotFoundException("Current Kamelet not found");
        verifyPointer(pointer, name);
        if (!repository.deleteById(pointer.getId())) throw new NotFoundException("Current Kamelet not found");
    }

    /** Derives a deterministic current-selection identifier.
     * @param name native name
     * @return record identifier */
    public static String currentId(String name) {
        return "kamelet-current-" + name;
    }
    /** Derives a content-addressed revision identifier.
     * @param name native name
     * @param sha256 digest
     * @return record identifier */
    public static String revisionId(String name, String sha256) {
        return "kamelet-revision-" + name + "-" + sha256;
    }

    private KameletDefinition verified(DataStore entry, String name, String sha256) {
        Map<String, String> labels = entry.getLabels();
        if (labels == null
                || !REVISION.equals(labels.get("wanaku.type"))
                || !name.equals(labels.get("kamelet.name"))
                || !sha256.equals(labels.get("kamelet.sha256"))
                || !"true".equals(labels.get("kamelet.immutable"))
                || !revisionId(name, sha256).equals(entry.getId())
                || !entry.getId().equals(entry.getName())
                || !List.of("bundled", "configured", "uploaded").contains(labels.get("kamelet.source")))
            throw new IllegalStateException("Stored Kamelet revision metadata is inconsistent");
        KameletDefinition definition;
        try {
            definition = parser.parse(entry.getData(), labels.get("kamelet.source"));
        } catch (ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException e) {
            throw new IllegalStateException("Stored Kamelet revision is invalid", e);
        }
        if (!name.equals(definition.name) || !sha256.equals(definition.sha256))
            throw new IllegalStateException("Stored Kamelet revision digest is inconsistent");
        return definition;
    }

    private static void verifyPointer(DataStore pointer, String name) {
        if (pointer.getLabels() == null
                || !CURRENT.equals(pointer.getLabels().get("wanaku.type"))
                || !name.equals(pointer.getLabels().get("kamelet.name"))
                || !"true".equals(pointer.getLabels().get("kamelet.immutable"))
                || !currentId(name).equals(pointer.getId())
                || !pointer.getId().equals(pointer.getName())
                || pointer.getData() == null
                || !pointer.getData().matches("[a-f0-9]{64}"))
            throw new IllegalStateException("Stored Kamelet current selection is inconsistent");
    }

    private static void requireName(String name) {
        if (!KameletParser.validName(name)) throw new IllegalArgumentException("Invalid native Kamelet name");
    }
}
