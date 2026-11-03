package ai.wanaku.operator.util;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WanakuForwardsTest {

    @Test
    void forwardResponsesAreParsedFromTheEnvelope() throws IOException {
        WanakuForwards.Forward forward =
                WanakuForwards.parse("{\"data\":{\"name\":\"support\",\"available\":false,\"statusMessage\":\"down\","
                        + "\"labels\":{\"wsr.instance\":\"abc\"}},\"error\":null}");

        assertThat(forward.available).isFalse();
        assertThat(forward.statusMessage).isEqualTo("down");
        assertThat(forward.labels).containsEntry("wsr.instance", "abc");
    }

    @Test
    void missingFieldsUseServerDefaults() throws IOException {
        WanakuForwards.Forward forward = WanakuForwards.parse("{\"data\":{\"name\":\"support\"}}");

        assertThat(forward.available).isTrue();
        assertThat(forward.statusMessage).isNull();
        assertThat(forward.labels).isEmpty();
    }
}
