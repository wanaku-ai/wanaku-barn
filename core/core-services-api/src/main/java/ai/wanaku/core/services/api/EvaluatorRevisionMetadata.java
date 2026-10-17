package ai.wanaku.core.services.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata of one immutable evaluator configuration revision on the router.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluatorRevisionMetadata {

    private long id;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("activated_at")
    private String activatedAt;

    private String status;
    private String checksum;
    private String origin;
    private String actor;

    @JsonProperty("failure_reason")
    private String failureReason;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** The time when the revision became active, or {@code null} if it never did. */
    public String getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(String activatedAt) {
        this.activatedAt = activatedAt;
    }

    /** {@code active}, {@code superseded} or {@code rejected}. */
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    /** How the revision was created: {@code startup} or {@code api}. */
    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    /** Why the revision was rejected, or {@code null}. */
    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
