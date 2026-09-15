package backend;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DiscoveryRecommendationsTests {
    final LocalDate day = LocalDate.of(2026,9,15);
    final DiscoveryRecommendations.Profile guest = DiscoveryRecommendations.Profile.guest();
    Map<String,Object> place(long id, String region, String name, String category, double priority) {
        return new LinkedHashMap<>(Map.of("id",id,"region",region,"name",name,"category",category,"categoryLabel",category,
                "address",region+" "+name,"mapx","128.9","mapy","37.8","discoveryPriority",priority,"imageVerified",true));
    }
    Map<String,Object> mapped(Map<String,Object> row) {
        var p = place(((Number)row.get("id")).longValue(),Objects.toString(row.get("region"),""),Objects.toString(row.get("name"),""),Objects.toString(row.get("category"),""),0);
        p.put("address",row.get("address")); p.put("tags",row.get("tags")); p.put("image",row.get("image"));
        p.put("imageVerified", Objects.toString(row.get("image"),"").startsWith("https://tong.visitkorea.or.kr/"));
        var content = PlaceContent.classify(row);
        p.put("category",content.category()); p.put("categoryLabel",content.label());
        p.put("contentTypeId",content.type()); p.put("contentKind",content.kind());
        p.put("mapx",Objects.toString(row.get("mapx"),"")); p.put("mapy",Objects.toString(row.get("mapy"),""));
        return p;
    }
    void insert(CatalogFixture db, String name, String region, String category, String image, boolean approved) {
        db.jdbc.update("INSERT INTO places(name,address,region,category,category_label,tags,image,mapx,mapy,approved,source) VALUES(?,?,?,?,?,?,?,'128.9','37.8',?,'fixture')",name,region+" "+name,region,category,category,name,image,approved);
    }
    DiscoveryRecommendations service(CatalogFixture db) {
        return new DiscoveryRecommendations(db.jdbc,db.mapper,null,db.queries,Clock.fixed(day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),ZoneOffset.UTC));
    }
    @Test void currentConcentrationCountsButMissingStaleArchivedAndInvalidValuesDoNotMeanQuiet() {
        var p = place(1,"강원","숲길","walk",0); p.put("crowdValue",10); p.put("hiddenRating",5);
        assertThat(DiscoveryRecommendations.evidenceScore(p,guest,day)).isZero();
        p.put("crowdBasis","archived"); p.put("crowdDate",day.toString());
        assertThat(DiscoveryRecommendations.evidenceScore(p,guest,day)).isZero();
        p.put("crowdBasis","forecast"); assertThat(DiscoveryRecommendations.evidenceScore(p,guest,day)).isPositive();
        assertThat(DiscoveryRecommendations.evidenceScore(p,guest,day.plusDays(1))).isZero();
        for (double bad : new double[]{-1,999,Double.NaN,Double.POSITIVE_INFINITY}) { p.put("crowdValue",bad); assertThat(DiscoveryRecommendations.evidenceScore(p,guest,day)).isZero(); }
        p.put("crowdValue",80); assertThat(DiscoveryRecommendations.evidenceScore(p,guest,day)).isNegative();
        assertThat(p.get("hiddenRating")).isEqualTo(5);
    }
    @Test void homeBalancesRegionsDeduplicatesAndKeepsFourPlaces() {
        var a=place(1,"강원","한 숲길","walk",30); var duplicate=place(2,"강원","한 숲길","walk",30);
        var b=place(3,"강원","다른 숲길","walk",29); var c=place(4,"제주","해변","tour",25);
        var d=place(5,"경기","박물관","culture",23); var e=place(6,"전북","캠핑","camping",22);
        var picked=DiscoveryRecommendations.select(List.of(a,duplicate,b,c,d,e),guest,day,4,null);
        assertThat(picked).hasSize(4); assertThat(picked.stream().map(p -> p.get("region"))).containsExactlyInAnyOrder("강원","제주","경기","전북");
        assertThat(picked.stream().map(p -> p.get("id"))).doesNotContain(2L,3L);
    }
    @Test void savedPlacesDoNotConsumeDiscoveryWhenThereAreNewCandidates() {
        var profile=new DiscoveryRecommendations.Profile(Set.of(),Set.of(),"normal",Set.of(1L));
        var picked=DiscoveryRecommendations.select(List.of(place(1,"강원","saved","tour",10),place(2,"강원","new","tour",10)),profile,day,1,null);
        assertThat(picked.get(0).get("id")).isEqualTo(2L);
    }
    @Test void nearbyIsBoundedToTwoExcludesTheOriginAndPrefersCloserComparableCandidates() {
        var origin=place(1,"강원","origin","walk",0);
        var close=place(2,"강원","cafe","cafe",10); close.put("mapx","128.91");
        var far=place(3,"강원","far cafe","cafe",10); far.put("mapx","129.1");
        var beyond=place(4,"서울","outside","nature",999); beyond.put("mapx","127.1");
        var second=place(5,"강원","museum","culture",10); second.put("mapx","128.92");
        var picks=DiscoveryRecommendations.select(List.of(origin,beyond,far,second,close),guest,day,2,origin);
        assertThat(picks.stream().map(p -> p.get("id"))).containsExactly(2L,5L);
        var invalid=place(6,"강원","missing","walk",0); invalid.remove("mapx");
        assertThat(DiscoveryRecommendations.select(List.of(invalid),guest,day,2,origin)).isEmpty();
    }
    @Test void exactNameBeatsPrettyPhotosAndPersonalizationNeverEscapesSearchFilters() {
        var db=new CatalogFixture();
        insert(db,"바다","강원","tour","",true); insert(db,"바다 숲길","강원","walk","https://tong.visitkorea.or.kr/photo.jpg",true);
        insert(db,"바다 카페","제주","cafe","https://tong.visitkorea.or.kr/photo.jpg",true); insert(db,"바다 비공개","강원","walk","",false);
        db.queries.indexSource("fixture");
        var profile=new DiscoveryRecommendations.Profile(Set.of("cafe"),Set.of("제주"),"quiet",Set.of());
        var results=db.queries.search("바다","강원","","recommended",1,6,profile);
        assertThat(results.total()).isEqualTo(2); assertThat(results.rows().get(0).get("name")).isEqualTo("바다");
        assertThat(results.rows()).allMatch(p -> "강원".equals(p.get("region")));
        assertThat(db.queries.search("","","","recommended",1,6,profile).rows().get(0).get("name")).isEqualTo("바다 카페");
    }
    @Test void nationwidePoolHasRegionalSlotsIsStablePerDayAndExcludesUnpublishedRows() {
        var db=new CatalogFixture();
        for (int i=0;i<70;i++) insert(db,"숲길"+i,"강원","walk","https://tong.visitkorea.or.kr/photo.jpg",true);
        for (String region : List.of("제주","경기","전북")) insert(db,region+"해변",region,"tour","https://tong.visitkorea.or.kr/photo.jpg",true);
        insert(db,"private","강원","walk","",false); insert(db,"deleted","강원","walk","",true);
        db.jdbc.update("UPDATE places SET tour_visible=FALSE WHERE name='deleted'"); db.queries.indexSource("fixture");
        var pool=db.queries.discoveryCandidates(guest,day);
        assertThat(pool).hasSize(6); assertThat(pool).allMatch(p -> !List.of("private","deleted").contains(p.get("name")));
        assertThat(pool).isEqualTo(db.queries.discoveryCandidates(guest,day));
        assertThat(pool.stream().map(p->p.get("id")).toList()).isNotEqualTo(db.queries.discoveryCandidates(guest,day.plusDays(1)).stream().map(p->p.get("id")).toList());
        var feed=service(db).home(guest,this::mapped);
        assertThat((List<?>)feed.get("items")).hasSize(4); assertThat(feed).containsEntry("personalized",false).containsEntry("method",DiscoveryRecommendations.METHOD);
    }
    @Test void localAndSqlPreferenceScoresAgreeAndEverySavedInterestHasARule() throws Exception {
        var db=new CatalogFixture(); insert(db,"제주 바다 카페","제주","cafe","https://tong.visitkorea.or.kr/photo.jpg",true); db.queries.indexSource("fixture");
        var profile=new DiscoveryRecommendations.Profile(Set.of("sea","cafe"),Set.of("제주"),"quiet",Set.of());
        var row=db.queries.discoveryCandidates(profile,day).get(0);
        assertThat(((Number)row.get("discovery_priority")).doubleValue()).isEqualTo(DiscoveryRecommendations.localPriority(mapped(row),profile,day));
        try (var input=getClass().getResourceAsStream("/data/travel-preferences.json")) {
            for (var item: db.mapper.readTree(input)) assertThat(DiscoveryRecommendations.interestsCovered()).contains(item.path("id").asText());
        }
    }
    @Test void explanationIsGroundedVariesByThemeAndDoesNotPromiseUnmeasuredPopularityOrAi() {
        var db=new CatalogFixture(); var service=service(db);
        var beach=place(1,"강원","어느 해변","tour",0); var cafe=place(2,"제주","작은 카페","cafe",0);
        service.explain(beach,guest,day); service.explain(cafe,new DiscoveryRecommendations.Profile(Set.of("cafe"),Set.of("제주"),"normal",Set.of()),day);
        assertThat(beach.get("recommendationReason").toString()).contains("해안","자료는 아직 확인되지").doesNotContain("AI","덜 알려진","한적함");
        assertThat(cafe.get("recommendationReason").toString()).contains("카페","관심 지역","여행 취향");
        assertThat(beach).doesNotContainKey("discoveryPriority");
    }
    @Test @org.junit.jupiter.api.Timeout(60)
    void fiftyThousandPlacesStayInTheDatabaseWhileTheRecommendationPoolIsBounded() {
        var db=new CatalogFixture();
        db.jdbc.execute("INSERT INTO places(name,address,region,category,category_label,tags,image,mapx,mapy,approved,source,search_text,category_text,region_text,name_fold,legal_area_code) "
                + "SELECT CONCAT('전국숲길',x),'강원 강릉시','강원',CASE WHEN MOD(x,3)=0 THEN 'walk' WHEN MOD(x,3)=1 THEN 'cafe' ELSE 'camping' END,'관광지','숲길','https://tong.visitkorea.or.kr/example.jpg','128.9','37.8',TRUE,'scale','전국숲길','숲길','강원',CONCAT('전국숲길',x),CAST(MOD(x,17) AS VARCHAR) FROM SYSTEM_RANGE(1,50000) AS numbers(x)");
        long migrationStarted=System.nanoTime(); db.queries.backfill();
        System.out.println("Discovery 50000-row one-time indexing ms="+(System.nanoTime()-migrationStarted)/1_000_000);
        long started=System.nanoTime(); var candidates=db.queries.discoveryCandidates(guest,day);
        System.out.println("Discovery 50000-row fixture query ms="+(System.nanoTime()-started)/1_000_000+", candidates="+candidates.size());
        assertThat(candidates).hasSize(153);
        assertThat(db.queries.search("전국","","","recommended",1,6).rows()).hasSize(6);
        db.jdbc.execute("SHUTDOWN");
    }
    @Test void cachedCandidatesRecheckPublicationAndKeepDifferentPreferencesSeparate() {
        var db=new CatalogFixture();
        for (int i=0;i<8;i++) insert(db,"바다"+i,i%2==0 ? "강원" : "제주",i%2==0 ? "tour" : "cafe","https://tong.visitkorea.or.kr/photo.jpg",true);
        db.queries.indexSource("fixture"); var service=service(db);
        var first=(List<Map<String,Object>>)service.home(guest,this::mapped).get("items");
        long hidden=((Number)first.get(0).get("id")).longValue(); db.jdbc.update("UPDATE places SET tour_visible=FALSE WHERE id=?",hidden);
        var next=(List<Map<String,Object>>)service.home(guest,this::mapped).get("items");
        assertThat(next.stream().map(p->p.get("id"))).doesNotContain(hidden);
        var preference=new DiscoveryRecommendations.Profile(Set.of("cafe"),Set.of("제주"),"normal",Set.of());
        var personal=service.home(preference,this::mapped);
        assertThat(personal).containsEntry("personalized",true);
        assertThat(((List<Map<String,Object>>)personal.get("items")).get(0).get("category")).isEqualTo("cafe");
        assertThat(service.home(guest,this::mapped)).containsEntry("personalized",false);
    }
    @Test void bannersPreferOtherPlacesWithOfficialPhotosAndDifferentThemes() {
        var db=new CatalogFixture();
        for (String region : List.of("강원","제주","경기","전북")) {
            insert(db,region+" 숲길",region,"walk","https://tong.visitkorea.or.kr/walk.jpg",true);
            insert(db,region+" 카페",region,"cafe","https://tong.visitkorea.or.kr/cafe.jpg",true);
            insert(db,region+" 캠핑",region,"camping","https://tong.visitkorea.or.kr/camping.jpg",true);
        }
        db.queries.indexSource("fixture");
        var feed=service(db).home(guest,this::mapped);
        var selected=(List<Map<String,Object>>)feed.get("items");
        var banners=(List<Map<String,Object>>)feed.get("banners");
        assertThat(banners).hasSize(3);
        assertThat(banners.stream().map(p->p.get("bannerTheme"))).doesNotHaveDuplicates();
        assertThat(banners.stream().map(p->p.get("id"))).doesNotContainAnyElementsOf(selected.stream().map(p->p.get("id")).toList());
        assertThat(banners).allMatch(p->Boolean.TRUE.equals(p.get("imageVerified")) && !p.containsKey("discoveryPriority"));
    }
    @Test void broadSeaSearchRanksPhysicalBeachesBeforeBusinessesAndEditorialCoursesWithoutDroppingResults() {
        var db=new CatalogFixture();
        insert(db,"바다와 함께 떠나는 드라이브 코스","강원","walk","",true);
        insert(db,"바다정원펜션","강원","tour","https://tong.visitkorea.or.kr/pension.jpg",true);
        insert(db,"바다 카페","강원","cafe","https://tong.visitkorea.or.kr/cafe.jpg",true);
        insert(db,"순포해변","강원","tour","",true);
        insert(db,"협재해수욕장","제주","tour","https://tong.visitkorea.or.kr/beach.jpg",true);
        insert(db,"해변식당","강원","food","",true);
        insert(db,"비공개 해변","강원","tour","",false);
        db.queries.indexSource("fixture");
        var profile=new DiscoveryRecommendations.Profile(Set.of("cafe","walk"),Set.of("강원"),"quiet",Set.of());
        var results=db.queries.search("바다","","","recommended",1,6,profile);
        assertThat(results.total()).isEqualTo(6);
        assertThat(results.rows().subList(0,2).stream().map(p->p.get("name"))).containsExactlyInAnyOrder("순포해변","협재해수욕장");
        assertThat(db.queries.search("강원 바다","","","recommended",1,6,profile).rows().get(0).get("name")).isEqualTo("순포해변");
        assertThat(db.queries.search("바다","강원","","recommended",1,6,profile).total()).isEqualTo(5);
        assertThat(db.queries.search("바다 카페","","","recommended",1,6,profile).rows().get(0).get("name")).isEqualTo("바다 카페");
        assertThat(db.queries.search("바다정원펜션","","","recommended",1,6,profile).rows().get(0).get("name")).isEqualTo("바다정원펜션");
        assertThat(db.queries.search("바다","","","name",1,6,profile).rows().stream().map(p->p.get("name_fold").toString())).isSorted();
    }
    @Test void materializedScoresMatchLocalRulesAcrossEveryMonthAndInterestAndRefreshOnEdit() {
        var db=new CatalogFixture();
        insert(db,"벚꽃 숲길 계곡 단풍 박물관 카페","제주","cafe","https://tong.visitkorea.or.kr/photo.jpg",true);
        db.queries.backfill();
        for (String interest : DiscoveryRecommendations.interestsCovered()) for (int month=1;month<=12;month++) {
            var profile=new DiscoveryRecommendations.Profile(Set.of(interest),Set.of("제주"),"normal",Set.of());
            var date=LocalDate.of(2026,month,1);
            var row=db.queries.discoveryCandidates(profile,date).get(0);
            assertThat(((Number)row.get("discovery_priority")).doubleValue()).as(interest+" month "+month).isEqualTo(DiscoveryRecommendations.localPriority(mapped(row),profile,date));
        }
        long id=db.jdbc.queryForObject("SELECT id FROM places",Long.class);
        db.jdbc.update("UPDATE places SET name='해변 펜션',category='tour',image='',tags='' WHERE id=?",id);
        db.queries.index(id);
        var row=db.queries.discoveryCandidates(guest,day).get(0);
        assertThat(((Number)row.get("discovery_priority")).doubleValue()).isEqualTo(DiscoveryRecommendations.localPriority(mapped(row),guest,day));
        assertThat(((Number)row.get("discovery_topics")).intValue() & 1).isZero();
        db.jdbc.update("UPDATE places SET discovery_base=-999,discovery_version=0 WHERE id=?",id);
        db.queries.backfill();
        assertThat(db.jdbc.queryForObject("SELECT discovery_base FROM places",Integer.class)).isGreaterThanOrEqualTo(0);
        var before=db.jdbc.queryForMap("SELECT * FROM places"); db.queries.backfill();
        assertThat(db.jdbc.queryForMap("SELECT * FROM places")).isEqualTo(before);
    }
    @Test @org.junit.jupiter.api.Timeout(20)
    void concurrentColdRequestsShareOnePoolBuild() throws Exception {
        var db=new CatalogFixture(); insert(db,"해변","강원","tour","https://tong.visitkorea.or.kr/photo.jpg",true); db.queries.indexSource("fixture");
        var query=org.mockito.Mockito.spy(db.queries);
        var service=new DiscoveryRecommendations(db.jdbc,db.mapper,null,query,Clock.fixed(day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),ZoneOffset.UTC));
        var start=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var work=new ArrayList<java.util.concurrent.Future<Map<String,Object>>>();
            for (int i=0;i<8;i++) work.add(executor.submit(() -> { start.await(); return service.home(guest,this::mapped); }));
            start.countDown();
            for (var result : work) assertThat((List<?>)result.get(10,java.util.concurrent.TimeUnit.SECONDS).get("items")).hasSize(1);
            org.mockito.Mockito.verify(query,org.mockito.Mockito.times(1)).discoveryCandidates(guest,day);
        } finally { executor.shutdownNow(); }
    }
    @Test void incompleteAndNonExplorationItemsCannotWinEvenWithLargePreferenceScores() {
        var good=place(1,"제주","정원","nature",0);
        var missing=place(2,"제주","주소 없는 숲길","walk",999); missing.put("address"," ");
        var course=place(3,"제주","코스","walk",999); course.put("contentTypeId","25");
        var stay=place(4,"제주","숲길 펜션","walk",999); stay.put("contentTypeId","32");
        var event=place(5,"제주","지난 축제","culture",999); event.put("contentTypeId","15");
        assertThat(DiscoveryRecommendations.select(List.of(missing,course,stay,event,good),guest,day,4,null)).containsExactly(good);
    }
    @Test void nearbyEligibilityIsAppliedBeforeTheSixtyCandidateLimitAndSearchStillIncludesAccommodation() {
        var db=new CatalogFixture();
        for (int i=0;i<65;i++) insert(db,"펜션"+i,"강원","stay","",true);
        db.jdbc.update("UPDATE places SET tour_content_type='32'");
        insert(db,"등대","강원","tour","",true); insert(db,"카페","강원","cafe","",true);
        db.jdbc.update("UPDATE places SET mapx='128.92' WHERE name IN ('등대','카페')");
        db.queries.indexSource("fixture");
        assertThat(db.queries.search("","","","recommended",1,6).total()).isEqualTo(67);
        var selected=service(db).nearby(place(999,"강원","출발지","tour",0),guest,this::mapped);
        assertThat(selected).hasSize(2);
        assertThat(selected.stream().map(p -> p.get("name"))).containsExactlyInAnyOrder("등대","카페");
        assertThat(selected).allMatch(p -> p.get("recommendationReason").toString().contains("직선거리 약"));
    }
    @Test void officialClassificationControlsMaterializedAndLivePreferenceScoring() {
        var db=new CatalogFixture(); insert(db,"작은 박물관","강원","walk","",true);
        db.jdbc.update("UPDATE places SET tour_content_type='14'"); db.queries.indexSource("fixture");
        var profile=new DiscoveryRecommendations.Profile(Set.of("culture"),Set.of(),"normal",Set.of());
        var row=db.queries.discoveryCandidates(profile,day).get(0);
        assertThat(((Number)row.get("discovery_priority")).doubleValue()).isEqualTo(DiscoveryRecommendations.localPriority(mapped(row),profile,day));
    }
}
