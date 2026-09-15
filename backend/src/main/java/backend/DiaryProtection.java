package backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiaryProtection {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProgressService progress;
    private final int maxEntries;
    private final long maxBytes;
    private static final List<String> COLUMNS=List.of("title","body","place_name","visited_date","weather_icon","page_color","font_family","image_url","drawing_image","drawing_layout","badge_stickers");
    public DiaryProtection(JdbcTemplate jdbc,ObjectMapper mapper,ProgressService progress,
            @Value("${soomgil.diary.max-entries:300}") int maxEntries,@Value("${soomgil.diary.max-bytes:67108864}") long maxBytes) {
        if (maxEntries<1 || maxBytes<1) throw new IllegalArgumentException("Diary quotas must be positive");
        this.jdbc=jdbc; this.mapper=mapper; this.progress=progress; this.maxEntries=maxEntries; this.maxBytes=maxBytes;
    }
    void initialize() {
        jdbc.execute("ALTER TABLE diary_entries ADD COLUMN IF NOT EXISTS storage_bytes BIGINT");
        jdbc.execute("CREATE INDEX IF NOT EXISTS diary_owner_idx ON diary_entries(user_id)");
        String size=COLUMNS.stream().map(column -> column.equals("visited_date") ? "CASE WHEN visited_date IS NULL THEN 0 ELSE 10 END" : "COALESCE(OCTET_LENGTH("+column+"),0)").collect(java.util.stream.Collectors.joining("+"));
        // Backfill accounting only; existing photos and records are not rewritten or deleted.
        jdbc.update("UPDATE diary_entries SET storage_bytes="+size+" WHERE storage_bytes IS NULL");
    }
    record Draft(String title,String body,String placeName,LocalDate date,String weather,String color,String font,
                 String image,String drawing,String layout,String stickers,long bytes) {}
    Draft prepare(String user,Long id,Map<String,Object> body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Diary quota checks require a write transaction");
        if (jdbc.queryForList("SELECT username FROM user_accounts WHERE username=? FOR UPDATE",user).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Map<String,Object> previous=Map.of();
        if (id!=null) {
            var rows=jdbc.queryForList("SELECT * FROM diary_entries WHERE id=? AND user_id=?",id,user);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"기록을 찾을 수 없습니다.");
            previous=rows.get(0);
        }
        Draft draft=validate(body,previous,new HashSet<>(progress.earnedIds(user)));
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?",Long.class,user);
        long used=jdbc.queryForObject("SELECT COALESCE(SUM(storage_bytes),0) FROM diary_entries WHERE user_id=?",Long.class,user);
        long old=previous.get("storage_bytes") instanceof Number bytes ? bytes.longValue() : 0;
        // Accounts already over quota may still shrink or delete existing records.
        if ((id==null && count>=maxEntries) || (used-old+draft.bytes()>maxBytes && (id==null || draft.bytes()>old)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"다이어리 저장 한도에 도달했어요. 기존 사진이나 기록을 정리한 뒤 다시 저장해주세요.");
        return draft;
    }
    Draft validate(Map<String,Object> body,Map<String,Object> previous,Set<String> earned) {
        String title=field(body,"title","",150).trim();
        if (title.isEmpty()) throw bad("제목을 입력해주세요.");
        String content=field(body,"body","",3000),place=field(body,"placeName","",200);
        String dateText=field(body,"visitedDate",LocalDate.now().toString(),10);
        if (!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || dateText.startsWith("0000")) throw bad("여행 날짜를 확인해주세요.");
        LocalDate date=LocalDate.parse(dateText);
        String weather=choice(body,"weatherIcon","sun",Set.of("sun","cloud","rain","snow"));
        String color=field(body,"pageColor","#ffffff",7);
        if (!color.matches("#[a-fA-F0-9]{6}")) throw bad("페이지 색상을 확인해주세요.");
        String font=choice(body,"fontFamily","sans-serif",Set.of("sans-serif","serif","monospace","Pretendard"));
        String layout=choice(body,"drawingLayout","inline",Set.of("inline","free"));
        String image=UploadImages.validate(field(body,"imageUrl","",UploadImages.MAX_ENCODED),Objects.toString(previous.get("image_url"),""));
        String drawing=UploadImages.validate(field(body,"drawingImage","",UploadImages.MAX_ENCODED),Objects.toString(previous.get("drawing_image"),""));
        String stickers=stickers(body.get("badgeStickers"),earned);
        long size=List.of(title,content,place,date.toString(),weather,color,font,image,drawing,layout,stickers).stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
        return new Draft(title,content,place,date,weather,color,font,image,drawing,layout,stickers,size);
    }
    private String stickers(Object raw,Set<String> earned) {
        if (raw==null || raw.equals("")) return "[]";
        try {
            if (raw instanceof String text && text.length()>3000) throw bad("배지 데이터가 너무 큽니다.");
            JsonNode node=raw instanceof String text ? mapper.readTree(text) : mapper.valueToTree(raw);
            if (node==null || !node.isArray() || node.size()>20) throw bad("배지는 최대 20개까지 배치할 수 있습니다.");
            ArrayNode result=mapper.createArrayNode();
            for (JsonNode sticker : node) {
                if (!sticker.isObject()) throw bad("배지 형식을 확인해주세요.");
                var keys=sticker.fieldNames();
                while(keys.hasNext()) if (!Set.of("id","badgeId","x","y","scale","rotation").contains(keys.next())) throw bad("배지 형식을 확인해주세요.");
                JsonNode badge=sticker.has("badgeId") ? sticker.get("badgeId") : sticker.get("id");
                if (badge==null || !badge.isTextual() || !earned.contains(badge.asText()) || (sticker.has("id") && sticker.has("badgeId"))) throw bad("획득한 배지만 배치할 수 있습니다.");
                var item=result.addObject(); item.put("badgeId",badge.asText());
                item.put("x",number(sticker,"x",50,0,100)); item.put("y",number(sticker,"y",50,0,100));
                item.put("scale",number(sticker,"scale",1,.25,4)); item.put("rotation",number(sticker,"rotation",0,-360,360));
            }
            String value=mapper.writeValueAsString(result);
            if (value.length()>3000) throw bad("배지 데이터가 너무 큽니다.");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) { throw bad("배지 형식을 확인해주세요."); }
    }
    private static double number(JsonNode node,String key,double fallback,double min,double max) {
        if (!node.has(key)) return fallback;
        var value=node.get(key); double number=value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(number) || number<min || number>max) throw bad("배지 위치와 크기를 확인해주세요.");
        return number;
    }
    static String field(Map<String,Object> body,String key,String fallback,int max) {
        Object raw=body.get(key);
        if (raw==null) return fallback;
        if (!(raw instanceof String value) || value.length()>max) throw bad("입력한 내용의 형식이나 길이를 확인해주세요.");
        return value;
    }
    private static String choice(Map<String,Object> body,String key,String fallback,Set<String> allowed) {
        String value=field(body,key,fallback,30);
        if (!allowed.contains(value)) throw bad("다이어리 꾸미기 설정을 확인해주세요.");
        return value;
    }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
