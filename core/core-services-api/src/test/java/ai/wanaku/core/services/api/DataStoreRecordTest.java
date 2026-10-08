package ai.wanaku.core.services.api;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataStoreRecordTest {

    @Test
    void entityTagIsQuoted() {
        assertEquals("\"3\"", DataStoreRecord.entityTag(3));
    }

    @Test
    void parsesEntityTags() {
        assertEquals(3L, DataStoreRecord.parseEntityTag("\"3\""));
        assertEquals(3L, DataStoreRecord.parseEntityTag(" W/\"3\" "));
        assertEquals(3L, DataStoreRecord.parseEntityTag("3"));
        assertNull(DataStoreRecord.parseEntityTag(null));
        assertNull(DataStoreRecord.parseEntityTag(" "));
        assertNull(DataStoreRecord.parseEntityTag("*"));
        assertThrows(IllegalArgumentException.class, () -> DataStoreRecord.parseEntityTag("\"abc\""));
        assertThrows(IllegalArgumentException.class, () -> DataStoreRecord.parseEntityTag("\"1\", \"2\""));
    }

    @Test
    void copyIsDetached() {
        DataStoreRecord record = DataStoreRecord.of(new DataStore("id", "name", "data"));
        record.setRevision(4);
        record.addLabel("a", "b");

        DataStoreRecord copy = record.copy();
        copy.addLabel("c", "d");

        assertEquals(record.getRevision(), copy.getRevision());
        assertNull(record.getLabels().get("c"));
    }

    @Test
    void clientUpdateSendsTheRevisionThatWasRead() {
        AtomicReference<String> sentIfMatch = new AtomicReference<>("not called");
        DataStoresService service = recordingService(sentIfMatch);

        DataStoreRecord read = DataStoreRecord.of(new DataStore("id", "name", "data"));
        read.setRevision(5);
        service.update(read);
        assertEquals("\"5\"", sentIfMatch.get());

        service.update(new DataStore("id", "name", "data"));
        assertNull(sentIfMatch.get(), "Plain entries carry no revision");

        service.update(DataStoreRecord.of(new DataStore("id", "name", "data")));
        assertNull(sentIfMatch.get(), "Entries without a known revision send no precondition");
    }

    private static DataStoresService recordingService(AtomicReference<String> sentIfMatch) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (method.getName().equals("update") && args.length == 2) {
                sentIfMatch.set((String) args[0]);
                return new WanakuResponse<Void>();
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (DataStoresService) Proxy.newProxyInstance(
                DataStoresService.class.getClassLoader(), new Class<?>[] {DataStoresService.class}, handler);
    }
}
