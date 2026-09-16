package backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SearchPaginationTests {
    @Test void oneScanKeepsTotalsOrderingFiltersAndLastPageClamping() {
        var db = new CatalogFixture();
        for (int i=1;i<=15;i++) db.jdbc.update("INSERT INTO places(name,address,region,category,category_label,approved,source) VALUES(?,?,?,'tour','관광지',TRUE,'pagination')",
                "해변"+String.format("%02d",i), i<=8 ? "강원 강릉시" : "제주 제주시", i<=8 ? "강원" : "제주");
        db.queries.indexSource("pagination");
        db.jdbc.update("UPDATE places SET approved=FALSE WHERE name='해변15'");
        db.jdbc.update("UPDATE places SET tour_visible=FALSE WHERE name='해변14'");
        for (String sort : List.of("name","recommended")) {
            var all = db.queries.search("바다","","",sort,1,60);
            assertThat(all.total()).isEqualTo(13);
            var combined = new ArrayList<Map<String,Object>>();
            for (int page=1;page<=3;page++) {
                var result = db.queries.search("바다","","",sort,page,6);
                assertThat(result.total()).isEqualTo(13);
                assertThat(result.page()).isEqualTo(page);
                assertThat(result.hasMore()).isEqualTo(page<3);
                combined.addAll(result.rows());
                assertThat(result.rows()).allMatch(row -> !row.containsKey("result_total"));
            }
            assertThat(combined).isEqualTo(all.rows());
            var beyond = db.queries.search("바다","","",sort,100000,6);
            assertThat(beyond.page()).isEqualTo(3);
            assertThat(beyond.rows()).isEqualTo(all.rows().subList(12,13));
            var region = db.queries.search("바다","강원","",sort,2,6);
            assertThat(region.total()).isEqualTo(8);
            assertThat(region.rows()).hasSize(2).allMatch(row -> "강원".equals(row.get("region")));
        }
        for (int page : List.of(1,99)) {
            var empty=db.queries.search("일치하지않는검색어","","","recommended",page,6);
            assertThat(empty.total()).isZero(); assertThat(empty.page()).isEqualTo(1);
            assertThat(empty.rows()).isEmpty(); assertThat(empty.hasMore()).isFalse();
        }
        db.jdbc.execute("SHUTDOWN");
    }
}
