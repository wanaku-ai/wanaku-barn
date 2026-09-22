package ai.wanaku.cli.main.support;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import ai.wanaku.core.config.WanakuConfig;

@ConfigMapping(prefix = "wanaku.cli")
public interface WanakuCliConfig extends WanakuConfig {

    interface Auth {
        @WithDefault("none")
        String mode();

        @WithDefault("~/.wanaku/credentials")
        String credentialsFile();

        @WithDefault("false")
        boolean enabled();
    }

    Auth auth();
}
