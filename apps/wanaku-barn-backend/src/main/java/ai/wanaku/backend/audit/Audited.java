package ai.wanaku.backend.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST resource method as an audited management operation.
 * {@link AuditFilter} records one event for each request to the method, after the outcome is known.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

    /**
     * The operation name, for example {@code service_catalog.deploy}.
     *
     * @return the operation name
     */
    String operation();

    /**
     * The type of the changed resource, for example {@code service_catalog}.
     *
     * @return the target type
     */
    String targetType();

    /**
     * The response field that identifies the target when no {@code id} or {@code name} parameter does.
     *
     * @return {@code id} or {@code name}
     */
    String targetField() default "id";
}
