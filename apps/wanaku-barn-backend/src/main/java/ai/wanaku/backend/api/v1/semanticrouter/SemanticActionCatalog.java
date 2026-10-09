package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import ai.wanaku.backend.api.v1.kamelets.KameletBean;
import ai.wanaku.backend.api.v1.kamelets.KameletParser;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticAction;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticActionSelection;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticExpert;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Adapts current and pinned native Kamelets for semantic routing, with separately configured experts. */
@ApplicationScoped
public class SemanticActionCatalog {
    public static final String PROFILE = KameletParser.PROFILE;
    private final Map<String, SemanticExpert> experts = new LinkedHashMap<>();

    @Inject
    KameletBean kamelets;

    @Inject
    KameletParser parser;

    @ConfigProperty(name = "wanaku.semantic.experts-file")
    Optional<String> expertsFile;

    @PostConstruct
    void init() {
        try {
            if (expertsFile.isPresent()) {
                if (Files.size(Path.of(expertsFile.get())) > 64 * 1024)
                    throw new IOException("Expert catalog exceeds 64 KiB");
                List<SemanticExpert> configured = new ObjectMapper()
                        .readValue(Files.readString(Path.of(expertsFile.get())), new TypeReference<>() {});
                for (SemanticExpert expert : configured) registerExpert(expert);
            } else {
                SemanticExpert expert = new SemanticExpert();
                expert.id = "support";
                expert.name = "Support expert (deployment configured)";
                expert.bean = "supportExpert";
                expert.dependency = "org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT";
                registerExpert(expert);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load semantic expert catalog", e);
        }
    }

    private void registerExpert(SemanticExpert expert) throws IOException {
        if (expert == null
                || expert.id == null
                || !expert.id.matches("[a-z][a-z0-9_-]{0,63}")
                || expert.bean == null
                || !expert.bean.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                || expert.dependency == null
                || !expert.dependency.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
                || expert.supportsConfidence
                || experts.putIfAbsent(expert.id, expert) != null) throw new IOException("Invalid configured expert");
    }

    /** Returns current eligible actions in stable catalog order. @return current semantic actions */
    public List<SemanticAction> actions() {
        List<SemanticAction> result = new ArrayList<>();
        for (var summary : kamelets.list()) {
            if (!summary.semanticEligible) continue;
            SemanticAction action = parser.action(kamelets.get(summary.name, summary.sha256));
            action.current = true;
            result.add(action);
        }
        return result;
    }

    /** Includes exact selected historical revisions alongside current actions. @param definition optional stored definition @return eligible current and selected actions */
    public List<SemanticAction> actions(SemanticRouterDefinition definition) {
        List<SemanticAction> result = actions();
        if (definition == null || definition.actions == null) return result;
        for (SemanticActionSelection selection : definition.actions) {
            SemanticAction selected = action(selection);
            if (selected != null
                    && result.stream().noneMatch(a -> a.id.equals(selected.id) && a.sha256.equals(selected.sha256))) {
                selected.current = false;
                result.add(selected);
            }
        }
        return result;
    }

    /** Binds unpinned selections once per authoring operation without persistence. @param definition draft configuration */
    public void bind(SemanticRouterDefinition definition) {
        if (definition == null || definition.actions == null) return;
        for (SemanticActionSelection selection : definition.actions) {
            if (selection == null || selection.sha256 != null) continue;
            SemanticAction action = action(selection);
            if (action != null) selection.sha256 = action.sha256;
        }
    }

    /** Retains accepted selection bytes before saving or publishing a definition. @param definition bound draft configuration */
    public void retain(SemanticRouterDefinition definition) {
        if (definition.actions == null) return;
        for (SemanticActionSelection selection : definition.actions)
            if (selection != null && action(selection) != null)
                kamelets.retain(kamelets.get(selection.actionId, selection.sha256));
    }

    /** Returns configured expert identifiers without credentials. @return configured expert metadata */
    public List<SemanticExpert> experts() {
        return new ArrayList<>(experts.values());
    }

    SemanticAction action(SemanticActionSelection selection) {
        if (selection == null
                || !KameletParser.validName(selection.actionId)
                || selection.sha256 != null && !selection.sha256.matches("[a-f0-9]{64}")) return null;
        try {
            return parser.action(kamelets.get(selection.actionId, selection.sha256));
        } catch (NotFoundException e) {
            return null;
        }
    }

    SemanticExpert expert(String id) {
        return experts.get(id);
    }

    byte[] resource(SemanticActionSelection selection) {
        return KameletParser.bytes(kamelets.get(selection.actionId, selection.sha256).yaml);
    }
}
