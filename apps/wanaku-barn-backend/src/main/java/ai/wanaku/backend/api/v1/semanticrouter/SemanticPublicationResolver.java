package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticExpert;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticResolvedPublication;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogZipReader;
import ai.wanaku.backend.api.v1.servicecatalog.ServiceCatalogBean;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.ServiceCatalogIndex;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Selects persisted publications and verifies their metadata against immutable archives. */
@ApplicationScoped
public class SemanticPublicationResolver {
    static final String CURRENT = "semantic-current-publication";

    @Inject
    DataStoreRepository repository;

    @Inject
    ObjectMapper mapper;

    @Inject
    ServiceCatalogBean serviceCatalog;

    @Inject
    SemanticActionCatalog catalog;

    /** Records the last successful publication without changing its archive or the editable draft. */
    void remember(SemanticPublication publication) {
        DataStore current = new DataStore();
        current.setId(currentId(publication.definitionId));
        current.setName(current.getId());
        current.setLabels(Map.of(
                "wanaku.type", CURRENT, "semantic.definition", publication.definitionId, "semantic.immutable", "true"));
        try {
            current.setData(mapper.writeValueAsString(Map.of("revision", publication.revision)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot store current publication selection", e);
        }
        repository.persist(current);
    }

    static String currentId(String definitionId) {
        return "semantic-current-" + definitionId;
    }

    /** Resolves an explicit revision or the recorded current publication, without consulting draft configuration. */
    SemanticResolvedPublication resolve(
            String name, String id, String revision, List<SemanticPublication> publications) {
        SemanticPublication publication = select(id, revision, publications);
        DataStore stored = serviceCatalog.get(publication.catalogName);
        if (stored == null) throw new NotFoundException("Published catalog is unavailable");
        byte[] archive = Base64.getDecoder().decode(stored.getData());
        if (!SemanticCatalogGenerator.digest(archive).equals(publication.sha256))
            throw new IllegalStateException("Published catalog digest does not match its publication");
        ServiceCatalogIndex index = ServiceCatalogIndex.fromZipBytes(archive);
        if (!publication.catalogName.equals(index.getName())
                || !index.getServiceNames().contains("service")
                || !publication.mainFile.equals(index.getRoutesFile("service")))
            throw new IllegalStateException("Published catalog index does not match its publication");
        Map<String, byte[]> entries = CatalogZipReader.readEntries(archive);
        Properties manifest = manifest(entries);
        if (!publication.revision.equals(manifest.getProperty("catalog.revision"))
                || !publication.mainFile.equals(manifest.getProperty("main"))
                || !publication.camelVersion.equals(manifest.getProperty("camel.version"))
                || publication.camelBuild != null
                        && !Objects.equals(publication.camelBuild, manifest.getProperty("camel.build")))
            throw new IllegalStateException("Published semantic manifest does not match its publication");
        String toolName = manifest.getProperty("tool.name");
        if (toolName == null
                || !toolName.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || publication.toolName != null && !publication.toolName.equals(toolName))
            throw new IllegalStateException("Published tool name does not match its publication");
        SemanticResolvedPublication result = new SemanticResolvedPublication();
        result.name = name;
        result.toolName = toolName;
        result.catalogName = publication.catalogName;
        result.service = "service";
        result.revision = publication.revision;
        result.sha256 = publication.sha256;
        result.mainFile = publication.mainFile;
        result.camelVersion = publication.camelVersion;
        result.camelBuild = manifest.getProperty("camel.build");
        result.downloadUrl = publication.downloadUrl;
        result.expert = expert(publication, manifest, entries);
        return result;
    }

    private SemanticPublication select(String id, String revision, List<SemanticPublication> publications) {
        if (publications.isEmpty()) throw new NotFoundException("Router has no published revision");
        String selected = revision;
        if (selected == null) {
            DataStore pointer = repository.findById(currentId(id));
            if (pointer != null) {
                selected = pointerRevision(id, pointer);
            } else if (publications.size() == 1
                    && publications.getFirst().toolName == null
                    && publications.getFirst().expert == null) {
                return publications.getFirst();
            } else {
                throw new SemanticResolutionConflictException(
                        "Router has no current publication selection; specify revision or publish the saved draft again");
            }
        }
        String expected = selected;
        return publications.stream()
                .filter(p -> expected.equals(p.revision))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Published revision not found"));
    }

    private String pointerRevision(String id, DataStore pointer) {
        if (pointer.getLabels() == null
                || pointer.getData() == null
                || pointer.getData().isBlank()
                || !CURRENT.equals(pointer.getLabels().get("wanaku.type"))
                || !id.equals(pointer.getLabels().get("semantic.definition")))
            throw new IllegalStateException("Current publication pointer is invalid");
        String revision;
        try {
            revision = mapper.readTree(pointer.getData()).path("revision").asText(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read current publication selection", e);
        }
        if (revision == null || revision.isBlank())
            throw new IllegalStateException("Current publication pointer has no revision");
        return revision;
    }

    private SemanticExpert expert(SemanticPublication publication, Properties manifest, Map<String, byte[]> entries) {
        String bean = manifest.getProperty("expert.bean");
        byte[] resource = entries.get(manifest.getProperty("dependencies"));
        if (resource == null) throw new IllegalStateException("Published dependency declaration is unavailable");
        List<String> dependencies = new String(resource, StandardCharsets.UTF_8)
                .lines()
                .map(String::strip)
                .toList();
        if (publication.expert != null) {
            if (!publication.expert.bean.equals(bean) || !dependencies.contains("mvn:" + publication.expert.dependency))
                throw new IllegalStateException("Published expert snapshot does not match its archive");
            return publication.expert;
        }
        List<SemanticExpert> matches = catalog.experts().stream()
                .filter(expert -> expert.bean.equals(bean) && dependencies.contains("mvn:" + expert.dependency))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private Properties manifest(Map<String, byte[]> entries) {
        byte[] resource = entries.get("service/semantic-router.properties");
        if (resource == null) throw new IllegalStateException("Published semantic manifest is unavailable");
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(resource));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read published semantic manifest", e);
        }
        return properties;
    }
}
