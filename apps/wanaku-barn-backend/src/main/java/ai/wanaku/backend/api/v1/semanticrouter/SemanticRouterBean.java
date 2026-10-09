package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import ai.wanaku.backend.api.v1.exceptions.InvalidPayloadException;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticAction;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticExpert;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreview;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreviewRequest;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticResolvedPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticValidation;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogValidator;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogZipReader;
import ai.wanaku.backend.api.v1.servicecatalog.ServiceCatalogBean;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Authoring persistence and publication are separate from runtime activation. */
@ApplicationScoped
public class SemanticRouterBean {
    private static final Logger LOG = Logger.getLogger(SemanticRouterBean.class);
    static final String TYPE = "semantic-definition";
    static final String PUBLICATION = "semantic-publication";

    @Inject
    DataStoreRepository repository;

    @Inject
    ObjectMapper mapper;

    @Inject
    SemanticActionCatalog catalog;

    @Inject
    SemanticDefinitionValidator validator;

    @Inject
    SemanticCatalogGenerator generator;

    @Inject
    ServiceCatalogBean serviceCatalog;

    @Inject
    CatalogValidator catalogValidator;

    @Inject
    SemanticPreviewClient previewClient;

    @Inject
    SemanticPublicationResolver publicationResolver;

    /** Returns eligible curated actions. */
    public List<SemanticAction> actions(String definitionId) {
        return catalog.actions(definitionId == null ? null : get(definitionId));
    }
    /** Returns configured expert identifiers. */
    public List<SemanticExpert> experts() {
        return catalog.experts();
    }
    /** Lists stored drafts in name order. */
    public List<SemanticRouterDefinition> list() {
        return repository.findAllFilterByLabelExpression("wanaku.type=" + TYPE).stream()
                .map(ds -> decode(ds.getData(), SemanticRouterDefinition.class))
                .sorted(Comparator.comparing(d -> d.name == null ? "" : d.name))
                .toList();
    }
    /** Loads a definition by its own persisted identifier. */
    public SemanticRouterDefinition get(String id) {
        return decode(stored(id).getData(), SemanticRouterDefinition.class);
    }
    /** Resolves an exact saved name to immutable publication pins, rejecting duplicate display names. */
    public SemanticResolvedPublication resolve(String name, String revision) {
        List<SemanticRouterDefinition> matches =
                list().stream().filter(d -> name.equals(d.name)).toList();
        if (matches.isEmpty()) throw new NotFoundException("Router definition not found");
        if (matches.size() != 1)
            throw new SemanticResolutionConflictException(
                    "Several router definitions have this name; use a unique saved name");
        String id = matches.getFirst().id;
        return publicationResolver.resolve(name, id, revision, revisions(id));
    }
    /** Saves an incomplete draft after storage and credential-reference checks. */
    public SemanticRouterDefinition save(String id, SemanticRouterDefinition definition) {
        catalog.bind(definition);
        SemanticValidation validation = validator.validate(definition, true);
        if (!validation.valid)
            throw new InvalidPayloadException(
                    validation.errors.getFirst().field + ": " + validation.errors.getFirst().message);
        DataStore data = id == null ? new DataStore() : stored(id);
        if (id == null) id = UUID.randomUUID().toString();
        definition.id = id;
        data.setId(id);
        data.setName("semantic-router-" + id);
        data.setLabels(Map.of("wanaku.type", TYPE));
        data.setData(encode(definition));
        if (data.getData().length() > 128 * 1024) throw new InvalidPayloadException("Definition exceeds 128 KiB");
        catalog.retain(definition);
        repository.persist(data);
        return definition;
    }
    /** Removes a draft while preserving immutable published revisions. */
    public void remove(String id) {
        stored(id);
        if (!repository.deleteById(id)) throw new NotFoundException("Router definition not found");
    }
    /** Performs structural validation without inference. */
    public SemanticValidation validate(SemanticRouterDefinition definition) {
        catalog.bind(definition);
        return validator.validate(definition, false);
    }
    /**
     * Generates the same catalog resources as publication without storage or expert calls.
     *
     * @param definition the complete draft configuration to inspect
     * @return generated file paths and UTF-8 contents using preview publication placeholders
     * @throws InvalidPayloadException if the configuration is invalid
     */
    public Map<String, String> files(SemanticRouterDefinition definition) {
        requireValid(definition);
        return CatalogZipReader.readEntriesAsText(generator.generate(definition, "preview", "semantic-preview"));
    }
    /** Evaluates an example through the dedicated classification service. */
    public SemanticPreview preview(String id, SemanticPreviewRequest request) {
        SemanticRouterDefinition definition = get(id);
        requireValid(definition);
        if (request == null || request.message == null || request.message.isBlank() || request.message.length() > 8192)
            throw new InvalidPayloadException("Example message is required and must not exceed 8192 characters");
        return previewClient.evaluate(definition, request.message);
    }
    /** Publishes or reuses a byte-identical immutable revision without activating a runtime. */
    public synchronized SemanticPublication publish(String id) {
        SemanticRouterDefinition definition = get(id);
        requireValid(definition);
        catalog.retain(definition);
        String revision = "r"
                + SemanticCatalogGenerator.digest(generator.generate(definition, "candidate", "candidate"))
                        .substring(0, 24);
        String name = "semantic-" + id + "-" + revision;
        byte[] archive = generator.generate(definition, revision, name);
        String digest = SemanticCatalogGenerator.digest(archive);
        for (SemanticPublication existing : revisions(id))
            if (existing.revision.equals(revision)) {
                if (!existing.sha256.equals(digest))
                    throw new IllegalStateException("Published revision digest conflict");
                publicationResolver.remember(existing);
                return existing;
            }
        DataStore data = new DataStore();
        data.setName(name);
        data.setData(Base64.getEncoder().encodeToString(archive));
        data.setLabels(Map.of(
                "wanaku.type",
                "catalog",
                "semantic.immutable",
                "true",
                "semantic.definition",
                id,
                "semantic.revision",
                revision,
                "semantic.sha256",
                digest));
        var checked = catalogValidator.validateCatalog(data);
        if (!checked.valid())
            throw new InvalidPayloadException("Generated catalog failed validation: " + checked.errors());
        DataStore alreadyPublished = serviceCatalog.get(name);
        if (alreadyPublished != null
                && !SemanticCatalogGenerator.digest(Base64.getDecoder().decode(alreadyPublished.getData()))
                        .equals(digest))
            throw new IllegalStateException("Immutable catalog already exists with another digest");
        if (alreadyPublished == null) serviceCatalog.deploy(data);
        SemanticPublication publication = new SemanticPublication();
        publication.definitionId = id;
        publication.toolName = definition.toolName;
        publication.expert = mapper.convertValue(catalog.expert(definition.expertId), SemanticExpert.class);
        publication.revision = revision;
        publication.catalogName = name;
        publication.sha256 = digest;
        publication.mainFile = SemanticCatalogGenerator.MAIN;
        publication.camelVersion = SemanticCatalogGenerator.CAMEL_VERSION;
        publication.camelBuild = SemanticCatalogGenerator.CAMEL_BUILD;
        publication.status = "published";
        publication.downloadUrl = "/api/v1/service-catalog/download?name=" + name;
        publication.deploymentInstructions = List.of(
                "Configure the WSR catalog URL with the Barn origin and " + publication.downloadUrl,
                "Set wsr.catalog.name=" + name,
                "Set wsr.catalog.service=service",
                "Set wsr.catalog.revision=" + revision,
                "Set wsr.catalog.sha256=" + digest,
                "Select main YAML " + publication.mainFile,
                "Configure expert bean " + catalog.expert(definition.expertId).bean
                        + " and external credential references",
                "Restart or replace WSR with this revision; publication does not start WSR");
        DataStore metadata = new DataStore();
        metadata.setName(name + "-publication");
        metadata.setLabels(Map.of("wanaku.type", PUBLICATION, "semantic.definition", id));
        metadata.setData(encode(publication));
        repository.persist(metadata);
        publicationResolver.remember(publication);
        LOG.infof("Published semantic catalog %s at revision %s", name, revision);
        return publication;
    }
    /** Lists published revisions independently of runtime readiness. */
    public List<SemanticPublication> revisions(String id) {
        stored(id);
        List<SemanticPublication> publications = new ArrayList<>();
        for (DataStore entry : repository.findAllFilterByLabelExpression("wanaku.type=" + PUBLICATION))
            if (id.equals(entry.getLabels().get("semantic.definition")))
                publications.add(decode(entry.getData(), SemanticPublication.class));
        publications.sort(Comparator.comparing(p -> p.revision));
        return publications;
    }

    private DataStore stored(String id) {
        DataStore data = repository.findById(id);
        if (data == null
                || data.getLabels() == null
                || !TYPE.equals(data.getLabels().get("wanaku.type")))
            throw new NotFoundException("Router definition not found");
        return data;
    }

    private void requireValid(SemanticRouterDefinition definition) {
        SemanticValidation checked = validate(definition);
        if (!checked.valid)
            throw new InvalidPayloadException(
                    checked.errors.getFirst().field + ": " + checked.errors.getFirst().message);
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot store semantic authoring data", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (IOException e) {
            throw new IllegalStateException("Stored semantic authoring data is invalid", e);
        }
    }
}
