package ai.wanaku.core.services.api;

import java.util.List;

/**
 * The outcome of validating a service catalog or service template package.
 *
 * @param type the type of package that was validated: {@code catalog} or {@code template}
 * @param name the catalog name declared in the package manifest, or null when it could not be read
 * @param valid whether the package is free of errors and can be deployed
 * @param errors the problems that prevent the package from being deployed
 * @param warnings the problems that do not prevent deployment but are likely mistakes
 */
public record ValidationResult(
        String type, String name, boolean valid, List<ValidationIssue> errors, List<ValidationIssue> warnings) {

    /** Type of a service catalog package. */
    public static final String TYPE_CATALOG = "catalog";

    /** Type of a service template package. */
    public static final String TYPE_TEMPLATE = "template";
}
