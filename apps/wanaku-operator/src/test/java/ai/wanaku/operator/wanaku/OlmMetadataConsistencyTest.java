package ai.wanaku.operator.wanaku;

import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class OlmMetadataConsistencyTest {

    @Test
    void bundleMetadataHasRequiredCSVFields() {
        CSVMetadata csv = WanakuBundleMetadata.class.getAnnotation(CSVMetadata.class);
        assertThat(csv).as("@CSVMetadata on WanakuBundleMetadata").isNotNull();
        assertThat(csv.displayName()).isNotBlank();
        assertThat(csv.description()).isNotBlank();
        assertThat(csv.keywords()).isNotEmpty();
        assertThat(csv.provider().name()).isNotBlank();
        assertThat(csv.maintainers()).isNotEmpty();
        assertThat(csv.links()).isNotEmpty();
        assertThat(csv.installModes()).hasSizeGreaterThanOrEqualTo(4);

        CSVMetadata.Annotations annotations = csv.annotations();
        assertThat(annotations.containerImage()).isNotBlank();
        assertThat(annotations.capabilities()).isNotBlank();
        assertThat(annotations.categories()).isNotBlank();
        assertThat(annotations.repository()).isNotBlank();
    }

    // ── #2: every Reconciler references the shared bundle ─────────────────

    @ParameterizedTest
    @ValueSource(
            classes = {
                WanakuRouterReconciler.class,
                WanakuServiceCatalogReconciler.class,
                WanakuSemanticRouterReconciler.class,
                WanakuIntegrationReconciler.class
            })
    void reconcilerReferencesBundleName(Class<?> reconciler) {
        CSVMetadata csv = reconciler.getAnnotation(CSVMetadata.class);
        assertThat(csv).as("@CSVMetadata on " + reconciler.getSimpleName()).isNotNull();
        assertThat(csv.bundleName())
                .as("bundleName on " + reconciler.getSimpleName())
                .isEqualTo(WanakuBundleMetadata.BUNDLE_NAME);
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                WanakuRouter.class,
                WanakuServiceCatalog.class,
                WanakuSemanticRouter.class,
                WanakuIntegration.class
            })
    void customResourceHasCSVMetadata(Class<?> crd) {
        CSVMetadata csv = crd.getAnnotation(CSVMetadata.class);
        assertThat(csv).as("@CSVMetadata on " + crd.getSimpleName()).isNotNull();
        assertThat(csv.displayName())
                .as("displayName on " + crd.getSimpleName())
                .isNotBlank();
        assertThat(csv.description())
                .as("description on " + crd.getSimpleName())
                .isNotBlank();
    }
}
