package ai.wanaku.backend.audit;

/**
 * Filter and pagination values for an audit query. Blank values do not filter.
 *
 * @param from the earliest timestamp (ISO 8601, inclusive)
 * @param to the latest timestamp (ISO 8601, inclusive)
 * @param operation the operation
 * @param target the target
 * @param decision the decision
 * @param reasonCode the reason code
 * @param correlationId the correlation identifier
 * @param actor the actor
 * @param offset the number of events to skip
 * @param limit the maximum number of events to return
 */
public record AuditFilterCriteria(
        String from,
        String to,
        String operation,
        String target,
        String decision,
        String reasonCode,
        String correlationId,
        String actor,
        int offset,
        int limit) {}
