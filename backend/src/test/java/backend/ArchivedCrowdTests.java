package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchivedCrowdTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void restoresOriginalValuesAndLabelsWithoutDependingOnOldIds() throws Exception {
        var service = new ArchivedCrowd(mapper);
        try (var input = getClass().getResourceAsStream("/data/recovered-crowd.json")) {
            var root = mapper.readTree(input);
            assertThat(ArchivedCrowd.read(root)).hasSize(5);
            for (var row : root.path("records")) {
                for (String source : new String[]{"recovered-tour-api", "tour-api:123456"}) {
                    var item = service.find(row.path("name").asText(), row.path("mapx").asText(), row.path("mapy").asText(), source).orElseThrow();
                    assertThat(item.value()).isEqualTo(row.path("concentration").asDouble());
                    assertThat(item.label()).isEqualTo(row.path("level").asText());
                    assertThat(item.backupAt()).isEqualTo("2026-09-09T19:50:04");
                }
            }
        }
    }

    @Test
    void rejectsUnrelatedPlacesAndUntrustedSources() throws Exception {
        var service = new ArchivedCrowd(mapper);
        assertThat(service.find("순포해변", "129.5", "37.8", "tour-api:1")).isEmpty();
        assertThat(service.find("다른 해변", "128.8902985428", "37.8193190673", "tour-api:1")).isEmpty();
        assertThat(service.find("순포해변", "", "", "recovered-tour-api")).isEmpty();
        assertThat(service.find("순포해변", "NaN", "Infinity", "recovered-tour-api")).isEmpty();
        for (String source : new String[]{"test", "submission", "fallback", "rebuild-seed", ""}) {
            assertThat(service.find("순포해변", "128.8902985428", "37.8193190673", source)).isEmpty();
        }
    }

    @Test
    void rejectsSentinelsMissingValuesAndUntrustedArchiveRows() throws Exception {
        var root = mapper.createObjectNode().put("source", "legacy-db");
        var rows = root.putArray("records");
        var row = rows.addObject().put("name", "test").put("mapx", "128.8").put("mapy", "37.8").put("level", "한적함");
        for (double value : new double[]{-1, 101, 999, Double.NaN, Double.POSITIVE_INFINITY}) {
            row.put("concentration", value);
            assertThat(ArchivedCrowd.read(root)).isEmpty();
        }
        row.putNull("concentration");
        assertThat(ArchivedCrowd.read(root)).isEmpty();
        row.put("concentration", 17.93);
        assertThat(ArchivedCrowd.read(root)).hasSize(1);
        for (String source : new String[]{"test", "fallback", "submission", "unknown"}) {
            row.put("source", source);
            assertThat(ArchivedCrowd.read(root)).isEmpty();
        }
        row.put("source", "tour-api").put("level", "unknown");
        assertThat(ArchivedCrowd.read(root)).isEmpty();
    }
}
