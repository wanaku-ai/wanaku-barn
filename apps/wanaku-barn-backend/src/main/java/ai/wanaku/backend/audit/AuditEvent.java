package ai.wanaku.backend.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * An audit event. The JSON form uses the field names of the Wanaku audit schema version 1.0.
 * <p>
 * Barn records administrative events only. The event never contains request or response bodies.
 * </p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEvent {

    public static final String SCHEMA_VERSION = "1.0";

    public static final String CATEGORY_ADMINISTRATIVE = "administrative";

    public static final String DECISION_ALLOW = "allow";
    public static final String DECISION_REJECT_MALFORMED = "reject_malformed";
    public static final String DECISION_ERROR = "error";

    /** Barn records management operations received over HTTP. */
    public static final String PROTOCOL_HTTP = "http";

    private String schemaVersion = SCHEMA_VERSION;
    private String eventId;
    private String streamId;
    private long sequence;

    @JsonIgnore
    private long timestampMillis;

    private String category = CATEGORY_ADMINISTRATIVE;
    private String decision;
    private String correlationId;
    private String requestId;
    private String actor;
    private String protocol = PROTOCOL_HTTP;
    private String operation;
    private String targetType;
    private String target;
    private String reasonCode;
    private String explanation;
    private String policyRevision;
    private Integer responseStatus;
    private Long durationMs;
    private RedactionMetadata redaction = new RedactionMetadata();
    private Map<String, String> attributes = new TreeMap<>();
    private boolean coverageComplete = true;
    private long droppedEvents;

    public AuditEvent() {}

    /**
     * Creates an administrative event with a new identifier and the current time.
     * The correlation identifier defaults to the event identifier.
     *
     * @param operation the operation, for example {@code service_catalog.deploy}
     * @param decision the decision
     * @param reasonCode a stable reason code
     * @param explanation a fixed, human-readable explanation
     * @return the event
     */
    public static AuditEvent administrative(String operation, String decision, String reasonCode, String explanation) {
        AuditEvent event = new AuditEvent();
        event.eventId = UUID.randomUUID().toString();
        event.correlationId = event.eventId;
        event.timestampMillis = Instant.now().truncatedTo(ChronoUnit.MILLIS).toEpochMilli();
        event.operation = operation;
        event.decision = decision;
        event.reasonCode = reasonCode;
        event.explanation = explanation;
        return event;
    }

    /** The event time as an ISO 8601 UTC timestamp. */
    public String getTimestamp() {
        return Instant.ofEpochMilli(timestampMillis).toString();
    }

    /** Sets the event time from an ISO 8601 UTC timestamp, for an import. */
    public void setTimestamp(String timestamp) {
        this.timestampMillis = Instant.parse(timestamp).toEpochMilli();
    }

    @JsonIgnore
    public long getTimestampMillis() {
        return timestampMillis;
    }

    public void setTimestampMillis(long timestampMillis) {
        this.timestampMillis = timestampMillis;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getPolicyRevision() {
        return policyRevision;
    }

    public void setPolicyRevision(String policyRevision) {
        this.policyRevision = policyRevision;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public RedactionMetadata getRedaction() {
        return redaction;
    }

    public void setRedaction(RedactionMetadata redaction) {
        this.redaction = redaction;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public boolean isCoverageComplete() {
        return coverageComplete;
    }

    public void setCoverageComplete(boolean coverageComplete) {
        this.coverageComplete = coverageComplete;
    }

    public long getDroppedEvents() {
        return droppedEvents;
    }

    public void setDroppedEvents(long droppedEvents) {
        this.droppedEvents = droppedEvents;
    }

    /**
     * Describes what the event omits. Barn never captures payloads.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RedactionMetadata {
        private List<String> redactedFields = new ArrayList<>();
        private boolean payloadCaptured;
        private boolean payloadTruncated;

        public List<String> getRedactedFields() {
            return redactedFields;
        }

        public void setRedactedFields(List<String> redactedFields) {
            this.redactedFields = redactedFields;
        }

        public boolean isPayloadCaptured() {
            return payloadCaptured;
        }

        public void setPayloadCaptured(boolean payloadCaptured) {
            this.payloadCaptured = payloadCaptured;
        }

        public boolean isPayloadTruncated() {
            return payloadTruncated;
        }

        public void setPayloadTruncated(boolean payloadTruncated) {
            this.payloadTruncated = payloadTruncated;
        }
    }
}
