package ai.wanaku.backend.support;

import java.util.HashMap;
import java.util.Map;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class WanakuTestResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        Map<String, String> conf = new HashMap<>();
        conf.put("wanaku.persistence.infinispan.base-folder", "target/wanaku/barn");
        conf.put("wanaku.persistence.infinispan.file-store", "false");
        conf.put("quarkus.log.console.enable", "false");
        conf.put("quarkus.log.file.enable", "true");
        conf.put("quarkus.log.file.path", "target/logs/wanaku-test.log");
        return conf;
    }

    @Override
    public void stop() {}
}
