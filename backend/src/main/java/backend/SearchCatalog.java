package backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SearchCatalog {
    record Category(String id, String label, List<String> types, String keyword, String contentType, List<String> items) {}
    record Topic(String id, List<String> queries, List<String> titleTerms, List<String> categories, List<String> excludedCategories, List<String> excludedTitleTerms, List<String> titleSuffixes) {
        Topic { titleSuffixes = titleSuffixes == null ? List.of() : titleSuffixes; }
    }
    record Dictionary(int version, List<String> keywords, Map<String, List<String>> regionalKeywords,
                      List<List<String>> synonyms, Map<String, List<String>> related, Map<String, String> upstreamKeywords) {}
    private final List<Category> categories;
    private final Dictionary dictionary;
    private final List<Topic> topics;
    private final Map<String, String> vocabulary = new LinkedHashMap<>();
    private final Map<String, List<String>> aliases = new LinkedHashMap<>();

    public SearchCatalog(ObjectMapper mapper) {
        try (var words = getClass().getResourceAsStream("/data/search-keywords.json");
             var groups = getClass().getResourceAsStream("/data/categories.json");
             var intents = getClass().getResourceAsStream("/data/search-topics.json")) {
            if (words == null || groups == null || intents == null) throw new IllegalStateException("Search data is missing");
            dictionary = mapper.readValue(words, Dictionary.class);
            categories = mapper.readValue(groups, new TypeReference<>() {});
            topics = mapper.readValue(intents, new TypeReference<>() {});
            if (topics.size()>30 || topics.stream().map(Topic::id).distinct().count()!=topics.size()) throw new IllegalStateException("Invalid search topics");
        } catch (IOException error) { throw new IllegalStateException("Search data could not be read", error); }
        dictionary.keywords.forEach(this::addWord);
        categories.forEach(category -> { addWord(category.label); category.items.forEach(this::addWord); });
        dictionary.regionalKeywords.forEach((region, words) -> { addWord(region); words.forEach(this::addWord); });
        dictionary.synonyms.forEach(group -> group.forEach(word -> {
            addWord(word);
            aliases.put(fold(word), group.stream().map(SearchCatalog::fold).distinct().toList());
        }));
        dictionary.related.values().forEach(words -> words.forEach(this::addWord));
        if (categories.stream().map(Category::id).distinct().count() != categories.size()) {
            throw new IllegalStateException("Duplicate category IDs");
        }
    }

    private void addWord(String word) { vocabulary.putIfAbsent(fold(word), word); }
    static String fold(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim(); }
    public List<Category> categories() { return categories; }
    public List<String> keywords() { return List.copyOf(vocabulary.values()); }
    public List<List<String>> matchTerms(String query) {
        return Arrays.stream(phrase(query).split("\\s+")).map(term -> aliases.getOrDefault(fold(term), List.of(fold(term)))).toList();
    }
    public String phrase(String query) { return vocabulary.getOrDefault(fold(query), query.trim()); }

    int topicIndex(String query) {
        String[] parts = phrase(query).split("\\s+");
        String last = fold(parts[parts.length-1]);
        for (int i=0;i<topics.size();i++) if (topics.get(i).queries().stream().map(SearchCatalog::fold).anyMatch(last::equals)) return i;
        return -1;
    }
    int topicMask(Map<String,Object> row) {
        String name = fold(java.util.Objects.toString(row.get("name"),""));
        String withoutQualifier = name.replaceFirst("\\(.*$","");
        String category = java.util.Objects.toString(row.get("category"),"");
        int mask = 0;
        for (int i=0;i<topics.size();i++) {
            Topic topic = topics.get(i);
            if (topic.excludedCategories().contains(category) || topic.excludedTitleTerms().stream().map(SearchCatalog::fold).anyMatch(name::contains)) continue;
            if (topic.categories().contains(category) || topic.titleTerms().stream().map(SearchCatalog::fold).anyMatch(name::contains)
                    || topic.titleSuffixes().stream().map(SearchCatalog::fold).anyMatch(withoutQualifier::endsWith)) mask |= 1 << i;
        }
        return mask;
    }

    public List<String> suggestions(String query, List<String> placeNames) {
        if (query.isBlank()) return List.of();
        var candidates = new LinkedHashMap<>(vocabulary);
        placeNames.forEach(name -> candidates.putIfAbsent(fold(name), name));
        return candidates.values().stream().filter(value -> rank(value, query) < 10)
                .sorted(Comparator.comparingInt((String value) -> rank(value, query)))
                .limit(8).toList();
    }

    private int rank(String value, String query) {
        String word = fold(value), key = fold(query);
        if (word.equals(key)) return 0;
        if (word.startsWith(key)) return 1;
        if (word.contains(key)) return 2;
        if (query.replaceAll("\\s+", "").matches("[ㄱ-ㅎ]+") && initials(value).startsWith(query.replaceAll("\\s+", ""))) return 3;
        if (Normalizer.normalize(word, Normalizer.Form.NFD).startsWith(Normalizer.normalize(key, Normalizer.Form.NFD))) return 4;
        if (matches(value, query)) return 5;
        return 10;
    }

    private static String initials(String value) {
        String consonants = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        var result = new StringBuilder();
        for (char ch : value.replaceAll("\\s+", "").toCharArray()) {
            result.append(ch >= '가' && ch <= '힣' ? consonants.charAt((ch - '가') / 588) : ch);
        }
        return result.toString();
    }

    // Related themes are deliberately excluded from result matching.
    public boolean matches(String text, String query) {
        if (query.isBlank()) return true;
        String content = fold(text);
        if (content.contains(fold(query))) return true;
        return Arrays.stream(phrase(query).split("\\s+")).allMatch(term ->
                aliases.getOrDefault(fold(term), List.of(fold(term))).stream().anyMatch(content::contains));
    }

    public List<String> related(String query) {
        if (query.isBlank()) return List.of();
        String phrase = phrase(query);
        String[] terms = phrase.split("\\s+");
        String topic = terms[terms.length - 1];
        String prefix = terms.length > 1 ? String.join(" ", Arrays.copyOf(terms, terms.length - 1)) + " " : "";
        var result = new LinkedHashSet<String>();
        dictionary.related.forEach((key, words) -> {
            if (aliases.getOrDefault(fold(topic), List.of(fold(topic))).contains(fold(key))) {
                words.forEach(word -> result.add(prefix + word));
            }
        });
        return result.stream().filter(value -> !fold(value).equals(fold(query))).limit(6).toList();
    }

    public boolean matchesCategory(Map<String, Object> place, String id) {
        if (id.isBlank()) return true;
        Category group = categories.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
        if (group == null) return false;
        if (group.types.contains(String.valueOf(place.get("category")))) return true;
        String content = place.get("name") + " " + place.get("tags") + " " + place.get("categoryLabel");
        return group.items.stream().anyMatch(topic -> matches(content, topic));
    }

    public String categoryKeyword(String id) {
        return categories.stream().filter(item -> item.id.equals(id)).map(Category::keyword).findFirst().orElse("");
    }

    public String contentType(String id) {
        return categories.stream().filter(item -> item.id.equals(id)).map(Category::contentType).findFirst().orElse("");
    }

    public String upstreamQuery(String query) {
        return Arrays.stream(phrase(query).split("\\s+")).map(term -> dictionary.upstreamKeywords.getOrDefault(term, term))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
