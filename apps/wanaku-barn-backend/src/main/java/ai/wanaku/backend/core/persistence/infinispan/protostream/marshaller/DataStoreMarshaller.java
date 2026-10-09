package ai.wanaku.backend.core.persistence.infinispan.protostream.marshaller;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import org.infinispan.protostream.MessageMarshaller;
import ai.wanaku.backend.core.persistence.infinispan.StoredDataStore;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

/**
 * Protostream marshaller for DataStore entity serialization.
 * <p>
 * Entries are stored as {@link StoredDataStore} under the original {@code DataStore} message name,
 * so records written before the metadata fields existed remain readable.
 * </p>
 */
public class DataStoreMarshaller implements MessageMarshaller<StoredDataStore> {

    @Override
    public StoredDataStore readFrom(ProtoStreamReader reader) throws IOException {
        StoredDataStore dataStore = new StoredDataStore();
        dataStore.setId(reader.readString("id"));
        dataStore.setName(reader.readString("name"));
        dataStore.setData(reader.readString("data"));
        dataStore.setLabels(reader.readMap("labels", new HashMap<>(), String.class, String.class));
        dataStore.setCreatedAt(toInstant(reader.readLong("created_at")));
        dataStore.setUpdatedAt(toInstant(reader.readLong("updated_at")));
        dataStore.setCreatedBy(reader.readString("created_by"));
        dataStore.setUpdatedBy(reader.readString("updated_by"));
        Long revision = reader.readLong("revision");
        dataStore.setRevision(revision == null ? 0 : revision);
        return dataStore;
    }

    @Override
    public void writeTo(ProtoStreamWriter writer, StoredDataStore dataStore) throws IOException {
        writer.writeString("id", dataStore.getId());
        writer.writeString("name", dataStore.getName());
        writer.writeString("data", dataStore.getData());
        writer.writeMap("labels", dataStore.getLabels(), String.class, String.class);
        writer.writeLong("created_at", toMillis(dataStore.getCreatedAt()));
        writer.writeLong("updated_at", toMillis(dataStore.getUpdatedAt()));
        writer.writeString("created_by", dataStore.getCreatedBy());
        writer.writeString("updated_by", dataStore.getUpdatedBy());
        writer.writeLong("revision", dataStore.getRevision());
    }

    @Override
    public Class<? extends StoredDataStore> getJavaClass() {
        return StoredDataStore.class;
    }

    @Override
    public String getTypeName() {
        return DataStore.class.getCanonicalName();
    }

    private static Instant toInstant(Long millis) {
        return millis == null || millis == 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static long toMillis(Instant instant) {
        return instant == null ? 0 : instant.toEpochMilli();
    }
}
