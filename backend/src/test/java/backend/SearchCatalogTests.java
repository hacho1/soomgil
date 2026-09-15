package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SearchCatalogTests {
    private final SearchCatalog catalog = new SearchCatalog(new ObjectMapper());

    @Test void dictionaryHasUniqueWordsAndNineCompleteGroups() {
        assertThat(catalog.keywords().size()).isGreaterThanOrEqualTo(250);
        assertThat(catalog.keywords().stream().map(SearchCatalog::fold).distinct().count()).isEqualTo(catalog.keywords().size());
        assertThat(catalog.categories()).hasSize(9).allSatisfy(category -> assertThat(category.items()).hasSize(12));
        assertThat(catalog.categories().subList(0, 6)).extracting(SearchCatalog.Category::id).containsExactly("tour", "walk", "camping", "food", "cafe", "culture");
    }

    @Test void autocompleteSupportsKoreanPrefixesInitialsAndPublishedNames() {
        assertThat(catalog.suggestions("가", List.of())).isNotEmpty().hasSizeLessThanOrEqualTo(8);
        assertThat(catalog.suggestions("ㄱㄹ", List.of())).contains("강릉 바다");
        assertThat(catalog.suggestions("수ㄱ", List.of())).contains("수국");
        assertThat(catalog.suggestions("없는검색어xyz", List.of())).isEmpty();
        assertThat(catalog.suggestions("순포", List.of("순포해변"))).containsExactly("순포해변");
        assertThat(catalog.suggestions("", List.of("순포해변"))).isEmpty();
    }

    @Test void aliasesAreSymmetricAndDoNotMixRelatedThemesIntoResults() {
        assertThat(catalog.matches("순포해변", "바다")).isTrue();
        assertThat(catalog.matches("바다", "해수욕장")).isTrue();
        assertThat(catalog.matches("노을 공원", "바다")).isFalse();
        assertThat(catalog.matches("숲 공원", "계곡")).isFalse();
        assertThat(catalog.related("해변")).contains("바다 카페", "노을");
    }

    @Test void spacingAndCompoundRegionQueriesAreNormalized() {
        assertThat(catalog.matches("강원특별자치도 강릉시 순포해변", "강릉바다")).isTrue();
        assertThat(catalog.matches("강원특별자치도 강릉시 순포해변", "강원도 바다")).isTrue();
        assertThat(catalog.matches("강원특별자치도 강릉시 순포해변", "부산 바다")).isFalse();
        assertThat(catalog.suggestions("감성카", List.of())).contains("감성 카페");
        assertThat(catalog.related("강릉바다")).contains("강릉 해안 산책");
        assertThat(catalog.upstreamQuery("강릉바다")).isEqualTo("강릉 해변");
    }

    @Test void everyProvinceHasRegionalSuggestions() {
        TourCatalog.REGIONS.forEach(region -> assertThat(catalog.suggestions(region, List.of())).anyMatch(word -> word.startsWith(region)));
    }

    @Test void newCategoriesMatchPlaceEvidenceNotAddressOrUnknownFacilities() {
        var forest = Map.<String, Object>of("name", "편백숲", "category", "tour", "categoryLabel", "관광지", "tags", "", "address", "전시관 앞");
        assertThat(catalog.matchesCategory(forest, "nature")).isTrue();
        assertThat(catalog.matchesCategory(forest, "culture")).isFalse();
        assertThat(catalog.matchesCategory(forest, "pet")).isFalse();
        assertThat(catalog.matchesCategory(Map.of("name", "레일바이크", "category", "tour", "tags", "", "categoryLabel", "관광지"), "experience")).isTrue();
    }
    @Test void topicIntentUsesWholeWordsAndLastTopicWithoutMisreadingPlaceNames() {
        int sea=catalog.topicIndex("바다"), cafe=catalog.topicIndex("카페");
        assertThat(sea).isGreaterThanOrEqualTo(0);
        for (String query : List.of("해변","해수욕장","강릉바다","강원도 바다")) assertThat(catalog.topicIndex(query)).isEqualTo(sea);
        assertThat(catalog.topicIndex("바다 카페")).isEqualTo(cafe);
        for (String name : List.of("바다정원","정동진해변","산정호수","해변펜션","%_!")) assertThat(catalog.topicIndex(name)).isEqualTo(-1);
        assertThat(catalog.topicMask(Map.of("name","어느 해수욕장","category","tour")) & (1<<sea)).isNotZero();
        assertThat(catalog.topicMask(Map.of("name","해변펜션","category","tour")) & (1<<sea)).isZero();
        assertThat(catalog.topicMask(Map.of("name","해변식당","category","food")) & (1<<sea)).isZero();
        assertThat(catalog.topicMask(Map.of("name","해변 카페","category","cafe")) & (1<<cafe)).isNotZero();
        assertThat(catalog.topicMask(Map.of("name","송도해변축제","category","culture")) & (1<<sea)).isZero();
        assertThat(catalog.topicMask(Map.of("name","동해안 해변을 거닐다","category","walk")) & (1<<sea)).isZero();
        assertThat(catalog.topicMask(Map.of("name","남항진해변(남항진해수욕장)","category","tour")) & (1<<sea)).isNotZero();
    }
}
