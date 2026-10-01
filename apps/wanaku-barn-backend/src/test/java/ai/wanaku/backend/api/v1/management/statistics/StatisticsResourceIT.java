package ai.wanaku.backend.api.v1.management.statistics;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import ai.wanaku.backend.support.TestIndexHelper;
import ai.wanaku.backend.support.WanakuTestResource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIf;

@QuarkusIntegrationTest
@QuarkusTestResource(value = WanakuTestResource.class, restrictToAnnotatedClass = true)
@DisabledIf(value = "isUnsupportedOSOnGithub", disabledReason = "Does not run on macOS or Windows on GitHub")
public class StatisticsResourceIT extends AbstractStatisticsResourceTest {

    @BeforeAll
    static void setup() {
        TestIndexHelper.clearAllCaches();
    }
}
