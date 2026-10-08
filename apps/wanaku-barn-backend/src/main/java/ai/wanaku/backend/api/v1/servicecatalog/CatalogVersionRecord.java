package ai.wanaku.backend.api.v1.servicecatalog;

import java.util.HashMap;
import java.util.Map;
import ai.wanaku.core.services.api.CatalogVersion;

/**
 * A stored catalog or template version: the metadata plus the immutable package content.
 * <p>
 * The content and the creation metadata never change. Only {@code activatedAt}, {@code rejected} and
 * {@code failureReason} are set after creation. The status is derived from the active version pointer of the
 * item, so it is not stored.
 * </p>
 */
public class CatalogVersionRecord extends CatalogVersion {
    private String data;
    private Map<String, String> labels = new HashMap<>();
    private boolean rejected;

    public static String key(String type, String name, long version) {
        return type + ":" + name + ":" + version;
    }

    public String key() {
        return key(getType(), getName(), getVersion());
    }

    /**
     * Returns the metadata with the given status, without the content.
     *
     * @param status the derived status
     * @return the metadata
     */
    public CatalogVersion metadata(String status) {
        CatalogVersion metadata = new CatalogVersion();
        metadata.copyFrom(this);
        metadata.setStatus(status);
        return metadata;
    }

    /** The Base64-encoded ZIP package, or {@code null} for a rejected version. */
    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    /** The labels that the deploy request supplied. */
    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public boolean isRejected() {
        return rejected;
    }

    public void setRejected(boolean rejected) {
        this.rejected = rejected;
    }
}
