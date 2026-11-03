package ai.wanaku.cli.main.support;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NamespaceOptionsTest {

    private NamespaceOptions createWithNamespace(String name) throws Exception {
        NamespaceOptions options = new NamespaceOptions();
        Field field = NamespaceOptions.class.getDeclaredField("namespace");
        field.setAccessible(true);
        field.set(options, name);
        return options;
    }

    @Test
    void getNamespaceReturnsProvidedValue() throws Exception {
        NamespaceOptions options = createWithNamespace("finance");

        assertEquals("finance", options.getNamespace());
    }

    @Test
    void getNamespaceValueReturnsProvidedValue() throws Exception {
        NamespaceOptions options = createWithNamespace("finance");

        assertEquals("finance", options.getNamespaceValue());
    }

    @Test
    void getNamespaceReturnsNullWhenNotSet() {
        NamespaceOptions options = new NamespaceOptions();

        assertNull(options.getNamespace());
    }
}
