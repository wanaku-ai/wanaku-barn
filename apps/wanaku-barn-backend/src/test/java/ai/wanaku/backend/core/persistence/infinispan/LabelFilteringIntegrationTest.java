package ai.wanaku.backend.core.persistence.infinispan;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import ai.wanaku.backend.core.persistence.api.DataStoreRepository;
import ai.wanaku.backend.support.NoOidcTestProfile;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(NoOidcTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LabelFilteringIntegrationTest {

    @Inject
    DataStoreRepository dataStoreRepository;

    @BeforeAll
    public void setupTestData() {
        if (dataStoreRepository instanceof AbstractInfinispanRepository) {
            dataStoreRepository.removeAll();
        }

        createEntry(
                "weather-forecast-1",
                "Weather Forecast",
                Map.of("category", "weather", "action", "forecast", "version", "1.0"));

        createEntry(
                "weather-current-1",
                "Current Weather",
                Map.of("category", "weather", "action", "current", "version", "1.0"));

        createEntry(
                "weather-forecast-2",
                "Advanced Forecast",
                Map.of("category", "weather", "action", "forecast", "version", "2.0"));

        createEntry(
                "news-headlines-1",
                "News Headlines",
                Map.of("category", "news", "action", "headlines", "version", "1.0"));

        createEntry("news-search-1", "News Search", Map.of("category", "news", "action", "search", "version", "1.0"));

        createEntry(
                "finance-stock-1", "Stock Price", Map.of("category", "finance", "action", "stock", "version", "1.0"));

        createEntry("entry-prod-1", "Production Entry", Map.of("environment", "production", "status", "stable"));

        createEntry("entry-dev-1", "Development Entry", Map.of("environment", "development", "status", "beta"));

        createEntry("old-entry-1", "Deprecated Entry", Map.of("deprecated", "true", "category", "weather"));

        createEntry("no-labels-1", "Entry Without Labels", Map.of());
    }

    private void createEntry(String id, String name, Map<String, String> labels) {
        DataStore entry = new DataStore(id, name, "data-" + id);
        entry.addLabels(labels);
        dataStoreRepository.persist(entry);
    }

    @Test
    @Order(1)
    public void testListAll() {
        List<DataStore> all = dataStoreRepository.listAll();
        assertEquals(10, all.size());
    }

    @Test
    @Order(2)
    public void testSimpleEquality() {
        List<DataStore> weatherEntries = dataStoreRepository.findAllFilterByLabelExpression("category=weather");
        assertEquals(4, weatherEntries.size());
        assertTrue(weatherEntries.stream().allMatch(e -> "weather".equals(e.getLabelValue("category"))));
    }

    @Test
    @Order(3)
    public void testAndExpression() {
        List<DataStore> forecastEntries =
                dataStoreRepository.findAllFilterByLabelExpression("category=weather & action=forecast");
        assertEquals(2, forecastEntries.size());
        assertTrue(forecastEntries.stream()
                .allMatch(e ->
                        "weather".equals(e.getLabelValue("category")) && "forecast".equals(e.getLabelValue("action"))));
    }

    @Test
    @Order(4)
    public void testNotExpression() {
        List<DataStore> nonForecastWeather =
                dataStoreRepository.findAllFilterByLabelExpression("category=weather & !action=forecast");
        assertEquals(2, nonForecastWeather.size());
        assertTrue(nonForecastWeather.stream()
                .allMatch(e -> "weather".equals(e.getLabelValue("category"))
                        && !"forecast".equals(e.getLabelValue("action"))));
    }

    @Test
    @Order(5)
    public void testOrExpression() {
        List<DataStore> weatherOrNews =
                dataStoreRepository.findAllFilterByLabelExpression("category=weather | category=news");
        assertEquals(6, weatherOrNews.size());
        assertTrue(weatherOrNews.stream().allMatch(e -> {
            String category = e.getLabelValue("category");
            return "weather".equals(category) || "news".equals(category);
        }));
    }

    @Test
    @Order(6)
    public void testComplexExpression() {
        List<DataStore> result =
                dataStoreRepository.findAllFilterByLabelExpression("(category=weather | category=news) & version=1.0");
        assertEquals(4, result.size());
        assertTrue(result.stream().allMatch(e -> {
            String category = e.getLabelValue("category");
            String version = e.getLabelValue("version");
            return ("weather".equals(category) || "news".equals(category)) && "1.0".equals(version);
        }));
    }

    @Test
    @Order(7)
    public void testNotEquals() {
        List<DataStore> nonProd = dataStoreRepository.findAllFilterByLabelExpression("environment!=production");
        assertFalse(nonProd.isEmpty());
        assertTrue(nonProd.stream().noneMatch(e -> "production".equals(e.getLabelValue("environment"))));
    }

    @Test
    @Order(8)
    public void testNegationOfDeprecated() {
        List<DataStore> activeWeather =
                dataStoreRepository.findAllFilterByLabelExpression("category=weather & !deprecated=true");
        assertEquals(3, activeWeather.size());
        assertTrue(activeWeather.stream()
                .allMatch(e -> "weather".equals(e.getLabelValue("category"))
                        && !"true".equals(e.getLabelValue("deprecated"))));
    }

    @Test
    @Order(9)
    public void testVersionFiltering() {
        List<DataStore> v2 = dataStoreRepository.findAllFilterByLabelExpression("version=2.0");
        assertEquals(1, v2.size());
        assertEquals("weather-forecast-2", v2.get(0).getId());
    }

    @Test
    @Order(10)
    public void testMultipleAndConditions() {
        List<DataStore> specific =
                dataStoreRepository.findAllFilterByLabelExpression("category=weather & action=forecast & version=1.0");
        assertEquals(1, specific.size());
        assertEquals("weather-forecast-1", specific.get(0).getId());
    }

    @Test
    @Order(11)
    public void testComplexNegation() {
        List<DataStore> result =
                dataStoreRepository.findAllFilterByLabelExpression("environment=production & !deprecated=true");
        assertEquals(1, result.size());
        assertEquals("entry-prod-1", result.get(0).getId());
    }

    @Test
    @Order(12)
    public void testNullLabelExpression() {
        List<DataStore> all = dataStoreRepository.findAllFilterByLabelExpression(null);
        assertEquals(10, all.size());
    }

    @Test
    @Order(13)
    public void testEmptyLabelExpression() {
        List<DataStore> all = dataStoreRepository.findAllFilterByLabelExpression("");
        assertEquals(10, all.size());
    }

    @Test
    @Order(14)
    public void testNoMatchingEntries() {
        List<DataStore> result = dataStoreRepository.findAllFilterByLabelExpression("category=nonexistent");
        assertEquals(0, result.size());
    }

    @Test
    @Order(15)
    public void testInvalidLabelExpression() {
        assertThrows(
                Exception.class,
                () -> dataStoreRepository.findAllFilterByLabelExpression("invalid syntax without operator"));
    }

    @Test
    @Order(16)
    public void testParenthesesPrecedence() {
        List<DataStore> result =
                dataStoreRepository.findAllFilterByLabelExpression("(category=weather | category=news) & version=1.0");
        assertEquals(4, result.size());
    }

    @Test
    @Order(17)
    public void testDoubleNegation() {
        List<DataStore> result = dataStoreRepository.findAllFilterByLabelExpression("!!category=weather");
        assertEquals(4, result.size());
    }

    @Test
    @Order(18)
    public void testStatusFiltering() {
        List<DataStore> stableEntries = dataStoreRepository.findAllFilterByLabelExpression("status=stable");
        assertEquals(1, stableEntries.size());
        assertEquals("entry-prod-1", stableEntries.get(0).getId());
    }

    @AfterAll
    public void cleanup() {
        if (dataStoreRepository instanceof AbstractInfinispanRepository) {
            dataStoreRepository.removeAll();
        }
    }
}
