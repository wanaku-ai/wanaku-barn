package ai.wanaku.core.services.api;

/**
 * A single problem found while validating a service catalog or service template package.
 *
 * @param path the location of the problem within the package. It is either the name of a file
 *        inside the package (e.g. {@code index.properties}), a file followed by the offending
 *        property (e.g. {@code index.properties#catalog.name}) or the name of the request field
 *        that is at fault (e.g. {@code data})
 * @param message a human-readable description of what is wrong and, whenever possible, how to fix it
 */
public record ValidationIssue(String path, String message) {}
