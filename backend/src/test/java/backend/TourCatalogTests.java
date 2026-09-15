package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TourCatalogTests {
    @Test
    void regionAliasesCoverAllProvinces() {
        assertThat(TourCatalog.areaCode("전라북도")).isEqualTo("37");
        assertThat(TourCatalog.areaCode("전북특별자치도")).isEqualTo("37");
        assertThat(TourCatalog.areaCode("충청남도")).isEqualTo("34");
        assertThat(TourCatalog.areaCode("강원특별자치도")).isEqualTo("32");
        assertThat(TourCatalog.areaCode("")).isEmpty();
        assertThat(TourCatalog.legalAreaCode("광주")).isEqualTo("12");
        assertThat(TourCatalog.legalAreaCode("전라남도")).isEqualTo("12");
        assertThat(TourCatalog.legalAreaCode("세종")).isEqualTo("36110");
        assertThat(TourCatalog.regionName("전남광주통합특별시")).isEqualTo("전남·광주");
        TourCatalog.REGIONS.forEach(region -> assertThat(TourCatalog.areaCode(region)).isNotBlank());
    }

    @Test
    void nationalSearchOmitsAreaCodeAndIdenticalSearchesUseCache() throws Exception {
        var calls = new AtomicInteger();
        var request = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/searchKeyword2", exchange -> {
            calls.incrementAndGet(); request.set(exchange.getRequestURI().getQuery());
            byte[] json = "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\",\"totalCount\":0}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length); exchange.getResponseBody().write(json); exchange.close();
        });
        server.start();
        try {
            var service = new CatalogFixture().catalog("http://127.0.0.1:" + server.getAddress().getPort(), 300);
            service.refresh("바다", "", ""); service.refresh("바다", "", "");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(request.get()).doesNotContain("areaCode").contains("keyword=해변");
            service.refresh("바다", "충청남도", "");
            assertThat(request.get()).contains("lDongRegnCd=44").doesNotContain("areaCode=");
            assertThat(service.status().get("state")).isEqualTo("connected");
            assertThat(service.status().toString()).doesNotContain("test-key");
        } finally { server.stop(0); }
    }

    @Test
    void pagedDiscoveryReportsUpstreamTotalAndUsesLocationRadius() throws Exception {
        var calls = new AtomicInteger();
        var request = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            calls.incrementAndGet(); request.set(exchange.getRequestURI().getQuery());
            int page = exchange.getRequestURI().getQuery().contains("pageNo=3") ? 3 : 1;
            var items = new ObjectMapper().createArrayNode();
            for (int i = 0; i < (page == 3 ? 1 : 60); i++) items.addObject();
            byte[] json = ("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":121,\"items\":{\"item\":" + items + "}}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length); exchange.getResponseBody().write(json); exchange.close();
        };
        server.createContext("/areaBasedList2", handler); server.createContext("/locationBasedList2", handler); server.start();
        try {
            var service = new CatalogFixture().catalog("http://127.0.0.1:" + server.getAddress().getPort(), 300);
            var first = service.discover("", "", 1, null, null, 10000);
            assertThat(first.total()).isEqualTo(121); assertThat(first.hasMore()).isTrue();
            assertThat(service.status().get("nationalTotal")).isEqualTo(121L);
            service.discover("", "", 1, null, null, 10000);
            assertThat(calls.get()).isEqualTo(1);
            var last = service.discover("", "", 3, null, null, 10000);
            assertThat(last.hasMore()).isFalse(); assertThat(request.get()).contains("pageNo=3", "numOfRows=60");
            service.discover("강원", "tour", 1, 128.89, 37.81, 20000);
            assertThat(request.get()).contains("mapX=128.89", "radius=20000", "lDongRegnCd=51", "arrange=E");
        } finally { server.stop(0); }
    }

    @Test
    void failedUpstreamDoesNotBreakLocalSearchAndUsesCooldown() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/searchKeyword2", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            var service = new CatalogFixture().catalog("http://127.0.0.1:" + server.getAddress().getPort(), 300);
            service.refresh("바다", "", ""); service.refresh("숲", "", "");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(service.status().get("state")).isEqualTo("unavailable");
        } finally { server.stop(0); }
    }
}
