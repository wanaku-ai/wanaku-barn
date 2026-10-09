package ai.wanaku.backend.core.persistence.infinispan.protostream.schema;

import org.infinispan.protostream.SerializationContext;
import ai.wanaku.backend.core.persistence.infinispan.protostream.marshaller.AuditEventMarshaller;

/**
 * Schema initializer for audit events.
 */
public class AuditEventSchema extends AbstractWanakuSerializationContextInitializer {

    @Override
    public String getName() {
        return "audit_event.proto";
    }

    @Override
    public void registerMarshallers(SerializationContext serCtx) {
        serCtx.registerMarshaller(new AuditEventMarshaller());
    }
}
