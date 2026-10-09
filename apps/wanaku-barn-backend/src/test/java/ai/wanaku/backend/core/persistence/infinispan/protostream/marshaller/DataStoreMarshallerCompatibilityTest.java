package ai.wanaku.backend.core.persistence.infinispan.protostream.marshaller;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.MessageMarshaller;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import ai.wanaku.backend.core.persistence.infinispan.StoredDataStore;
import ai.wanaku.backend.core.persistence.infinispan.protostream.schema.DataStoreSchema;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that records written with the original four-field schema load with the current schema.
 */
class DataStoreMarshallerCompatibilityTest {

    private static final String LEGACY_SCHEMA =
            """
            syntax = "proto3";
            package ai.wanaku.capabilities.sdk.api.types;
            message DataStore {
              string id = 1;
              string name = 2;
              string data = 3;
              map<string, string> labels = 4;
            }
            """;

    @Test
    void legacyRecordLoadsWithDefaults() throws IOException {
        DataStore legacy = new DataStore("legacy-id", "legacy", "payload");
        legacy.setLabels(new HashMap<>(Map.of("wanaku.type", "catalog")));
        byte[] bytes = ProtobufUtil.toWrappedByteArray(legacyContext(), legacy);

        StoredDataStore loaded = (StoredDataStore) ProtobufUtil.fromWrappedByteArray(currentContext(), bytes);

        assertEquals("legacy-id", loaded.getId());
        assertEquals("legacy", loaded.getName());
        assertEquals("payload", loaded.getData());
        assertEquals("catalog", loaded.getLabels().get("wanaku.type"));
        assertEquals(0, loaded.getRevision());
        assertNull(loaded.getCreatedAt());
        assertNull(loaded.getUpdatedAt());
        assertNull(loaded.getCreatedBy());
    }

    @Test
    void metadataRoundTrips() throws IOException {
        StoredDataStore record = StoredDataStore.from(new DataStore("id", "name", "data"));
        record.setCreatedAt(Instant.ofEpochMilli(1_000));
        record.setUpdatedAt(Instant.ofEpochMilli(2_000));
        record.setRevision(7);

        SerializationContext ctx = currentContext();
        Object loaded = ProtobufUtil.fromWrappedByteArray(ctx, ProtobufUtil.toWrappedByteArray(ctx, record));

        assertEquals(record, loaded);
    }

    private static SerializationContext currentContext() {
        SerializationContext ctx = ProtobufUtil.newSerializationContext();
        DataStoreSchema schema = new DataStoreSchema();
        schema.registerSchema(ctx);
        schema.registerMarshallers(ctx);
        return ctx;
    }

    private static SerializationContext legacyContext() {
        SerializationContext ctx = ProtobufUtil.newSerializationContext();
        ctx.registerProtoFiles(FileDescriptorSource.fromString("data_store.proto", LEGACY_SCHEMA));
        ctx.registerMarshaller(new MessageMarshaller<DataStore>() {
            @Override
            public DataStore readFrom(ProtoStreamReader reader) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void writeTo(ProtoStreamWriter writer, DataStore dataStore) throws IOException {
                writer.writeString("id", dataStore.getId());
                writer.writeString("name", dataStore.getName());
                writer.writeString("data", dataStore.getData());
                writer.writeMap("labels", dataStore.getLabels(), String.class, String.class);
            }

            @Override
            public Class<DataStore> getJavaClass() {
                return DataStore.class;
            }

            @Override
            public String getTypeName() {
                return "ai.wanaku.capabilities.sdk.api.types.DataStore";
            }
        });
        return ctx;
    }
}
