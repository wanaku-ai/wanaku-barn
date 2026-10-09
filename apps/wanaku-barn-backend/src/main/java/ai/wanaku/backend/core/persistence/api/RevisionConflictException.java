package ai.wanaku.backend.core.persistence.api;

import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;

/**
 * Thrown when a write expects a revision that is different from the stored revision.
 * The REST API maps this exception to HTTP 409 (Conflict).
 */
public class RevisionConflictException extends WanakuException {

    private final long currentRevision;

    public RevisionConflictException(String id, long expectedRevision, long currentRevision) {
        super("Entry %s is at revision %d, but revision %d was expected"
                .formatted(id, currentRevision, expectedRevision));
        this.currentRevision = currentRevision;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }
}
