package ai.wanaku.backend.api.v1.semanticrouter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.PathParam;

import java.util.List;
import java.util.Map;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticAction;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticExpert;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreview;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPreviewRequest;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticResolvedPublication;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticRouterDefinition;
import ai.wanaku.backend.api.v1.semanticrouter.model.SemanticValidation;
import ai.wanaku.backend.audit.AuditContext;
import ai.wanaku.backend.audit.Audited;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/** Thin REST transport for router authoring. */
@ApplicationScoped
public class SemanticRouterResource implements SemanticRouterService {
    @Inject
    SemanticRouterBean bean;

    @Inject
    AuditContext auditContext;
    /** List eligible curated Kamelet actions. */
    @Override
    public WanakuResponse<List<SemanticAction>> actions(String definitionId) {
        return new WanakuResponse<>(bean.actions(definitionId));
    }
    /** List configured expert instances. */
    @Override
    public WanakuResponse<List<SemanticExpert>> experts() {
        return new WanakuResponse<>(bean.experts());
    }
    /** List saved router drafts. */
    @Override
    public WanakuResponse<List<SemanticRouterDefinition>> list() {
        return new WanakuResponse<>(bean.list());
    }
    /** Resolve an exact name to an immutable publication selection. */
    @Override
    public WanakuResponse<SemanticResolvedPublication> resolve(String name, String revision) {
        return new WanakuResponse<>(bean.resolve(name, revision));
    }
    /** Save a router draft. */
    @Override
    @Audited(operation = "semantic_router.create", targetType = "semantic_router")
    public WanakuResponse<SemanticRouterDefinition> create(@Valid SemanticRouterDefinition definition) {
        return new WanakuResponse<>(bean.save(null, definition));
    }
    /** Read a router draft. */
    @Override
    public WanakuResponse<SemanticRouterDefinition> get(@PathParam("id") String id) {
        return new WanakuResponse<>(bean.get(id));
    }
    /** Update a router draft. */
    @Override
    @Audited(operation = "semantic_router.update", targetType = "semantic_router")
    public WanakuResponse<SemanticRouterDefinition> update(
            @PathParam("id") String id, @Valid SemanticRouterDefinition definition) {
        return new WanakuResponse<>(bean.save(id, definition));
    }
    /** Remove a router draft. */
    @Override
    @Audited(operation = "semantic_router.remove", targetType = "semantic_router")
    public WanakuResponse<Void> remove(@PathParam("id") String id) {
        bean.remove(id);
        return new WanakuResponse<>();
    }
    /** Validate configuration without inference. */
    @Override
    public WanakuResponse<SemanticValidation> validate(SemanticRouterDefinition definition) {
        return new WanakuResponse<>(bean.validate(definition));
    }
    /** Returns generated file paths and contents for a complete draft without persistence or inference. */
    @Override
    public WanakuResponse<Map<String, String>> files(@Valid SemanticRouterDefinition definition) {
        return new WanakuResponse<>(bean.files(definition));
    }
    /** Classify without executing actions. */
    @Override
    public WanakuResponse<SemanticPreview> preview(@PathParam("id") String id, @Valid SemanticPreviewRequest request) {
        return new WanakuResponse<>(bean.preview(id, request));
    }
    /** Publish an immutable catalog revision. */
    @Override
    @Audited(operation = "semantic_router.publish", targetType = "semantic_router")
    public WanakuResponse<SemanticPublication> publish(@PathParam("id") String id) {
        SemanticPublication publication = bean.publish(id);
        auditContext.setPolicyRevision(publication.revision);
        return new WanakuResponse<>(publication);
    }
    /** List published revisions. */
    @Override
    public WanakuResponse<List<SemanticPublication>> revisions(@PathParam("id") String id) {
        return new WanakuResponse<>(bean.revisions(id));
    }
}
