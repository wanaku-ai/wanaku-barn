package ai.wanaku.core.services.api;

import java.time.Instant;

/**
 * Metadata of one immutable version of a service catalog or service template.
 */
public class CatalogVersion {

    /** The version is the active content of the catalog or template. */
    public static final String STATUS_ACTIVE = "active";

    /** The version was active and a later version replaced it. */
    public static final String STATUS_SUPERSEDED = "superseded";

    /** The version was never activated. */
    public static final String STATUS_REJECTED = "rejected";

    private String type;
    private String name;
    private long version;
    private String status;
    private Instant createdAt;
    private Instant activatedAt;
    private String checksum;
    private String origin;
    private String actor;
    private String failureReason;
    private Long restoredFrom;
    private String dataStoreName;

    public CatalogVersion() {}

    /** Copies the metadata of another version. */
    public void copyFrom(CatalogVersion other) {
        type = other.type;
        name = other.name;
        version = other.version;
        status = other.status;
        createdAt = other.createdAt;
        activatedAt = other.activatedAt;
        checksum = other.checksum;
        origin = other.origin;
        actor = other.actor;
        failureReason = other.failureReason;
        restoredFrom = other.restoredFrom;
        dataStoreName = other.dataStoreName;
    }

    /** The item type: {@code catalog} or {@code template}. */
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /** The catalog or template name from {@code index.properties}. */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The version number. Starts at 1 and increments for each deploy of the same name. */
    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    /** {@code active}, {@code superseded} or {@code rejected}. */
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** The time when the version became active, or {@code null} if it never did. */
    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    /** The SHA-256 digest of the decoded ZIP package, in lowercase hexadecimal. */
    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    /** How the version was created: {@code api}, {@code startup}, {@code instantiate}, {@code restore} or {@code legacy}. */
    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    /** The actor that created the version. Empty: Barn has no identity source. */
    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    /** Why the version was rejected, or {@code null}. */
    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    /** The version that this version restored, or {@code null}. */
    public Long getRestoredFrom() {
        return restoredFrom;
    }

    public void setRestoredFrom(Long restoredFrom) {
        this.restoredFrom = restoredFrom;
    }

    /** The data store name that the deploy request used, for example {@code weather.service.zip}. */
    public String getDataStoreName() {
        return dataStoreName;
    }

    public void setDataStoreName(String dataStoreName) {
        this.dataStoreName = dataStoreName;
    }
}
