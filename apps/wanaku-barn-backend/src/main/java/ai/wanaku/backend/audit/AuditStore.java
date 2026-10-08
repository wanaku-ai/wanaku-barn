package ai.wanaku.backend.audit;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.commons.api.query.Query;
import org.infinispan.commons.api.query.QueryResult;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.logging.Logger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stores administrative audit events in a dedicated Infinispan cache.
 * <p>
 * Events are keyed by their sequence number. The next sequence number is stored in a separate cache, so it
 * survives a restart even after retention removed the newest events. The store keeps the newest
 * {@code wanaku.audit.max-records} events.
 * </p>
 * <p>
 * Recording never throws: a storage failure increments the dropped-event count, marks the store unhealthy
 * and increments the {@code wanaku.audit.storage.failures} metric. The next stored event reports the gap.
 * </p>
 */
@ApplicationScoped
public class AuditStore {
    private static final Logger LOG = Logger.getLogger(AuditStore.class);

    static final String EVENTS_CACHE = "audit-event";
    static final String STATE_CACHE = "audit-state";
    private static final String SEQUENCE_KEY = "sequence";
    private static final String STORAGE_ERROR = "audit storage unavailable";
    private static final String EVENT_TYPE = AuditEvent.class.getCanonicalName();

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    @Inject
    EmbeddedCacheManager cacheManager;

    @Inject
    Configuration configuration;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "wanaku.audit.max-records", defaultValue = "10000")
    int maxRecords;

    @ConfigProperty(name = "wanaku.audit.stream-id", defaultValue = "barn")
    String streamId;

    private final ReentrantLock lock = new ReentrantLock();
    private Counter failures;
    private long droppedEvents;
    private long gap;
    private String lastError;

    @PostConstruct
    void init() {
        for (String name : List.of(EVENTS_CACHE, STATE_CACHE)) {
            if (cacheManager.getCacheConfiguration(name) == null) {
                cacheManager.defineConfiguration(name, configuration);
            }
        }
        failures = Counter.builder("wanaku.audit.storage.failures")
                .description("Audit events that could not be stored")
                .register(meterRegistry);
        try {
            lock.lock();
            applyRetention(currentSequence());
        } catch (RuntimeException e) {
            LOG.errorf(
                    "Failed to apply audit retention at startup: %s",
                    e.getClass().getName());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Assigns the stream, sequence and gap information, then stores the event. Never throws.
     *
     * @param event the event to store
     */
    public void record(AuditEvent event) {
        try {
            lock.lock();
            long sequence = currentSequence() + 1;
            event.setStreamId(streamId);
            event.setSequence(sequence);
            event.setCoverageComplete(gap == 0);
            event.setDroppedEvents(gap);
            state().put(SEQUENCE_KEY, sequence);
            events().put(sequence, event);
            gap = 0;
            lastError = null;
            applyRetention(sequence);
        } catch (RuntimeException e) {
            droppedEvents++;
            gap++;
            lastError = STORAGE_ERROR;
            failures.increment();
            // Never log event content: the target or attributes can contain sensitive names
            LOG.errorf(
                    "Failed to store an audit event for operation %s: %s",
                    event.getOperation(), e.getClass().getName());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the events that match the filter, newest first.
     *
     * @param filter the filter
     * @return one page of events
     * @throws IllegalArgumentException if a filter value is not valid
     */
    public AuditPage query(AuditFilterCriteria filter) {
        int offset = Math.max(0, filter.offset());
        int limit = filter.limit() <= 0 ? DEFAULT_LIMIT : Math.min(filter.limit(), MAX_LIMIT);

        Map<String, Object> parameters = new LinkedHashMap<>();
        addTime(parameters, "from", filter.from());
        addTime(parameters, "to", filter.to());
        add(parameters, "operation", filter.operation());
        add(parameters, "targetType", filter.targetType());
        add(parameters, "target", filter.target());
        add(parameters, "decision", filter.decision());
        add(parameters, "reasonCode", filter.reasonCode());
        add(parameters, "actor", filter.actor());

        String where = parameters.keySet().stream()
                .map(name -> switch (name) {
                    case "from" -> "e.timestampMillis >= :from";
                    case "to" -> "e.timestampMillis <= :to";
                    default -> "e.%s = :%s".formatted(name, name);
                })
                .collect(Collectors.joining(" and "));
        String statement =
                "from %s e%s order by e.sequence desc".formatted(EVENT_TYPE, where.isEmpty() ? "" : " where " + where);

        Query<AuditEvent> query = events().query(statement);
        parameters.forEach(query::setParameter);
        query.startOffset(offset).maxResults(limit);
        QueryResult<AuditEvent> result = query.execute();
        return new AuditPage(result.list(), offset, limit, result.count().value());
    }

    /**
     * Returns one event.
     *
     * @param eventId the event identifier
     * @return the event, or {@code null} if no retained event has the identifier
     */
    public AuditEvent get(String eventId) {
        Query<AuditEvent> query = events().query("from %s e where e.eventId = :id".formatted(EVENT_TYPE));
        query.setParameter("id", eventId);
        List<AuditEvent> found = query.execute().list();
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Returns the health of the store.
     *
     * @return the health
     */
    public AuditHealth health() {
        long retained;
        try {
            retained = events().size();
        } catch (RuntimeException e) {
            return new AuditHealth(false, droppedEvents, 0, maxRecords, STORAGE_ERROR);
        }
        return new AuditHealth(lastError == null, droppedEvents, retained, maxRecords, lastError);
    }

    private long currentSequence() {
        Long sequence = state().get(SEQUENCE_KEY);
        return sequence == null ? 0 : sequence;
    }

    /** Removes the events that are older than the newest {@code maxRecords} sequence numbers. */
    private void applyRetention(long newest) {
        long oldestToRemove = newest - maxRecords;
        if (oldestToRemove <= 0) {
            return;
        }
        // Normally only one event falls out of the window; the delete statement also covers a reduced limit
        if (events().remove(oldestToRemove) == null && events().size() > maxRecords) {
            Query<AuditEvent> delete =
                    events().query("delete from %s e where e.sequence <= :cutoff".formatted(EVENT_TYPE));
            delete.setParameter("cutoff", oldestToRemove);
            delete.executeStatement();
        }
    }

    private static void add(Map<String, Object> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.put(name, value);
        }
    }

    private static void addTime(Map<String, Object> parameters, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            parameters.put(name, Instant.parse(value).toEpochMilli());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'%s' must be an ISO 8601 UTC timestamp".formatted(name));
        }
    }

    private Cache<Long, AuditEvent> events() {
        return cacheManager.getCache(EVENTS_CACHE);
    }

    private Cache<String, Long> state() {
        return cacheManager.getCache(STATE_CACHE);
    }

    /** Clears all events and the sequence. For testing only. */
    void clear() {
        events().clear();
        state().clear();
        droppedEvents = 0;
        gap = 0;
        lastError = null;
    }
}
