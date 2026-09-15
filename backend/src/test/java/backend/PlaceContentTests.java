package backend;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlaceContentTests {
    @Test void officialTypesWinOverAttractiveWordsAndUnknownTypesStayUnknown() {
        assertThat(PlaceContent.classify(Map.of("name","카페와 숲길을 걷는 캠핑 코스","tour_content_type","25")))
                .extracting(PlaceContent.Classification::category,PlaceContent.Classification::kind,PlaceContent.Classification::label).containsExactly("course","course","여행 코스");
        assertThat(PlaceContent.classify(Map.of("name","숲길 펜션","tour_content_type","32")).category()).isEqualTo("stay");
        assertThat(PlaceContent.classify(Map.of("name","해변 카페 축제","tour_content_type","15")).label()).isEqualTo("축제·행사");
        assertThat(PlaceContent.classify(Map.of("name","순포 카페","tour_content_type","39")).category()).isEqualTo("cafe");
        assertThat(PlaceContent.classify(Map.of("name","해안둘레길","tour_content_type","12")).category()).isEqualTo("walk");
        assertThat(PlaceContent.classify(Map.of("name","해변을 걷다","category","walk")).kind()).isEqualTo("unknown");
        assertThat(PlaceContent.validType("250")).isEmpty();
    }
    @Test void physicalLocationQualityUsesActualAddressAndValidCoordinates() {
        var row = new java.util.HashMap<String,Object>(Map.of("address","강릉시","mapx","128.9","mapy","37.8"));
        assertThat(PlaceContent.classify(row).quality()).isZero();
        row.put("mapx","NaN"); assertThat(PlaceContent.classify(row).quality()).isEqualTo(1);
        row.put("address",""); assertThat(PlaceContent.classify(row).quality()).isEqualTo(2);
        row.put("tour_content_type","25"); assertThat(PlaceContent.classify(row).quality()).isEqualTo(3);
        assertThat(PlaceContent.classify(Map.of("source","submission:3")).kind()).isEqualTo("place");
    }
    @Test void missingAndGenericPhotosNeverMasqueradeAsVenuePhotos() {
        assertThat(PlaceContent.displayImage(Map.of("image","https://images.unsplash.com/generic.jpg"))).isEmpty();
        assertThat(PlaceContent.displayImage(Map.of())).isEmpty();
        assertThat(PlaceContent.displayImage(Map.of("image","https://tong.visitkorea.or.kr/a.jpg"))).isNotEmpty();
        assertThat(PlaceContent.officialImage("https://tong.visitkorea.or.kr.evil.example/a.jpg")).isFalse();
        assertThat(PlaceContent.displayImage(Map.of("image","https://example.test/user.jpg","source","submission:1"))).isNotEmpty();
    }
    @Test void searchPreservesEverythingButPrefersLocatedPlacesAndSeparatesOfficialCourses() {
        var db = new CatalogFixture();
        db.jdbc.update("INSERT INTO places(name,address,mapx,mapy,tour_content_type,category,approved) VALUES('바다 해변 코스','강릉','128.9','37.8','25','walk',TRUE),('바다 누락 해변','','','','','walk',TRUE),('가진해변','강원 고성','128.9','37.8','12','tour',TRUE)");
        db.queries.backfill();
        var all = db.queries.search("바다","","","recommended",1,6);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.rows().get(0).get("name")).isEqualTo("가진해변");
        var profile = DiscoveryRecommendations.Profile.guest();
        assertThat(db.queries.search("바다","","","recommended",1,6,profile,"course").rows()).extracting(r->r.get("name")).containsExactly("바다 해변 코스");
        assertThat(db.queries.search("바다","","","recommended",1,6,profile,"place").total()).isEqualTo(1);
        assertThat(db.queries.search("바다 해변 코스","","","recommended",1,6).rows().get(0).get("name")).isEqualTo("바다 해변 코스");
        assertThat(db.queries.nearby(128.9,37.8,1000,"","near",1,6).rows()).extracting(r->r.get("name")).containsExactly("가진해변");
        assertThat(db.queries.discoveryCandidates(profile,java.time.LocalDate.of(2026,9,15))).extracting(r->r.get("name")).containsExactly("가진해변");
        long count = db.queries.publicCount(); db.queries.backfill(); assertThat(db.queries.publicCount()).isEqualTo(count);
    }
}
