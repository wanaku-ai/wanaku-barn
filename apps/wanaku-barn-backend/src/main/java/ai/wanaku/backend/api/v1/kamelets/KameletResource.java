package ai.wanaku.backend.api.v1.kamelets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.core.Response;

import java.util.List;
import ai.wanaku.backend.api.v1.kamelets.model.KameletDefinition;
import ai.wanaku.backend.api.v1.kamelets.model.KameletSummary;
import ai.wanaku.backend.api.v1.kamelets.model.KameletUpload;
import ai.wanaku.backend.audit.Audited;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

/** Thin REST transport for remote native Kamelet resources. */
@ApplicationScoped
public class KameletResource implements KameletService {
    @Inject
    KameletBean bean;
    /** Returns current metadata.
     * @return current catalog */
    @Override
    public WanakuResponse<List<KameletSummary>> list() {
        return new WanakuResponse<>(bean.list());
    }
    /** Uploads original YAML.
     * @param upload definition
     * @return selected metadata */
    @Override
    @Audited(operation = "kamelet.upload", targetType = "kamelet", targetField = "name")
    public WanakuResponse<KameletSummary> upload(@Valid KameletUpload upload) {
        return new WanakuResponse<>(bean.upload(upload));
    }
    /** Reads a definition.
     * @param name native name
     * @param sha256 optional digest
     * @return definition */
    @Override
    public WanakuResponse<KameletDefinition> get(String name, String sha256) {
        return new WanakuResponse<>(bean.get(name, sha256));
    }
    /** Serves raw native YAML.
     * @param name native name
     * @param sha256 optional digest
     * @return exact bytes with a SHA-256 ETag */
    @Override
    public Response download(String name, String sha256) {
        KameletDefinition definition = bean.get(name, sha256);
        return Response.ok(KameletParser.bytes(definition.yaml), "application/yaml")
                .tag(definition.sha256)
                .build();
    }
    /** Removes a current uploaded selection.
     * @param name native name
     * @return empty success response */
    @Override
    @Audited(operation = "kamelet.remove", targetType = "kamelet")
    public WanakuResponse<Void> remove(String name) {
        bean.remove(name);
        return new WanakuResponse<>();
    }
}
