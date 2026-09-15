package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ArchivedCrowd {
    private final List<Observation> observations;

    public ArchivedCrowd(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("data/recovered-crowd.json").getInputStream()) {
            observations = read(mapper.readTree(input));
        }
    }

    record Observation(String name, double longitude, double latitude, double value, String label, String backupAt) {}

    static List<Observation> read(JsonNode root) {
        List<Observation> result = new ArrayList<>();
        if (!"legacy-db".equals(root.path("source").asText())) return List.of();
        for (var row : root.path("records")) {
            double value = row.path("concentration").isNumber() ? row.path("concentration").asDouble() : Double.NaN;
            double x = row.path("mapx").asDouble(Double.NaN);
            double y = row.path("mapy").asDouble(Double.NaN);
            String source = row.path("source").asText("");
            String name = row.path("name").asText("").strip();
            String label = row.path("level").asText("");
            if (!Double.isFinite(value) || value < 0 || value > 100 || name.isBlank()
                    || !Set.of("한적함", "보통", "붐빔").contains(label)
                    || !(x > 124 && x < 132 && y > 32 && y < 39)
                    || !(source.isEmpty() || source.equals("tour-api"))) continue;
            result.add(new Observation(name, x, y, value, label, root.path("backupAt").asText("")));
        }
        return List.copyOf(result);
    }

    public Optional<Observation> find(String name, String longitude, String latitude, String source) {
        // Never reuse old numeric IDs or guessed seed fields. Match a public place by name and location.
        if (!(source.equals("recovered-tour-api") || source.startsWith("tour-api:"))) return Optional.empty();
        try {
            double x = Double.parseDouble(longitude);
            double y = Double.parseDouble(latitude);
            return observations.stream().filter(item -> item.name().equals(name.strip())
                    && Math.abs(item.longitude() - x) < 0.001 && Math.abs(item.latitude() - y) < 0.001).findFirst();
        } catch (NumberFormatException exception) { return Optional.empty(); }
    }
}
