package ai.wanaku.backend.core.persistence.infinispan.protostream.marshaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;
import org.infinispan.protostream.MessageMarshaller;
import ai.wanaku.backend.audit.AuditEvent;

/**
 * Protostream marshaller for audit events.
 */
public class AuditEventMarshaller implements MessageMarshaller<AuditEvent> {

    @Override
    public AuditEvent readFrom(ProtoStreamReader reader) throws IOException {
        AuditEvent event = new AuditEvent();
        event.setSchemaVersion(reader.readString("schema_version"));
        event.setEventId(reader.readString("event_id"));
        event.setStreamId(reader.readString("stream_id"));
        event.setSequence(orZero(reader.readLong("sequence")));
        event.setTimestampMillis(orZero(reader.readLong("timestamp")));
        event.setCategory(reader.readString("category"));
        event.setDecision(reader.readString("decision"));
        event.setCorrelationId(reader.readString("correlation_id"));
        event.setRequestId(reader.readString("request_id"));
        event.setActor(reader.readString("actor"));
        event.setProtocol(reader.readString("protocol"));
        event.setOperation(reader.readString("operation"));
        event.setTargetType(reader.readString("target_type"));
        event.setTarget(reader.readString("target"));
        event.setReasonCode(reader.readString("reason_code"));
        event.setExplanation(reader.readString("explanation"));
        event.setPolicyRevision(reader.readString("policy_revision"));
        Integer status = reader.readInt("response_status");
        event.setResponseStatus(status == null || status == 0 ? null : status);
        Long duration = reader.readLong("duration_ms");
        event.setDurationMs(duration);
        event.getRedaction()
                .setRedactedFields(reader.readCollection("redacted_fields", new ArrayList<>(), String.class));
        event.getRedaction().setPayloadCaptured(Boolean.TRUE.equals(reader.readBoolean("payload_captured")));
        event.getRedaction().setPayloadTruncated(Boolean.TRUE.equals(reader.readBoolean("payload_truncated")));
        event.setAttributes(reader.readMap("attributes", new TreeMap<>(), String.class, String.class));
        event.setCoverageComplete(Boolean.TRUE.equals(reader.readBoolean("coverage_complete")));
        event.setDroppedEvents(orZero(reader.readLong("dropped_events")));
        return event;
    }

    @Override
    public void writeTo(ProtoStreamWriter writer, AuditEvent event) throws IOException {
        writer.writeString("schema_version", event.getSchemaVersion());
        writer.writeString("event_id", event.getEventId());
        writer.writeString("stream_id", event.getStreamId());
        writer.writeLong("sequence", event.getSequence());
        writer.writeLong("timestamp", event.getTimestampMillis());
        writer.writeString("category", event.getCategory());
        writer.writeString("decision", event.getDecision());
        writer.writeString("correlation_id", event.getCorrelationId());
        writer.writeString("request_id", event.getRequestId());
        writer.writeString("actor", event.getActor());
        writer.writeString("protocol", event.getProtocol());
        writer.writeString("operation", event.getOperation());
        writer.writeString("target_type", event.getTargetType());
        writer.writeString("target", event.getTarget());
        writer.writeString("reason_code", event.getReasonCode());
        writer.writeString("explanation", event.getExplanation());
        writer.writeString("policy_revision", event.getPolicyRevision());
        writer.writeInt("response_status", event.getResponseStatus());
        writer.writeLong("duration_ms", event.getDurationMs());
        writer.writeCollection("redacted_fields", event.getRedaction().getRedactedFields(), String.class);
        writer.writeBoolean("payload_captured", event.getRedaction().isPayloadCaptured());
        writer.writeBoolean("payload_truncated", event.getRedaction().isPayloadTruncated());
        writer.writeMap("attributes", event.getAttributes(), String.class, String.class);
        writer.writeBoolean("coverage_complete", event.isCoverageComplete());
        writer.writeLong("dropped_events", event.getDroppedEvents());
    }

    @Override
    public Class<? extends AuditEvent> getJavaClass() {
        return AuditEvent.class;
    }

    @Override
    public String getTypeName() {
        return AuditEvent.class.getCanonicalName();
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
