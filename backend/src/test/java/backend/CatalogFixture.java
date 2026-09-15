package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.UUID;

class CatalogFixture {
    final ObjectMapper mapper = new ObjectMapper();
    final DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:catalog-" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final SearchCatalog dictionary = new SearchCatalog(mapper);
    final PlaceQueries queries = new PlaceQueries(jdbc, dictionary);
    CatalogFixture() {
        jdbc.execute("CREATE TABLE places(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(300),address VARCHAR(600),region VARCHAR(120),category VARCHAR(80),category_label VARCHAR(120),image VARCHAR(1000),mapx VARCHAR(80),mapy VARCHAR(80),approved BOOLEAN,source VARCHAR(80),tags VARCHAR(1000),legal_area_code VARCHAR(2),legal_district_code VARCHAR(5))");
        queries.initialize();
    }
    TourCatalog catalog(String url, int limit) {
        var catalog = new TourCatalog(jdbc, mapper, dictionary, "test-key", url, queries, new DataSourceTransactionManager(source), limit);
        catalog.initialize(); return catalog;
    }
}
