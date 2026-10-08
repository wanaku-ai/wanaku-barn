package ai.wanaku.core.services.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

/**
 * A stored data store entry with server-managed record metadata.
 * <p>
 * The repository assigns the metadata on every write. Values supplied by clients are ignored.
 * Records written before the metadata existed load with {@code revision} 0 and no timestamps.
 * </p>
 */
public class DataStoreRecord extends DataStore {

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
    private long revision;

    public DataStoreRecord() {}

    /**
     * Creates a record with the content of the given data store and no metadata.
     *
     * @param dataStore the source data store
     * @return a new record
     */
    public static DataStoreRecord of(DataStore dataStore) {
        DataStoreRecord record = new DataStoreRecord();
        record.setId(dataStore.getId());
        record.setName(dataStore.getName());
        record.setData(dataStore.getData());
        record.setLabels(dataStore.getLabels() == null ? null : new HashMap<>(dataStore.getLabels()));
        return record;
    }

    /**
     * Creates a detached copy of this record, including its metadata.
     *
     * @return a new record
     */
    public DataStoreRecord copy() {
        DataStoreRecord copy = of(this);
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.createdBy = createdBy;
        copy.updatedBy = updatedBy;
        copy.revision = revision;
        return copy;
    }

    /**
     * Formats a revision as a strong HTTP entity tag, for example {@code "3"}.
     *
     * @param revision the revision
     * @return the quoted entity tag
     */
    public static String entityTag(long revision) {
        return "\"" + revision + "\"";
    }

    /**
     * Parses an {@code If-Match} value into an expected revision.
     *
     * @param value the header value; {@code null}, blank or {@code *} match any revision
     * @return the expected revision, or {@code null} when any revision matches
     * @throws IllegalArgumentException if the value is not a single numeric entity tag
     */
    public static Long parseEntityTag(String value) {
        if (value == null || value.isBlank() || value.trim().equals("*")) {
            return null;
        }
        String tag = value.trim();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
            tag = tag.substring(1, tag.length() - 1);
        }
        try {
            return Long.parseLong(tag);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid entity tag: %s".formatted(value));
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) return false;
        DataStoreRecord that = (DataStoreRecord) o;
        return revision == that.revision
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt)
                && Objects.equals(createdBy, that.createdBy)
                && Objects.equals(updatedBy, that.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), createdAt, updatedAt, createdBy, updatedBy, revision);
    }

    @Override
    public String toString() {
        return "DataStoreRecord{id='%s', name='%s', revision=%d, createdAt=%s, updatedAt=%s, labels=%s}"
                .formatted(getId(), getName(), revision, createdAt, updatedAt, getLabels());
    }
}
