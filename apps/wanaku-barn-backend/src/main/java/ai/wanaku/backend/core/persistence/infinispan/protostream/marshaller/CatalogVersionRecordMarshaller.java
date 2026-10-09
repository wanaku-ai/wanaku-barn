package ai.wanaku.backend.core.persistence.infinispan.protostream.marshaller;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import org.infinispan.protostream.MessageMarshaller;
import ai.wanaku.backend.api.v1.servicecatalog.CatalogVersionRecord;

/**
 * Protostream marshaller for catalog and template versions.
 */
public class CatalogVersionRecordMarshaller implements MessageMarshaller<CatalogVersionRecord> {

    @Override
    public CatalogVersionRecord readFrom(ProtoStreamReader reader) throws IOException {
        CatalogVersionRecord record = new CatalogVersionRecord();
        record.setType(reader.readString("type"));
        record.setName(reader.readString("name"));
        Long version = reader.readLong("version");
        record.setVersion(version == null ? 0 : version);
        record.setCreatedAt(toInstant(reader.readLong("created_at")));
        record.setActivatedAt(toInstant(reader.readLong("activated_at")));
        record.setChecksum(reader.readString("checksum"));
        record.setOrigin(reader.readString("origin"));
        record.setActor(reader.readString("actor"));
        record.setFailureReason(reader.readString("failure_reason"));
        Long restoredFrom = reader.readLong("restored_from");
        record.setRestoredFrom(restoredFrom == null || restoredFrom == 0 ? null : restoredFrom);
        record.setDataStoreName(reader.readString("data_store_name"));
        record.setData(reader.readString("data"));
        record.setLabels(reader.readMap("labels", new HashMap<>(), String.class, String.class));
        record.setRejected(Boolean.TRUE.equals(reader.readBoolean("rejected")));
        return record;
    }

    @Override
    public void writeTo(ProtoStreamWriter writer, CatalogVersionRecord record) throws IOException {
        writer.writeString("type", record.getType());
        writer.writeString("name", record.getName());
        writer.writeLong("version", record.getVersion());
        writer.writeLong("created_at", toMillis(record.getCreatedAt()));
        writer.writeLong("activated_at", toMillis(record.getActivatedAt()));
        writer.writeString("checksum", record.getChecksum());
        writer.writeString("origin", record.getOrigin());
        writer.writeString("actor", record.getActor());
        writer.writeString("failure_reason", record.getFailureReason());
        writer.writeLong("restored_from", record.getRestoredFrom() == null ? 0 : record.getRestoredFrom());
        writer.writeString("data_store_name", record.getDataStoreName());
        writer.writeString("data", record.getData());
        writer.writeMap("labels", record.getLabels(), String.class, String.class);
        writer.writeBoolean("rejected", record.isRejected());
    }

    @Override
    public Class<? extends CatalogVersionRecord> getJavaClass() {
        return CatalogVersionRecord.class;
    }

    @Override
    public String getTypeName() {
        return CatalogVersionRecord.class.getCanonicalName();
    }

    private static Instant toInstant(Long millis) {
        return millis == null || millis == 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static long toMillis(Instant instant) {
        return instant == null ? 0 : instant.toEpochMilli();
    }
}
