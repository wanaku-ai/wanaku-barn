package ai.wanaku.backend.api.v1.servicecatalog;

import jakarta.inject.Inject;

import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import ai.wanaku.backend.support.CatalogZips;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.core.services.api.CatalogVersion;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
@TestProfile(CatalogVersionTrimmingTest.SmallHistory.class)
class CatalogVersionTrimmingTest {

    public static class SmallHistory implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("wanaku.catalog.max-versions", "3");
        }
    }

    @Inject
    ServiceCatalogBean catalogs;

    @Inject
    CatalogLifecycle lifecycle;

    @Test
    void keepsTheNewestVersions() {
        for (int i = 1; i <= 5; i++) {
            catalogs.deploy(new DataStore(null, "trim.zip", CatalogZips.base64("trim", "v" + i, "sys")));
        }

        assertThat(lifecycle.versions("catalog", "trim"))
                .extracting(CatalogVersion::getVersion)
                .containsExactly(5L, 4L, 3L);
    }

    @Test
    void keepsTheActiveVersionWhenItIsTheOldest() {
        DataStore published = new DataStore(null, "pinned.zip", CatalogZips.base64("pinned", "v1", "sys"));
        published.setLabels(Map.of("semantic.immutable", "true"));
        catalogs.deploy(published);
        for (int i = 2; i <= 6; i++) {
            DataStore rejected = new DataStore(null, "pinned.zip", CatalogZips.base64("pinned", "v" + i, "sys"));
            assertThatThrownBy(() -> catalogs.deploy(rejected)).isInstanceOf(WanakuException.class);
        }

        assertThat(lifecycle.versions("catalog", "pinned"))
                .extracting(CatalogVersion::getVersion, CatalogVersion::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(6L, "rejected"),
                        org.assertj.core.groups.Tuple.tuple(5L, "rejected"),
                        org.assertj.core.groups.Tuple.tuple(1L, "active"));
    }
}
