package ai.wanaku.backend.api.v1.semanticrouter;

/** A router name or its current published selection is ambiguous. */
public class SemanticResolutionConflictException extends RuntimeException {
    /** Creates a conflict with a message that explains how to select a publication. */
    public SemanticResolutionConflictException(String message) {
        super(message);
    }
}
