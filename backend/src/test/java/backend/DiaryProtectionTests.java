package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiaryProtectionTests {
    static class Db {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final DiaryProtection protection;
        Db(int count,long bytes) {
            var source=new DriverManagerDataSource("jdbc:h2:mem:diary-"+UUID.randomUUID()+";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
            jdbc=new JdbcTemplate(source); tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            jdbc.execute("CREATE TABLE user_accounts(username VARCHAR(40) PRIMARY KEY)");
            jdbc.update("INSERT INTO user_accounts VALUES('owner')");
            jdbc.execute("CREATE TABLE diary_entries(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id VARCHAR(40),title VARCHAR(150),body LONGTEXT,place_name VARCHAR(200),visited_date DATE,weather_icon VARCHAR(30),page_color VARCHAR(30),font_family VARCHAR(30),image_url LONGTEXT,drawing_image LONGTEXT,drawing_layout VARCHAR(20),badge_stickers VARCHAR(3000))");
            var progress=mock(ProgressService.class); when(progress.earnedIds("owner")).thenReturn(List.of("first-visit"));
            protection=new DiaryProtection(jdbc,new ObjectMapper(),progress,count,bytes); protection.initialize();
        }
        void save() {
            tx.executeWithoutResult(status -> {
                var draft=protection.prepare("owner",null,Map.of("title","여행 기록"));
                jdbc.update("INSERT INTO diary_entries(user_id,title,storage_bytes) VALUES('owner',?,?)",draft.title(),draft.bytes());
            });
        }
    }
    @Test void drawingAndStickerFieldsCannotBypassImageValidationOrBadgeOwnership() {
        var db=new Db(300,1000000);
        var base=Map.<String,Object>of("title","기록");
        var valid=new HashMap<>(base); valid.put("badgeStickers","[{\"badgeId\":\"first-visit\",\"x\":10,\"rotation\":90}]");
        assertThat(db.protection.validate(valid,Map.of(),Set.of("first-visit")).stickers()).contains("first-visit");
        for (Object invalid : List.of("{not-json}","{}","[\"first-visit\"]","[{\"badgeId\":\"unearned\"}]","[{\"badgeId\":\"first-visit\",\"x\":101}]",Collections.nCopies(21,Map.of("badgeId","first-visit")))) {
            var input=new HashMap<>(base); input.put("badgeStickers",invalid);
            assertThatThrownBy(() -> db.protection.validate(input,Map.of(),Set.of("first-visit"))).isInstanceOf(ResponseStatusException.class);
        }
        var drawing=new HashMap<>(base); drawing.put("drawingImage","data:image/png;base64,AAAA");
        assertThatThrownBy(() -> db.protection.validate(drawing,Map.of(),Set.of())).isInstanceOf(ResponseStatusException.class);
        for (var entry : Map.of("placeName","a".repeat(201),"pageColor","url(https://example.test)","fontFamily","unexpected","weatherIcon","unexpected").entrySet()) {
            var input=new HashMap<>(base); input.put(entry.getKey(),entry.getValue());
            assertThatThrownBy(() -> db.protection.validate(input,Map.of(),Set.of())).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void accountingIncludesFullLegacyImageStringsAndUtf8WithoutChangingTheRecord() {
        var db=new Db(300,1000000);
        String image="legacy".repeat(1000);
        db.jdbc.update("INSERT INTO diary_entries(user_id,title,image_url,visited_date) VALUES('owner','한글',?,'2026-09-15')",image);
        db.protection.initialize();
        assertThat(db.jdbc.queryForObject("SELECT storage_bytes FROM diary_entries",Long.class)).isEqualTo(6+image.length()+10);
        assertThat(db.jdbc.queryForObject("SELECT image_url FROM diary_entries",String.class)).isEqualTo(image);
        assertThatThrownBy(() -> db.protection.prepare("owner",null,Map.of("title","x"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void countQuotaRejectsNewWritesButDeletionRestoresCapacityAndEditsUseADelta() {
        var db=new Db(1,1000); db.save();
        assertThatThrownBy(db::save).isInstanceOf(ResponseStatusException.class);
        long id=db.jdbc.queryForObject("SELECT id FROM diary_entries",Long.class);
        assertThatCode(() -> db.tx.execute(status -> db.protection.prepare("owner",id,Map.of("title","수정")))).doesNotThrowAnyException();
        db.jdbc.update("DELETE FROM diary_entries"); db.save();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries",Long.class)).isEqualTo(1);
    }
    @Test void byteQuotaAndConcurrentCreatesCannotExceedTheAllowance() throws Exception {
        var small=new Db(300,10); assertThatThrownBy(small::save).isInstanceOf(ResponseStatusException.class);
        var db=new Db(2,1000000);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var jobs=new ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i=0;i<8;i++) jobs.add(() -> { try { db.save(); return 1; } catch(ResponseStatusException expected) { return 0; } });
            int successes=0; for (var result:workers.invokeAll(jobs)) successes+=result.get();
            assertThat(successes).isEqualTo(2);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries",Long.class)).isEqualTo(2);
        } finally { workers.shutdownNow(); }
    }

    @Test void overQuotaAccountsCanShrinkButNotGrowAndRolledBackWritesDoNotConsumeStorage() {
        var db=new Db(300,100);
        db.jdbc.update("INSERT INTO diary_entries(user_id,title,body,storage_bytes) VALUES('owner','Legacy',?,500)","a".repeat(450));
        long id=db.jdbc.queryForObject("SELECT id FROM diary_entries",Long.class);
        assertThatCode(() -> db.tx.execute(status -> db.protection.prepare("owner",id,Map.of("title","Smaller","body","a".repeat(200))))).doesNotThrowAnyException();
        assertThatThrownBy(() -> db.tx.execute(status -> db.protection.prepare("owner",id,Map.of("title","Larger","body","a".repeat(550))))).isInstanceOf(ResponseStatusException.class);
        db.jdbc.update("DELETE FROM diary_entries");
        db.tx.executeWithoutResult(status -> { db.save(); status.setRollbackOnly(); });
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries",Long.class)).isZero();
        assertThatCode(db::save).doesNotThrowAnyException();
    }
}
