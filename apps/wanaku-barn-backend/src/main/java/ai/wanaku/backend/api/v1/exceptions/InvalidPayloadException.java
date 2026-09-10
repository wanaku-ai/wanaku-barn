package ai.wanaku.backend.api.v1.exceptions;

import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;

/**
 * Exception thrown when a request payload is well-formed JSON, but cannot be processed
 * because required content is missing. Mapped to HTTP 422 (Unprocessable Entity).
 */
public class InvalidPayloadException extends WanakuException {
    public InvalidPayloadException(String message) {
        super(message);
    }
}
