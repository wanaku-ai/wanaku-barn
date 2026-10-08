package ai.wanaku.backend.core.persistence.infinispan.protostream.schema;

import org.infinispan.protostream.SerializationContext;
import ai.wanaku.backend.core.persistence.infinispan.protostream.marshaller.CatalogVersionRecordMarshaller;

/**
 * Schema initializer for catalog and template versions.
 */
public class CatalogVersionRecordSchema extends AbstractWanakuSerializationContextInitializer {

    @Override
    public String getName() {
        return "catalog_version.proto";
    }

    @Override
    public void registerMarshallers(SerializationContext serCtx) {
        serCtx.registerMarshaller(new CatalogVersionRecordMarshaller());
    }
}
