package backend;

import java.net.URI;
import java.util.Map;
import java.util.Set;

final class PlaceContent {
    static final int CATALOG_VERSION = 1;
    private static final Set<String> TYPES = Set.of("12", "14", "15", "25", "28", "32", "38", "39");
    record Classification(String type, String kind, String category, String label, int quality) {}

    static String validType(String value) { return TYPES.contains(value) ? value : ""; }
    static boolean submission(Map<String,Object> row) { String source = text(row,"source"); return source.equals("submission") || source.startsWith("submission:"); }
    static String text(Map<String,Object> row, String key) { return row.get(key) == null ? "" : String.valueOf(row.get(key)).trim(); }
    static boolean coordinates(Map<String,Object> row) {
        try {
            double x = Double.parseDouble(text(row,"mapx")), y = Double.parseDouble(text(row,"mapy"));
            return x > 124 && x < 132 && y > 32 && y < 39;
        } catch (NumberFormatException ignored) { return false; }
    }
    static Classification classify(Map<String,Object> row) {
        String type = validType(text(row,"tour_content_type")), name = text(row,"name");
        String category = switch (type) {
            case "25" -> "course"; case "32" -> "stay"; case "38" -> "shopping";
            case "14", "15" -> "culture"; case "28" -> "experience"; case "39" -> "food";
            case "12" -> "tour"; default -> text(row,"category");
        };
        if (type.equals("39") && name.matches(".*(카페|커피).*")) category = "cafe";
        if (type.equals("12") || type.equals("28")) {
            if (name.matches(".*(캠핑|야영장|글램핑).*")) category = "camping";
            else if (name.matches(".*(둘레길|산책|숲길|트레킹).*")) category = "walk";
        }
        String label = type.isEmpty() ? text(row,"category_label") : switch (category) {
            case "course" -> "여행 코스"; case "stay" -> "숙박"; case "shopping" -> "쇼핑";
            case "food" -> "음식점"; case "cafe" -> "카페"; case "camping" -> "캠핑";
            case "walk" -> "산책길"; case "culture" -> type.equals("15") ? "축제·행사" : "문화시설";
            case "experience" -> "레포츠"; default -> "관광지";
        };
        String kind = type.equals("25") ? "course" : !type.isEmpty() || submission(row) ? "place" : "unknown";
        boolean address = !text(row,"address").isBlank(), located = coordinates(row);
        int quality = kind.equals("course") ? 3 : address && located ? 0 : address || located ? 1 : 2;
        return new Classification(type,kind,category,label,quality);
    }
    static boolean discoveryEligible(Map<String,Object> row) {
        var content = classify(row);
        // Dated events, accommodation and shopping remain searchable, not automatic exploration stops.
        return !text(row,"name").isBlank() && content.quality() == 0
                && !Set.of("15", "25", "32", "38").contains(content.type())
                && !Set.of("course", "stay", "shopping").contains(content.category());
    }
    static boolean officialImage(String image) {
        try { var uri = URI.create(image); return "https".equals(uri.getScheme()) && "tong.visitkorea.or.kr".equals(uri.getHost()) && uri.getUserInfo() == null; }
        catch (IllegalArgumentException ignored) { return false; }
    }
    static String displayImage(Map<String,Object> row) {
        String image = text(row,"image");
        if (officialImage(image)) return image;
        if (submission(row) && (image.startsWith("https://") || image.startsWith("data:image/"))) return image;
        return "";
    }
}
