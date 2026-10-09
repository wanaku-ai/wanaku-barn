package ai.wanaku.backend;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "wanaku.router")
public interface WanakuRouterConfig {

    @WithDefault("100000000000")
    long namespaceAgeHardLimit();
}
